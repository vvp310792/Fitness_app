"""Доступ к базе приложения с ПК: чтение зеркала Firestore и запись через входящие.

Запуск (ключ сервисного аккаунта приходит из Bitwarden Secrets Manager переменной
FITNESS_FIREBASE_JSON, файлом на диске не лежит):

    python F:\\Claude\\tools\\bws\\bwsrun.py python -X utf8 tools\\fsdb.py status
    ... fsdb.py pull [--out C:\\Users\\Valerii\\fitness_data\\fitness_mirror.db]
    ... fsdb.py send upsert|patch|delete|mirror ТАБЛИЦА [--row JSON] [--key JSON]
    ... fsdb.py results [--wait СЕКУНД]
    ... fsdb.py clean
    ... fsdb.py logs [--hours 24] [--errors] [-v] [--prune-days N]

Устройство зеркала и входящих — CLAUDE.md, раздел «Зеркало базы и входящие для Claude».
"""
import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import pathlib
import secrets
import sqlite3
import sys
import time

import firebase_admin
from firebase_admin import credentials, firestore

# Данные о здоровье — только локально, вне Syncthing и репозитория.
DEFAULT_OUT = pathlib.Path.home() / "fitness_data" / "fitness_mirror.db"


def connect():
    raw = os.environ.get("FITNESS_FIREBASE_JSON")
    if not raw:
        sys.exit("нет FITNESS_FIREBASE_JSON — запускать через F:\\Claude\\tools\\bws\\bwsrun.py")
    firebase_admin.initialize_app(credentials.Certificate(json.loads(raw)))
    return firestore.client()


def pick_uid(db, uid):
    if uid:
        return uid
    # Документа users/{uid} может не быть — есть только подколлекции; list_documents
    # возвращает и такие «пустые» узлы, обычный stream() их не видит.
    uids = [d.id for d in db.collection("users").list_documents()]
    with_mirror = [u for u in uids if db.collection("users").document(u)
                   .collection("mirror_meta").document("manifest").get().exists]
    found = with_mirror or uids
    if len(found) != 1:
        sys.exit(f"uid не определён однозначно ({len(found)} шт.), укажите --uid")
    return found[0]


def user(db, uid):
    return db.collection("users").document(uid)


def cmd_status(db, uid, _):
    u = user(db, uid)
    schema = u.collection("mirror_meta").document("schema").get()
    manifest = u.collection("mirror_meta").document("manifest").get()
    print("uid:", uid[:6] + "…")
    if not schema.exists:
        # Схема пишется последней, после всех кусков: куски без неё — это прерванный проход
        # (приложение ушло в фон), а не старая версия — так и надо подсказывать.
        parts = sum(1 for _ in u.collection("mirror").list_documents())
        if parts:
            print(f"зеркало не дописано: {parts} кусков без схемы — проход прервался, "
                  "открыть приложение на телефоне и не сворачивать до конца выгрузки")
        else:
            print("зеркала нет: телефон ещё не выгрузил базу (нужен build-47+ и вход в аккаунт)")
    else:
        s = schema.to_dict()
        print("схема: версия базы", s.get("dbVersion"), "таблиц", len(json.loads(s["schemaJson"])),
              "обновлена", s.get("updatedAt"))
    if manifest.exists:
        m = manifest.to_dict()
        print("манифест: документов", len(json.loads(m["manifestJson"])), "обновлён", m.get("updatedAt"))
    inbox = list(u.collection("inbox").stream())
    done = sum(1 for d in inbox if d.get("appliedAt") is not None)
    print(f"входящие: всего {len(inbox)}, применено {done}, ждут телефона {len(inbox) - done}")


