import unittest
from dataclasses import replace
from audit_all_workout_overlap import Session
from strava_provenance import CUTOFF,link_copies,distinct_recorded_sports
def s(source,id,raw,timer=1800,distance=None,elapsed=1900):
    return Session(source,source+":"+id,"OTHER",CUTOFF+1000,elapsed,timer,distance,None,"2021-10-27",raw_sport=raw)
class Subtypes(unittest.TestCase):
    def test_row(self):self.assertEqual({1:0},link_copies([s("GARMIN","g","indoor_rowing"),s("STRAVA","s","Гребля",elapsed=None)]))
    def test_hike(self):self.assertEqual({1:0},link_copies([s("GARMIN","g","hiking",distance=7000),s("STRAVA","s","Хайкинг",timer=1200,distance=7010,elapsed=None)]))
    def test_generic_requires_exact_timer(self):self.assertEqual({},link_copies([s("GARMIN","g","breathwork"),s("STRAVA","s","Тренировка",timer=1200,elapsed=None)]))
    def test_distinct(self):
        a=s("GARMIN","g","indoor_rowing");b=replace(s("GYMUP","j",None),sport="STRENGTH")
        self.assertTrue(distinct_recorded_sports(a,b));self.assertEqual({},link_copies([a,s("STRAVA","s","Хайкинг",elapsed=None)]))
    def test_unknown_not_merged(self):self.assertEqual({},link_copies([s("GARMIN","g","unmapped"),s("STRAVA","s","unknown",elapsed=None)]))
if __name__=="__main__":unittest.main()
