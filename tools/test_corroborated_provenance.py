import unittest
from dataclasses import replace
from audit_all_workout_overlap import Session
from strava_provenance import CUTOFF,link_copies
T=CUTOFF+100000
def row(source,key,sport="STRENGTH",start=T,elapsed=2400,timer=1000,distance=None,raw=None,cal=200,avg=100,mx=150):
    return Session(source,source+":"+key,sport,start,elapsed,timer,distance,None,"2021-10-27",raw_sport=raw,copy_calories=cal,copy_avg_hr=avg,copy_max_hr=mx)
class CorroborationTest(unittest.TestCase):
    def pair(self):
        return [row("GARMIN","g"),row("STRAVA","s",elapsed=None,timer=2401)]
    def test_elapsed_not_timer(self):
        self.assertEqual(link_copies(self.pair()),{1:0})
    def test_swim_distance_and_calories(self):
        g=row("GARMIN","g","SWIM",timer=2300,distance=800,mx=None)
        s=row("STRAVA","s","SWIM",start=T+9000,elapsed=None,timer=2291,distance=800,mx=None)
        self.assertEqual(link_copies([g,s]),{1:0})
        self.assertEqual(link_copies([g,replace(s,copy_calories=201)]),{})
    def test_generic_export(self):
        g=row("GARMIN","g","RUN",raw="trail_running",distance=11000,timer=2300)
        s=row("STRAVA","s","OTHER",raw="workout",elapsed=None,timer=2100,distance=10998)
        self.assertEqual(link_copies([g,s]),{1:0})
        self.assertEqual(link_copies([g,replace(s,copy_avg_hr=110)]),{})
    def test_conflicts_and_absent_evidence(self):
        g,s=self.pair()
        for bad in [replace(s,copy_max_hr=160),replace(s,copy_calories=None),replace(s,timer=3600),replace(s,elapsed=600),replace(s,sport="RUN")]:
            self.assertEqual(link_copies([g,bad]),{})
        self.assertEqual(link_copies([replace(g,elapsed=None),s]),{})
        self.assertEqual(link_copies([g,s,replace(s,key="STRAVA:s2",start=T+5000,sport="RUN")]),{})
        self.assertEqual(link_copies([g,s,replace(g,key="GARMIN:g2",start=T+5000,sport="RUN")]),{})
    def test_cutoff_and_short_noise(self):
        g,s=self.pair()
        self.assertEqual(link_copies([replace(g,start=CUTOFF-1),replace(s,start=CUTOFF-1)]),{})
        self.assertEqual(link_copies([replace(g,elapsed=20,timer=1),replace(s,timer=20,start=T+9000)]),{})
if __name__=="__main__":unittest.main()
