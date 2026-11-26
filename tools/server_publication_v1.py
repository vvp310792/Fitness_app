"""Verify a complete content-addressed fitness publication before committing v1 status.

No credentials, filesystem paths, network configuration or private measurements.
Callers must serialize publication (including verification and final status write).
Fail closed: do not delete stale groups or repair remote payloads here.
"""
import datetime as dt
import hashlib
import json

from server_manifest_contract import status_fields

MAX_PART_BYTES = 1_000_000
MAX_GROUPS = 10_000
MAX_ROWS = 1_000_000
BATCH_SIZE = 20
MANIFEST_FIELDS = ("source", "kind", "month", "count", "parts", "sha256")


class PublicationError(ValueError):
    """A safe, non-payload-bearing validation error."""


def require(ok, reason):
    if not ok:
        raise PublicationError(reason)


def _catalog(rows):
    items = list(rows)
    require(len(items) <= MAX_GROUPS, "too many groups")
    try:
        fields = status_fields(items)
    except (KeyError, TypeError, ValueError, AttributeError):
        raise PublicationError("invalid manifest catalog") from None
    require(fields["records"] <= MAX_ROWS, "too many rows")
    return {m["id"]: m for m in items}, fields


def _decode(part):
    has_json = isinstance(part.get("rowsJson"), str)
    has_zlib = isinstance(part.get("rowsZlib"), (bytes, bytearray))
    require(has_json != has_zlib, "invalid payload representation")
    require((part.get("rowsJson") is None) != (part.get("rowsZlib") is None),
            "ambiguous payload representation")
    if has_json:
        raw = part["rowsJson"].encode("utf-8")
    else:
        import zlib
        packed = part["rowsZlib"]
        require(len(packed) <= MAX_PART_BYTES, "compressed part too large")
        try:
            dec = zlib.decompressobj()
            raw = dec.decompress(packed, MAX_PART_BYTES + 1)
            require(dec.eof and not dec.unused_data and not dec.unconsumed_tail,
                    "incomplete or oversized compressed part")
        except zlib.error:
            raise PublicationError("invalid compressed part") from None
    require(len(raw) <= MAX_PART_BYTES, "part too large")
    return raw


def _reject_constant(_):
    raise PublicationError("non-finite JSON value")


def verify_part(part_id, part, manifest, identities):
    for field in ("source", "kind", "month"):
        require(part.get(field) == manifest[field], "part metadata mismatch")
    require(type(part.get("count")) is int and 1 <= part["count"] <= manifest["count"],
            "invalid part count")
    sha = part_id.removeprefix(manifest["id"] + "__")
    require(part.get("sha256") == sha, "part identity mismatch")
    raw = _decode(part)
    require(hashlib.sha256(raw).hexdigest() == sha, "part content hash mismatch")
    try:
        rows = json.loads(raw.decode("utf-8", errors="strict"), parse_constant=_reject_constant)
    except (UnicodeError, ValueError):
        raise PublicationError("invalid UTF-8 JSON payload") from None
    require(isinstance(rows, list) and len(rows) == part["count"], "part row count mismatch")
    for row in rows:
        require(isinstance(row, dict), "invalid row")
        native_id = row.get("id")
        day = row.get("day")
        require(isinstance(native_id, str) and bool(native_id.strip()), "invalid native identity")
        require(isinstance(day, str), "invalid day")
        if day != "undated":
            try:
                require(dt.date.fromisoformat(day).isoformat() == day, "invalid day")
            except ValueError:
                raise PublicationError("invalid day") from None
        require(day[:7] == manifest["month"], "row belongs to another month")
        require(row.get("data") is not None, "missing data")
        identity = (manifest["source"], manifest["kind"], native_id)
        require(identity not in identities, "duplicate source identity across parts")
        identities.add(identity)
    return len(rows)


def verify_publication(expected, actual, read_parts):
    """All manifests must match local expected metadata, then every referenced
    part is retrieved and verified. read_parts(ids) returns {id: document}.
    Reads are bounded by BATCH_SIZE; records are not returned or logged.
    """
    expected_by_id, fields = _catalog(expected)
    actual_by_id, remote_fields = _catalog(actual)
    require(expected_by_id.keys() == actual_by_id.keys(), "missing or stale manifest group")
    require(fields == remote_fields, "manifest-set commitment mismatch")
    require(all(all(expected_by_id[key][f] == actual_by_id[key][f] for f in MANIFEST_FIELDS)
                for key in expected_by_id), "manifest metadata mismatch")
    targets = [(part, m) for m in expected_by_id.values() for part in m["parts"]]
    require(len({p for p, _ in targets}) == len(targets), "duplicate part reference")
    counts = dict.fromkeys(expected_by_id, 0)
    identities = set()
    for i in range(0, len(targets), BATCH_SIZE):
        batch = targets[i:i+BATCH_SIZE]
        ids = [part for part, _ in batch]
        documents = read_parts(ids)
        require(isinstance(documents, dict) and documents.keys() == set(ids),
                "missing or unexpected referenced part")
        for part_id, m in batch:
            counts[m["id"]] += verify_part(part_id, documents[part_id], m, identities)
            require(counts[m["id"]] <= m["count"], "group row count mismatch")
    require(all(counts[k] == m["count"] for k, m in expected_by_id.items()),
            "group row count mismatch")
    require(len(identities) == fields["records"], "catalog row count mismatch")
    return fields


def confirm_and_publish(c, db, user, manifest_jobs, sqlite_version, server_timestamp):
    """Publisher integration: use existing auth, write final v1 status only.
    Existing content-addressed part writes must have completed first.
    The caller must hold the existing scheduler's exclusive publication lock.
    A stale or missing cloud group is a hard failure; no data is deleted.
    """
    expected = [dict(obj, id=ref.id) for ref, obj in manifest_jobs]
    manifest_collection = user.collection("server_manifest")
    part_collection = user.collection("server_records")
    status_ref = user.collection("server_sync").document("status")

    def read_catalog():
        return [dict(s.to_dict(), id=s.id) for s in manifest_collection.stream(timeout=120)]

    def read_parts(ids):
        snaps = list(db.get_all([part_collection.document(i) for i in ids], timeout=120))
        require(all(s.exists for s in snaps), "missing referenced part")
        require(len(snaps) == len({s.id for s in snaps}), "duplicate returned part")
        return {s.id: s.to_dict() for s in snaps}

    prior = status_ref.get(timeout=30)
    fields = verify_publication(expected, read_catalog(), read_parts)
    require(_catalog(read_catalog())[1] == fields, "catalog changed during verification")
    require(c.execute("PRAGMA data_version").fetchone()[0] == sqlite_version,
            "SQLite changed during verification")
    current = status_ref.get(timeout=30)
    require(current.exists == prior.exists and current.update_time == prior.update_time,
            "another writer changed publication status")
    previous = current.to_dict() or {}
    require(not (previous.get("records", 0) > 0 and fields["records"] == 0),
            "refuse to replace history with an empty catalog")
    sources = [dict(source=a, checkedAt=b, status=s, detail=d)
               for a,b,s,d in c.execute("SELECT source,checked_at,status,detail FROM sync_state")]
    status_ref.set(dict(fields, updatedAt=server_timestamp, sources=sources), timeout=30)
    check = status_ref.get(timeout=30).to_dict() or {}
    require(all(check.get(k) == v for k,v in fields.items()) and check.get("updatedAt") is not None,
            "status readback mismatch")
    return fields
