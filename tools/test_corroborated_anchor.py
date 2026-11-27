"""Synthetic fixtures mirrored by Android CanonicalFitnessTest."""
import unittest
from audit_all_workout_overlap import Session
from materialize_canonical_workouts import reconcile

def s(source,key,elapsed,timer,distance):
    return Session(source,source+":"+key,"RUN",1000000,elapsed,timer,distance,None,"2026-01-01")
def count(*rows):
    rr=sorted(rows,key=lambda x:(x.start,x.key))
    groups=reconcile(rr,True)
    assert sorted(i for _,ids,_,_ in groups for i in ids)==list(range(len(rows)))
    return len(groups)
class AnchorParity(unittest.TestCase):
    def test_unique_measured_anchor(self):
        self.assertEqual(1,count(s("GARMIN","g",3600,3300,10000),s("STRAVA","s",None,3200,10000),s("HEALTH_CONNECT","h",3600,3300,10000)))
    def test_conflicting_distance(self):
        self.assertGreater(count(s("GARMIN","g",3600,3300,10000),s("STRAVA","s",None,3200,10140),s("HEALTH_CONNECT","h",3600,3300,9850)),1)
    def test_competing_identity(self):
        self.assertGreater(count(s("GARMIN","g",3600,3300,10000),s("STRAVA","s",None,3200,10000),s("STRAVA","s2",None,3200,10000),s("HEALTH_CONNECT","h",3600,3300,10000)),1)
if __name__=="__main__":unittest.main()
