import contextlib
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import audit_data_quality as audit
from audit_all_workout_overlap import Session, load
from materialize_canonical_workouts import reconcile, journal_marks_support_device

T = 1700000000000

class QualityAuditTest(unittest.TestCase):
    def test_invalid_payload_does_not_abort_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            db=root/"source.db"
            c=sqlite3.connect(db)
            c.executescript("""CREATE TABLE records(source,kind,native_id,day,payload,sha);
                CREATE TABLE sync_state(source,checked_at,status,detail);""")
            good=json.dumps({"startTimeGMT":"2023-11-14T22:13:20Z","duration":1800,"elapsedDuration":1800,"activityType":{"typeKey":"strength_training"}})
            for key,payload in [("bad","{"),("nonobject","[]"),("good",good)]:
                c.execute("INSERT INTO records VALUES (?,?,?,?,?,?)",("garmin","activity",key,"2023-11-14",payload,hashlib.sha256(payload.encode()).hexdigest()))
            array="[]"
            c.execute("INSERT INTO records VALUES (?,?,?,?,?,?)",("garmin","training_status_history","array","2023-11-14",array,hashlib.sha256(array.encode()).hexdigest()))
            c.commit()
            c.close()
            with patch.object(audit,"DB",db),patch.object(audit,"OUT",root/"quality"),contextlib.redirect_stdout(io.StringIO()):
                audit.main()
            report=json.loads((root/"quality"/"quality_latest.json").read_text())
            self.assertEqual(report["sqlite"]["invalid_json"],2)
            self.assertEqual(report["accounting"]["canonical_memberships"],1)
            self.assertEqual(report["workout_input_stats"]["rejected payload garmin/activity"],2)
            with contextlib.closing(sqlite3.connect(root/"quality"/"quality_cases.db")) as q:
                self.assertEqual(q.execute("select count(*) from quality_issues where issue='invalid_json'").fetchone()[0],2)

    def test_quick_entry_journal_is_not_second_timer(self):
        from dataclasses import replace
        from materialize_canonical_workouts import quick_journal_supports_device
        watch=Session("GARMIN","GARMIN:a","STRENGTH",T,2400,2400,None,None,"2023-11-14")
        journal=Session("GYMUP","GYMUP:a","STRENGTH",T+300000,10,10,None,None,"2023-11-14",(T+301000,T+308000))
        self.assertTrue(quick_journal_supports_device(journal,watch))
        self.assertEqual(len(reconcile([watch,journal],True)),1)
        self.assertEqual(len(reconcile([watch,replace(journal,set_marks=(T+301000,T+4000000))],True)),2)
        self.assertEqual(len(reconcile([watch,replace(watch,key="GARMIN:b",start=T+60000),journal],True)),3)

    def test_set_evidence_and_competing_watch(self):
        watch=Session("GARMIN","GARMIN:a","STRENGTH",T,2400,2400,None,None,"2023-11-14")
        journal=Session("GYMUP","GYMUP:a","STRENGTH",T+300000,None,None,None,None,"2023-11-14",(T+360000,T+720000,T+1200000))
        self.assertTrue(journal_marks_support_device(journal,watch))
        self.assertEqual(len(reconcile([watch,journal],True)),1)
        from dataclasses import replace
        stale=replace(journal,set_marks=(T+360000,T+720000,T+4000000))
        self.assertEqual(len(reconcile([watch,stale],True)),2)
        competing=replace(watch,key="GARMIN:b",start=T+60000)
        self.assertEqual(len(reconcile([watch,competing,journal],True)),3)

if __name__=="__main__":
    unittest.main()
