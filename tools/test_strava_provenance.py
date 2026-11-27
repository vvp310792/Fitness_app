import unittest
from audit_all_workout_overlap import Session
from strava_provenance import CUTOFF,link_copies
def s(source,key,sport="STRENGTH",start=CUTOFF+1000,elapsed=3600,timer=3300,distance=None):
    return Session(source,source+":"+key,sport,start,elapsed,timer,distance,None,"2021-10-27")
class ProvenanceTest(unittest.TestCase):
    def test_strength_exact(self):
        self.assertEqual({1:0},link_copies([s("GARMIN","g"),s("STRAVA","s",elapsed=None,timer=3299)]))
    def test_endurance_moving_time_differs(self):
        self.assertEqual({1:0},link_copies([s("GARMIN","g","RUN",distance=10000),s("STRAVA","s","RUN",elapsed=None,timer=2400,distance=10010)]))
    def test_competing_identity(self):
        self.assertEqual({},link_copies([s("GARMIN","g"),s("STRAVA","s1",elapsed=None),s("STRAVA","s2",elapsed=None,timer=10)]))
    def test_distance_conflict(self):
        self.assertEqual({},link_copies([s("GARMIN","g","RUN",distance=10000),s("STRAVA","s","RUN",elapsed=None,distance=5000)]))
    def test_pre_cutoff(self):
        self.assertEqual({},link_copies([s("GARMIN","g",start=CUTOFF-10000),s("STRAVA","s",start=CUTOFF-10000,elapsed=None)]))
    def test_missing_garmin(self):
        self.assertEqual({},link_copies([s("STRAVA","s",elapsed=None)]))
    def test_contradictory_elapsed(self):
        self.assertEqual({},link_copies([s("GARMIN","g"),s("STRAVA","s",elapsed=600,timer=600)]))
if __name__=="__main__":unittest.main()
