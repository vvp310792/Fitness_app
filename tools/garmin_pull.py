"""Дневные сводки Garmin за N дней с ПК → строки garmin_daily_extra для входящих телефона.

Зачем: телефон синхронизирует Garmin только открытым и часто теряет сеть на середине,
а дни старше 14 сам не перечитывает — пропуски стресса и интенсивных минут остаются
навсегда. Здесь те же запросы, что в GarminApiClient, и тот же разбор полей, поэтому строка
совпадает с той, что записал бы телефон (следующий синк того же дня её просто перезапишет).

Запуск (garminconnect — в F:\\ml\\venv_garmin, логин и пароль — из Bitwarden SM):
    python F:\\Claude\\tools\\bws\\bwsrun.py F:\\ml\\venv_garmin\\Scripts\\python.exe -X utf8 tools\\garmin_pull.py 35
затем отправка (firebase-admin — в anaconda):
    ... bwsrun.py python -X utf8 tools\\fsdb.py upsert-file garmin_daily_extra C:\\Users\\Valerii\\fitness_data\\garmin_daily_extra.json
    ... bwsrun.py python -X utf8 tools\\fsdb.py upsert-file garmin_sleep C:\\Users\\Valerii\\fitness_data\\garmin_sleep.json

Токены входа — секрет GARMIN_TOKENS_JSON в Bitwarden SM (чтобы не ловить код подтверждения
каждый раз; продлённые скрипт пишет туда же), запуск только через bwsrun.py;
сырые ответы и результат — ~/fitness_data (данные о здоровье: вне Syncthing и репозитория).
"""
import datetime as dt
import json
import math
import os
import pathlib
import subprocess
import sys
import time

from garminconnect import Garmin

PY_MAIN = r"C:\ProgramData\anaconda3\python.exe"
DATA = pathlib.Path.home() / "fitness_data"
OUT = DATA / "garmin_daily_extra.json"
SLEEP_OUT = DATA / "garmin_sleep.json"


def ask_mfa():
    # Сессия неинтерактивная: код подтверждения спрашиваем окном, а не через stdin.
    import tkinter as tk
    from tkinter import simpledialog
    root = tk.Tk()
    root.withdraw()
    root.attributes("-topmost", True)
    code = simpledialog.askstring("Garmin", "Код подтверждения Garmin (почта/SMS):", parent=root)
    root.destroy()
    return (code or "").strip()


def login():
    api = Garmin(os.environ["GARMIN_EMAIL"], os.environ["GARMIN_PASSWORD"], prompt_mfa=ask_mfa)
    # JSON-строку токенов garminconnect грузит сам; не подошла — сам же входит по паролю.
    api.login(os.environ.get("GARMIN_TOKENS_JSON") or None)
    save_tokens(api)
    return api


def save_tokens(api):
    # Токены живут в Bitwarden SM, а не файлом на ПК: продлённую пару пишем обратно,
    # иначе следующий запуск получит мёртвую и упрётся в код подтверждения.
    new = api.client.dumps()
    if new == os.environ.get("GARMIN_TOKENS_JSON"):
        return
    # bwsput нужен win32crypt, которого нет в venv Garmin, — запускаем его основным Python.
    r = subprocess.run([PY_MAIN, os.environ["BWSRUN_PUT"], "GARMIN_TOKENS_JSON"],
                       input=new, text=True, capture_output=True)
    print(r.stdout.strip() or r.stderr.strip()[:300])
    if r.returncode == 0:
        os.environ["GARMIN_TOKENS_JSON"] = new


# Как JSONObject.int / rounded / str в GarminApiClient: optInt усекает, отрицательное → 0.
def as_int(j, key):
    v = j.get(key)
    try:
        return max(int(float(v)), 0) if v is not None else 0
    except (TypeError, ValueError):
        return 0


def rounded(j, key):
    v = j.get(key)
    try:
        return max(int(math.floor(float(v) + 0.5)), 0) if v is not None else 0
    except (TypeError, ValueError):
        return 0


def as_str(j, key):
    v = j.get(key)
    return "" if v is None else str(v)


def as_float(j, *keys):
    for key in keys:
        v = j.get(key)
        if v is not None:
            try:
                return float(v)
            except (TypeError, ValueError):
                pass
    return 0.0


def qualifier(scores, component):
    return as_str((scores or {}).get(component) or {}, "qualifierKey")


