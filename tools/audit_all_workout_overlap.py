"""Read-only full workout-source audit. Prints aggregate counts only."""
import sqlite3, json, pathlib, datetime as dt, collections, math
from dataclasses import dataclass, replace
from itertools import combinations
from pathlib import Path

DB=Path.home()/"fitness_data"/"independent.db"
KNOWN={"RUN","SWIM","CYCLE","WALK","STRENGTH"}
ENDURANCE=KNOWN-{"STRENGTH"}
DIRECT={"GARMIN","GYMUP","STRAVA"}
TRANSPORT={"GOOGLE_HEALTH","HEALTH_CONNECT","GOOGLE_FIT"}
FIT="com.google.android.apps.fitness"
GARMIN="com.garmin.android.apps.connectmobile"
@dataclass(frozen=True)
class Session:
    source:str
    key:str
    sport:str
    start:int
    elapsed:int|None
    timer:int|None
    distance:float|None
    origin:str|None
    day:str
    set_marks:tuple[int,...]=()
    @property
    def channel(self):
        return self.source+(":"+self.origin if self.source=="GOOGLE_HEALTH" and self.origin else "")
    @property
    def end(self):
        return self.start+self.elapsed*1000 if self.elapsed else None
def num(x):
    try:
        n=float(x)
        return n if math.isfinite(n) and n>0 else None
    except (TypeError,ValueError):return None
def dur(x):
    v=num(x)
    return int(v) if v and 1<=int(v)<=604800 else None
def ts(v):
    try:
        t=dt.datetime.fromisoformat(v.replace("Z","+00:00").replace(" ","T"))
        return int((t.replace(tzinfo=dt.timezone.utc) if t.tzinfo is None else t).timestamp()*1000)
    except:return None
def garmin_sport(k):
    k=k.lower()
    if "motor" not in k:
        if "swim" in k:return "SWIM"
        if any(v in k for v in ("cycl","bik","ride")):return "CYCLE"
        if "run" in k:return "RUN"
    if k in ("strength_training","weightlifting"):return "STRENGTH"
    if "walking" in k:return "WALK"
    return "OTHER" if k else "UNKNOWN"
def hc_sport(k):
    return {56:"RUN",57:"RUN",8:"CYCLE",9:"CYCLE",73:"SWIM",74:"SWIM",70:"STRENGTH",81:"STRENGTH",79:"WALK",0:"UNKNOWN"}.get(k,"OTHER")
def gh_sport(k):
    return {"RUNNING":"RUN","SWIMMING":"SWIM","POOL_SWIMMING":"SWIM","OPEN_WATER_SWIMMING":"SWIM","WALKING":"WALK","BIKING":"CYCLE","CYCLING":"CYCLE","STRENGTH_TRAINING":"STRENGTH","WEIGHTLIFTING":"STRENGTH","":"UNKNOWN"}.get(k,"OTHER")
