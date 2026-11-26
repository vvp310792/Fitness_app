import json
import unittest

from server_manifest_contract import sha256, status_fields


def example(source="garmin", kind="daily", month="2026-11"):
    key = "__".join((source, kind, month))
    part = key + "__" + sha256('[{"id":"a","day":"2026-11-25","data":{"steps":12}}]')
    return dict(id=key, source=source, kind=kind, month=month, count=1, parts=[part],
                sha256=sha256(json.dumps([part], separators=(",", ":"))))


class ServerManifestContractTest(unittest.TestCase):
    def test_cross_platform_vector(self):
        status = status_fields([example()])
        self.assertEqual(status["manifestSetSha256"],
                         "370623c3de42e233900997c60a6a306c35ce4fc7427077837d91f3ec86586359")
        self.assertEqual((status["schemaVersion"], status["groups"], status["records"]), (1, 1, 1))

    def test_order_independent(self):
        a, b = example(), example("gymup", "training")
        self.assertEqual(status_fields([a, b]), status_fields([b, a]))

    def test_invalid_hash(self):
        with self.assertRaises(ValueError):
            status_fields([{**example(), "sha256": "0" * 64}])

    def test_duplicate_group(self):
        with self.assertRaises(ValueError):
            status_fields([example(), example()])

    def test_invalid_count(self):
        with self.assertRaises(ValueError):
            status_fields([{**example(), "count": 0}])

    def test_duplicate_parts(self):
        m = example()
        with self.assertRaises(ValueError):
            status_fields([{**m, "count": 2, "parts": m["parts"] * 2}])