def parse_sleep(day, root):
    """GarminApiClient.parseSleep; None — ночь пустая (как GarminSleep.isEmpty)."""
    dto = (root or {}).get("dailySleepDTO")
    if not dto:
        return None
    scores = dto.get("sleepScores") or {}
    need = dto.get("sleepNeed") or {}
    has_skin = bool(root.get("skinTempDataExists")) and "avgSkinTempDeviationC" in root
    row = {
        "dateEpochDay": day.toordinal() - EPOCH,
        "sleepSeconds": as_int(dto, "sleepTimeSeconds"),
        "napSeconds": as_int(dto, "napTimeSeconds"),
        "deepSeconds": as_int(dto, "deepSleepSeconds"),
        "lightSeconds": as_int(dto, "lightSleepSeconds"),
        "remSeconds": as_int(dto, "remSleepSeconds"),
        "awakeSeconds": as_int(dto, "awakeSleepSeconds"),
        "unmeasurableSeconds": as_int(dto, "unmeasurableSleepSeconds"),
        "awakeCount": as_int(dto, "awakeCount"),
        "sleepStartLocalMillis": int(dto.get("sleepStartTimestampLocal") or 0),
        "sleepEndLocalMillis": int(dto.get("sleepEndTimestampLocal") or 0),
        "score": as_int(scores.get("overall") or {}, "value"),
        "scoreQualifier": qualifier(scores, "overall"),
        "durationQualifier": qualifier(scores, "totalDuration"),
        "stressQualifier": qualifier(scores, "stress"),
        "awakeCountQualifier": qualifier(scores, "awakeCount"),
        "remQualifier": qualifier(scores, "remPercentage"),
        "restlessnessQualifier": qualifier(scores, "restlessness"),
        "lightQualifier": qualifier(scores, "lightPercentage"),
        "deepQualifier": qualifier(scores, "deepPercentage"),
        "feedback": as_str(dto, "sleepScoreFeedback"),
        "insight": as_str(dto, "sleepScoreInsight"),
        "needBaselineMinutes": as_int(need, "baseline"),
        "needActualMinutes": as_int(need, "actual"),
        "needFeedback": as_str(need, "feedback"),
        "avgSpo2": as_float(dto, "averageSpO2Value", "averageSpo2Value"),
        "lowestSpo2": max(int(dto.get("lowestSpO2Value") or dto.get("lowestSpo2Value") or 0), 0),
        "avgRespiration": as_float(dto, "averageRespirationValue"),
        "avgSleepStress": as_float(dto, "avgSleepStress"),
        "restingHeartRate": as_int(root, "restingHeartRate"),
        "bodyBatteryChange": int(root.get("bodyBatteryChange") or 0),
        "skinTempDeviationC": as_float(root, "avgSkinTempDeviationC") if has_skin else 0.0,
        "hasSkinTemp": has_skin,
        "updatedAtMillis": int(time.time() * 1000),
    }
    return None if row["sleepSeconds"] == 0 and row["score"] == 0 else row


def fetch_sleep(api, day):
    # Порядок эндпоинтов как в приложении: sleep-service, при пустом ответе — wellness-service.
    root = api.connectapi("/sleep-service/sleep/dailySleepData", params={"date": day.isoformat()})
    row = parse_sleep(day, root)
    if row is None:
        root = api.get_sleep_data(day.isoformat())
        row = parse_sleep(day, root)
    return root, row


def ranged(api, path, start, end):
    """forEachStatsItem: страницы по 28 дней — больше Garmin не отдаёт (400)."""
    out = {}
    page_end = end
    while page_end >= start:
        page_start = max(start, page_end - dt.timedelta(days=27))
        for raw in api.connectapi(f"{path}/{page_start}/{page_end}") or []:
            day = raw.get("calendarDate")
            if day:
                out[dt.date.fromisoformat(day[:10]).toordinal()] = raw.get("values") or raw
        page_end = page_start - dt.timedelta(days=1)
    return out


EPOCH = dt.date(1970, 1, 1).toordinal()


