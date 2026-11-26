import hashlib
import json
import unittest
import zlib

from server_manifest_contract import sha256
from server_publication_v1 import PublicationError, verify_publication, MAX_PART_BYTES


def fixture(source="garmin", kind="daily", month="2026-11", rows=None, compressed=True):
    if rows is None:
        rows = [{"id":"sample", "day":month+"-25", "data":{"steps":12}}]
    raw = json.dumps(rows, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    digest = hashlib.sha256(raw).hexdigest()
    key = "__".join((source,kind,month))
    ident = key+"__"+digest
    part = dict(source=source,kind=kind,month=month,count=len(rows),sha256=digest)
    part["rowsZlib" if compressed else "rowsJson"] = zlib.compress(raw) if compressed else raw.decode("utf-8")
    manifest = dict(id=key,source=source,kind=kind,month=month,count=len(rows),parts=[ident],
                    sha256=sha256(json.dumps([ident],separators=(",", ":"))))
    return manifest, {ident:part}


class VerifyPublicationTest(unittest.TestCase):
    def verify(self, expected, actual, parts):
        return verify_publication(expected,actual,lambda ids:{i:parts[i] for i in ids if i in parts})

    def test_accepts_all_parts_and_matches_client_commitment(self):
        m,p = fixture(rows=[{"id":"a","day":"2026-11-25","data":{"steps":12}}])
        status = self.verify([m],[m],p)
        self.assertEqual(status["manifestSetSha256"],
                         "370623c3de42e233900997c60a6a306c35ce4fc7427077837d91f3ec86586359")
        self.assertEqual((status["schemaVersion"],status["groups"],status["records"]),(1,1,1))

    def test_uncompressed_and_compressed_work(self):
        a,p = fixture()
        b,q = fixture(source="google_health",compressed=False)
        result=self.verify([a,b],[b,a],p|q)
        self.assertEqual(result["records"],2)

    def test_manifest_missing_or_stale(self):
        a,p=fixture()
        b,q=fixture(source="gymup")
        for expected,actual in [([a],[]),([a],[a,b])]:
            with self.assertRaises(PublicationError):
                self.verify(expected,actual,p|q)

    def test_missing_part(self):
        a,_=fixture()
        with self.assertRaises(PublicationError):
            self.verify([a],[a],{})

    def test_tampered_payload(self):
        a,p=fixture(compressed=False)
        next(iter(p.values()))["rowsJson"]+=" "
        with self.assertRaises(PublicationError):
            self.verify([a],[a],p)

    def test_tampered_count(self):
        a,p=fixture()
        next(iter(p.values()))["count"]=2
        with self.assertRaises(PublicationError):
            self.verify([a],[a],p)

    def test_wrong_month(self):
        a,p=fixture(rows=[{"id":"a","day":"2026-10-25","data":{}}])
        with self.assertRaises(PublicationError):
            self.verify([a],[a],p)

    def test_duplicate_id_across_months(self):
        a,p=fixture()
        b,q=fixture(month="2026-10")
        with self.assertRaises(PublicationError):
            self.verify([a,b],[a,b],p|q)

    def test_invalid_zlib_or_trailing_bytes(self):
        a,p=fixture()
        original=next(iter(p.values()))["rowsZlib"]
        for bad in (b"garbage",original+b"x",original[:-1]):
            next(iter(p.values()))["rowsZlib"]=bad
            with self.assertRaises(PublicationError):
                self.verify([a],[a],p)

    def test_decompression_bound(self):
        a,p=fixture(rows=[{"id":"x","day":"2026-11-25","data":"x"*MAX_PART_BYTES}])
        with self.assertRaises(PublicationError):
            self.verify([a],[a],p)

    def test_invalid_json_value_or_non_string_id(self):
        for row in ({"id":5,"day":"2026-11-25","data":{}},
                    {"id":"a","day":"2026-11-25","data":{"n":float("nan")}},
                    {"id":"a","day":"2026-11-25","data":None}):
            a,p=fixture(rows=[row])
            with self.assertRaises(PublicationError):
                self.verify([a],[a],p)