def cmd_pull(db, uid, args):
    u = user(db, uid)
    schema_doc = u.collection("mirror_meta").document("schema").get()
    manifest_doc = u.collection("mirror_meta").document("manifest").get()
    if not schema_doc.exists or not manifest_doc.exists:
        sys.exit("зеркала нет: телефон ещё не выгрузил базу")
    schema = json.loads(schema_doc.get("schemaJson"))
    manifest = json.loads(manifest_doc.get("manifestJson"))
    docs = {d.id: d.to_dict() for d in u.collection("mirror").stream()}

    # Полнота: телефон пишет месяцы и манифест разными записями, и чтение посреди его выгрузки
    # дало бы смесь старых и новых месяцев. Манифест обновляется последним, поэтому сверяем
    # с ним каждый месяц целиком — хеш склейки частей, как его считает MirrorLayout.
    problems = []
    missing = sorted(set(manifest) - set(docs))
    if missing:
        problems.append(f"нет документов из манифеста: {len(missing)} (например {missing[0]})")
    extra = sorted(set(docs) - set(manifest))
    months = {}
    for doc_id in manifest:
        d = docs.get(doc_id)
        if d:
            months.setdefault((d["table"], d["month"]), []).append(d)
    for (table, month), parts in months.items():
        parts.sort(key=lambda p: p["part"])
        expected = manifest[f"{table}__{month}" if parts[0]["parts"] <= 1 else f"{table}__{month}__p1"]
        if len(parts) != expected["parts"]:
            problems.append(f"{table} {month}: частей {len(parts)} из {expected['parts']}")
            continue
        joined = "\n".join(p["rowsJson"] for p in parts)
        if hashlib.sha256(joined.encode("utf-8")).hexdigest() != expected["monthHash"]:
            problems.append(f"{table} {month}: хеш месяца не совпал с манифестом")
    if problems and not args.force:
        print("копия неполная — телефон, вероятно, ещё выгружает; повторите позже или --force:")
        for p in problems[:20]:
            print("  ", p)
        sys.exit(1)

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)
    con = sqlite3.connect(tmp)
    counts = {}
    for table, cols in schema.items():
        pk = [c["name"] for c in sorted(cols, key=lambda c: c["pk"]) if c["pk"] > 0]
        defs = [f'"{c["name"]}" {c["type"]}' + (" NOT NULL" if c["notNull"] else "") for c in cols]
        if pk:
            defs.append("PRIMARY KEY (" + ", ".join(f'"{n}"' for n in pk) + ")")
        con.execute(f'CREATE TABLE "{table}" ({", ".join(defs)})')
        blobs = {c["name"] for c in cols if c["type"].upper() == "BLOB"}
        names = [c["name"] for c in cols]
        sql = f'INSERT OR REPLACE INTO "{table}" ({", ".join(chr(34) + n + chr(34) for n in names)}) ' \
              f'VALUES ({", ".join("?" * len(names))})'
        n = 0
        for (t, _), parts in months.items():
            if t != table:
                continue
            for p in parts:
                for row in json.loads(p["rowsJson"]):
                    vals = [base64.b64decode(row[k]) if k in blobs and isinstance(row.get(k), str)
                            else row.get(k) for k in names]
                    con.execute(sql, vals)
                    n += 1
        counts[table] = n
    con.execute("CREATE TABLE _mirror_info (key TEXT PRIMARY KEY, value TEXT)")
    con.executemany("INSERT INTO _mirror_info VALUES (?, ?)", [
        ("pulled_at", dt.datetime.now(dt.timezone.utc).isoformat()),
        ("db_version", str(schema_doc.get("dbVersion"))),
        ("problems", json.dumps(problems, ensure_ascii=False)),
    ])
    con.commit()
    con.close()
    os.replace(tmp, out)
    for t, n in sorted(counts.items()):
        print(f"{t}: {n}")
    if extra:
        print(f"документов вне манифеста (пропущены): {len(extra)}")
    print("проблем:", len(problems), "→", out)


def new_inbox_id():
    # Телефон применяет входящие в порядке id — id должен сортироваться по времени отправки.
    now = dt.datetime.now(dt.timezone.utc)
    return now.strftime("%Y%m%dT%H%M%S") + f"{now.microsecond // 1000:03d}Z-" + secrets.token_hex(3)


def cmd_send(db, uid, args):
    doc = {"op": args.op, "table": args.table, "createdAt": firestore.SERVER_TIMESTAMP}
    if args.row is not None:
        json.loads(args.row)  # не отправлять битый JSON — телефон всё равно откажет
        doc["rowJson"] = args.row
    if args.key is not None:
        json.loads(args.key)
        doc["keyJson"] = args.key
    doc_id = new_inbox_id()
    user(db, uid).collection("inbox").document(doc_id).set(doc)
    print("отправлено:", doc_id)