def main():
    days = int(sys.argv[1]) if len(sys.argv) > 1 else 35
    api = login()
    today = dt.date.today()
    start = today - dt.timedelta(days=days - 1)

    stress = ranged(api, "/usersummary-service/stats/stress/daily", start, today)
    im = ranged(api, "/usersummary-service/stats/im/daily", start, today)
    hyd = ranged(api, "/usersummary-service/stats/hydration/daily", start, today)

    rows, sleeps, raw_dir = [], [], DATA / "garmin_raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    d = start
    while d <= today:
        sleep_raw, sleep_row = fetch_sleep(api, d)
        (raw_dir / f"sleep_{d}.json").write_text(json.dumps(sleep_raw, ensure_ascii=False), "utf-8")
        if sleep_row:
            sleeps.append(sleep_row)
        j = api.get_user_summary(d.isoformat()) or {}
        (raw_dir / f"summary_{d}.json").write_text(json.dumps(j, ensure_ascii=False), "utf-8")
        z = stress.get(d.toordinal(), {})
        h = hyd.get(d.toordinal(), {})
        goal = as_int(im.get(d.toordinal(), {}), "weeklyGoal")
        row = {
            "dateEpochDay": d.toordinal() - EPOCH,
            "averageStressLevel": as_int(j, "averageStressLevel"),
            "maxStressLevel": as_int(j, "maxStressLevel"),
            "bodyBatteryAtWake": as_int(j, "bodyBatteryAtWakeTime"),
            "bodyBatteryHighest": as_int(j, "bodyBatteryHighestValue"),
            "bodyBatteryLowest": as_int(j, "bodyBatteryLowestValue"),
            "stressQualifier": as_str(j, "stressQualifier"),
            "restStressSeconds": as_int(z, "restStressDuration"),
            "lowStressSeconds": as_int(z, "lowStressDuration"),
            "mediumStressSeconds": as_int(z, "mediumStressDuration"),
            "highStressSeconds": as_int(z, "highStressDuration"),
            "totalSteps": as_int(j, "totalSteps"),
            "totalDistanceMeters": as_int(j, "totalDistanceMeters"),
            "totalKilocalories": as_int(j, "totalKilocalories"),
            "activeKilocalories": as_int(j, "activeKilocalories"),
            "floorsAscended": rounded(j, "floorsAscended"),
            "floorsDescended": rounded(j, "floorsDescended"),
            "moderateIntensityMinutes": as_int(j, "moderateIntensityMinutes"),
            "vigorousIntensityMinutes": as_int(j, "vigorousIntensityMinutes"),
            "intensityMinutesWeeklyGoal": goal,
            "activeSeconds": as_int(j, "activeSeconds"),
            "highlyActiveSeconds": as_int(j, "highlyActiveSeconds"),
            "sedentarySeconds": as_int(j, "sedentarySeconds"),
            "sleepingSeconds": as_int(j, "sleepingSeconds"),
            "restingHeartRate": as_int(j, "restingHeartRate"),
            "minHeartRate": as_int(j, "minHeartRate"),
            "maxHeartRate": as_int(j, "maxHeartRate"),
            "lastSevenDaysAvgRestingHeartRate": as_int(j, "lastSevenDaysAvgRestingHeartRate"),
            "averageSpo2": as_int(j, "averageSpo2"),
            "lowestSpo2": as_int(j, "lowestSpo2"),
            "avgWakingRespiration": rounded(j, "avgWakingRespirationValue"),
            "highestRespiration": rounded(j, "highestRespirationValue"),
            "lowestRespiration": rounded(j, "lowestRespirationValue"),
            "hydrationMl": max(int(math.floor(float(h.get("valueInMl") or 0) + 0.5)), 0),
            "hydrationGoalMl": max(int(math.floor(float(h.get("goalInMl") or 0) + 0.5)), 0),
            "updatedAtMillis": int(time.time() * 1000),
        }
        # Тот же критерий, что GarminDailyExtra.isEmpty: пустой день телефон тоже не пишет.
        if any(row[k] for k in ("averageStressLevel", "maxStressLevel", "bodyBatteryAtWake",
                                "bodyBatteryHighest", "bodyBatteryLowest", "totalSteps",
                                "totalKilocalories", "restingHeartRate",
                                "moderateIntensityMinutes", "vigorousIntensityMinutes")):
            rows.append(row)
        d += dt.timedelta(days=1)
        time.sleep(1)  # Garmin — чужой рабочий сервис: без залпов

    OUT.write_text(json.dumps(rows, ensure_ascii=False), "utf-8")
    SLEEP_OUT.write_text(json.dumps(sleeps, ensure_ascii=False), "utf-8")
    print(f"строк {len(rows)} из {days} дней → {OUT}")
    print(f"ночей {len(sleeps)} из {days} → {SLEEP_OUT}")
    save_tokens(api)  # за долгий проход токен мог продлиться


if __name__ == "__main__":
    main()