def load(connection=None):
    own=connection is None
    c=connection if connection is not None else sqlite3.connect(DB.resolve().as_uri()+"?mode=ro",uri=True)
    if own:c.execute("BEGIN")
    rows={}; stats=collections.Counter()
    def add(source,key,sport,start,elapsed,timer,distance,origin,day,set_marks=()):
        if not key or not start or start<=0:
            stats["invalid "+source]+=1;return
        key=source+":"+str(key)
        s=Session(source,key,sport,int(start),dur(elapsed),dur(timer),num(distance),origin,day,set_marks)
        stats["input "+source]+=1
        if key in rows:
            a=rows[key]
            if abs(a.start-s.start)>180000 or (a.sport in KNOWN and s.sport in KNOWN and a.sport!=s.sport):
                stats["identity conflicts "+source]+=1;return
            stats["same ID "+source]+=1
            s=replace(a,elapsed=s.elapsed or a.elapsed,timer=a.timer or s.timer,distance=a.distance or s.distance,origin=a.origin or s.origin,set_marks=s.set_marks or a.set_marks)
        rows[key]=s
    def read(source,kind):
        for key,day,payload in c.execute("select native_id,day,payload from records where source=? and kind=?",(source,kind)):
            try:
                value=json.loads(payload)
                if not isinstance(value,dict):raise ValueError("non-object workout")
            except (TypeError,ValueError):
                stats["rejected payload "+source+"/"+kind]+=1
                continue
            yield key,day,value
    for key,day,p in read("legacy_app","workouts"):
        r=p;a=r.get("startTimeMillis");b=r.get("endTimeMillis")
        add("HEALTH_CONNECT",r.get("recordId") or key,hc_sport(r.get("exerciseType")),a,(b-a)/1000 if a and b and b>a else None,(r.get("durationMinutes") or 0)*60,r.get("distanceMeters"),None,day)
    for key,day,p in read("legacy_app","strength_sets"):
        r=p;a=r.get("startMillis")
        if a and "GYMUP:"+str(a) not in rows:add("GYMUP",a,"STRENGTH",a,None,None,None,None,day)
    for key,day,p in read("garmin","activity"):
        r=p
        add("GARMIN",key,garmin_sport((r.get("activityType") or {}).get("typeKey") or ""),ts(r.get("startTimeGMT")) or r.get("beginTimestamp"),r.get("elapsedDuration"),r.get("duration"),r.get("distance"),None,day)
    for key,day,p in read("gymup","training"):
        r=p;t=r.get("training") or {};a=t.get("startDateTime");b=t.get("finishDateTime")
        performed=[x for w in (r.get("workouts") or []) for x in (w.get("sets") or []) if (x.get("reps") or 0)>0 and isinstance(x.get("weight"),(float,int)) and x["weight"]>=0]
        if not performed:
            stats["empty GymUp plans"]+=1;continue
        d=(b-a)/1000 if a and b and b>a else None
        marks=[x.get("finishDateTime") for x in performed]
        marks=tuple(sorted(set(int(m) for m in marks))) if all(isinstance(m,(int,float)) and math.isfinite(m) and m>=100000000000 for m in marks) else ()
        add("GYMUP",key,"STRENGTH",a,d,d,None,None,day,marks)
    for key,day,p in read("google_health","exercise"):
        r=p;e=r.get("exercise") or {};iv=e.get("interval") or {};a=ts(iv.get("startTime"));b=ts(iv.get("endTime"))
        m=e.get("metricsSummary") or {};d=num(m.get("distanceMillimeters"))
        add("GOOGLE_HEALTH",key,gh_sport(e.get("exerciseType") or ""),a,(b-a)/1000 if a and b and b>a else None,str(e.get("activeDuration") or "").removesuffix("s"),d/1000 if d else None,((r.get("dataSource") or {}).get("application") or {}).get("packageName"),day)
    if own:c.close()
    return sorted(rows.values(),key=lambda x:(x.start,x.key)),stats

def describe_pair(a,b):
    if a.sport!=b.sport or a.sport not in KNOWN or a.channel==b.channel:return None
    diff=abs(a.start-b.start)/1000
    if a.end is None or b.end is None:
        return "missing interval, near start" if diff<=180 else None
    ov=max(0,min(a.end,b.end)-max(a.start,b.start))/1000
    if ov<=0:return None
    short,long=sorted((a.elapsed,b.elapsed))
    if diff<=2 and a.timer and b.timer and abs(a.timer-b.timer)<=(59 if "HEALTH_CONNECT" in (a.source,b.source) else 2):
        return "same start/timer"
    if ov/long>=.8:return ">=80% both intervals"
    if ov/short>=.9:return ">=90% shorter interval"
    if ov/short>=.5:return "50-90% shorter interval"
    return "small overlap"

def main():
    rows,stats=load()
    by_source=collections.Counter(r.source for r in rows)
    coverage=collections.Counter((r.source, bool(r.elapsed)) for r in rows)
    pairs=collections.Counter();multi=collections.Counter()
    for i,a in enumerate(rows):
        for b in rows[i+1:]:
            if b.start>a.start+max((a.elapsed or 0)*1000,180000):break
            k=describe_pair(a,b)
            if k:
                sources="+".join(sorted([a.source,b.source]))
                pairs[(sources,k)]+=1
                if k=="2":multi[(sources,k)]+=1
    print("INPUT",json.dumps(dict(stats),ensure_ascii=True))
    print("UNIQUE",json.dumps(dict(by_source)))
    print("INTERVALS",json.dumps([(*k,v) for k,v in coverage.items()]))
    print("OVERLAP_MATRIX",json.dumps([(s,k,v) for (s,k),v in sorted(pairs.items())]))
if __name__=="__main__":main()