def cmd_results(db, uid, args):
    deadline = time.time() + args.wait
    while True:
        inbox = sorted(user(db, uid).collection("inbox").stream(), key=lambda d: d.id)
        waiting = [d for d in inbox if d.get("appliedAt") is None]
        if not waiting or time.time() >= deadline:
            break
        time.sleep(5)
    for d in inbox:
        x = d.to_dict()
        state = x.get("result") if x.get("appliedAt") is not None else "ждёт телефона"
        print(d.id, x.get("op"), x.get("table"), "→", state)


def cmd_logs(db, uid, args):
    since_ms = int((time.time() - args.hours * 3600) * 1000)
    col = user(db, uid).collection("logs")
    # id начинается с UTC-времени первой записи пачки — фильтр по id не требует индекса.
    start = dt.datetime.fromtimestamp(since_ms / 1000 - 86400, dt.timezone.utc).strftime("%Y%m%dT")
    docs = list(col.where(filter=firestore.FieldFilter("__name__", ">=", col.document(start))).stream())
    rows, seen = [], set()
    for d in docs:
        x = d.to_dict()
        for e in json.loads(x.get("entriesJson") or "[]"):
            # Пачку, отправленную повторно после обрыва, телефон может собрать иначе — убираем дубли.
            k = (x.get("installId"), e.get("t"), e.get("tag"), e.get("msg"))
            if e.get("t", 0) < since_ms or k in seen or (args.errors and e.get("level") != "ERROR"):
                continue
            seen.add(k)
            rows.append((e, x))
    rows.sort(key=lambda r: r[0].get("t", 0))
    for e, x in rows:
        t = dt.datetime.fromtimestamp(e["t"] / 1000).strftime("%d.%m %H:%M:%S")
        line = f'{t} {e["level"][0]} [{e["tag"]}] {e["msg"]}'
        if e.get("detail"):
            line += " — " + e["detail"]
        print(line, f'({x.get("appVersion")}, {x.get("installId")})' if args.verbose else "")
        if args.verbose and e.get("stack"):
            print("    " + e["stack"].replace("\n", "\n    "))
    print(f"записей: {len(rows)} за {args.hours} ч, документов: {len(docs)}")
    if args.prune_days:
        cut = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=args.prune_days)).strftime("%Y%m%dT")
        old = [d for d in col.list_documents() if d.id < cut]
        for d in old:
            d.delete()
        print(f"удалено документов старше {args.prune_days} дн.: {len(old)}")


def cmd_clean(db, uid, _):
    n = 0
    for d in user(db, uid).collection("inbox").stream():
        if d.get("appliedAt") is not None:
            d.reference.delete()
            n += 1
    print("удалено применённых:", n)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--uid")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status")
    p = sub.add_parser("pull")
    p.add_argument("--out", default=str(DEFAULT_OUT))
    p.add_argument("--force", action="store_true", help="выгрузить, даже если копия не сошлась с манифестом")
    p = sub.add_parser("send")
    p.add_argument("op", choices=["upsert", "patch", "delete", "mirror"])
    p.add_argument("table", nargs="?", default="")
    p.add_argument("--row")
    p.add_argument("--key")
    p = sub.add_parser("results")
    p.add_argument("--wait", type=int, default=0)
    sub.add_parser("clean")
    p = sub.add_parser("logs", help="предупреждения и ошибки, которые телефон отправил в облако")
    p.add_argument("--hours", type=float, default=24)
    p.add_argument("--errors", action="store_true", help="только ERROR")
    p.add_argument("-v", "--verbose", action="store_true", help="со стеком, версией и устройством")
    p.add_argument("--prune-days", type=int, help="удалить документы старше N дней")
    args = ap.parse_args()

    db = connect()
    uid = pick_uid(db, args.uid)
    {"status": cmd_status, "pull": cmd_pull, "send": cmd_send,
     "results": cmd_results, "clean": cmd_clean, "logs": cmd_logs}[args.cmd](db, uid, args)


if __name__ == "__main__":
    main()
