"""Cross-platform manifest-set commitment for the independent fitness publisher.

Pure helper only; no Firebase credentials, personal paths or network calls.
The production publisher should set these fields in server_sync/status ONLY
after every manifest and referenced immutable part has been written/read back.
"""
import hashlib
import json
import re


def sha256(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def manifest_set_sha256(manifests):
    lines = []
    seen = set()
    for m in manifests:
        key = m["source"] + "__" + m["kind"] + "__" + m["month"]
        if m["id"] != key or key in seen:
            raise ValueError("invalid/duplicate group")
        if not re.fullmatch(r"[a-z][a-z0-9_-]*", m["source"]) or not re.fullmatch(r"[a-z][a-z0-9_-]*", m["kind"]):
            raise ValueError("invalid name")
        if not re.fullmatch(r"[0-9]{4}-(0[1-9]|1[0-2])|undated", m["month"]):
            raise ValueError("invalid month")
        seen.add(key)
        parts = m["parts"]
        count = m["count"]
        if type(count) is not int or count < 1 or not parts or len(parts) > count or len(set(parts)) != len(parts):
            raise ValueError("invalid count/parts")
        if not all(re.fullmatch(re.escape(key) + r"__[0-9a-f]{64}", p) for p in parts):
            raise ValueError("invalid part identity")
        expected = sha256(json.dumps(parts, ensure_ascii=False, separators=(",", ":")))
        if expected != m["sha256"]:
            raise ValueError("manifest hash mismatch")
        lines.append((key, expected, count))
    return sha256("".join(f"{key}\t{digest}\t{count}\n" for key, digest, count in sorted(lines)))


def status_fields(manifests):
    rows = list(manifests)
    return {
        "schemaVersion": 1,
        "manifestSetSha256": manifest_set_sha256(rows),
        "groups": len(rows),
        "records": sum(m["count"] for m in rows),
    }
