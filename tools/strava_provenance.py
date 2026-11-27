"""Opt-in provenance policy: verified Garmin-origin Strava records are secondary evidence."""
from collections import defaultdict
from bisect import bisect_left,bisect_right
CUTOFF=1635267600000
KNOWN={"RUN","CYCLE","SWIM","WALK","STRENGTH"}
GARMIN_TYPES={"indoor_rowing":"ROW","rowing":"ROW","hiking":"HIKE","cross_country_skiing_ws":"SKI","cross_country_skiing":"SKI","elliptical":"ELLIPTICAL","breathwork":"GENERIC_WORKOUT","indoor_cardio":"GENERIC_WORKOUT","floor_climbing":"GENERIC_WORKOUT","other":"GENERIC_WORKOUT"}
STRAVA_TYPES={"гребля":"ROW","rowing":"ROW","хайкинг":"HIKE","поход":"HIKE","hike":"HIKE","hiking":"HIKE","лыжи":"SKI","nordicski":"SKI","cross country skiing":"SKI","эллиптический тренажер":"ELLIPTICAL","эллиптический тренажёр":"ELLIPTICAL","elliptical":"ELLIPTICAL","тренировка":"GENERIC_WORKOUT","workout":"GENERIC_WORKOUT"}
def subtype(r):
    table=GARMIN_TYPES if r.source=="GARMIN" else STRAVA_TYPES if r.source=="STRAVA" else {}
    return table.get((r.raw_sport or "").strip().lower()) if r.sport=="OTHER" else None
def copy_kind(g,s):
    if g.sport==s.sport and g.sport in KNOWN:return g.sport
    x,y=subtype(g),subtype(s)
    return x if x and x==y else None
def distinct_recorded_sports(a,b):
    return (subtype(a)=="ROW" and b.sport=="STRENGTH") or (subtype(b)=="ROW" and a.sport=="STRENGTH")
def compatible(g,s):
    if g.source!="GARMIN" or s.source!="STRAVA":return False
    kind=copy_kind(g,s)
    if kind is None:return False
    if abs(g.start-s.start)>2000 or not g.timer or not s.timer:return False
    if g.elapsed and s.elapsed and abs(g.elapsed-s.elapsed)>max(120,max(g.elapsed,s.elapsed)*.15):return False
    if g.elapsed and s.timer>g.elapsed+max(60,g.elapsed*.05):return False
    x,y=g.distance,s.distance
    if x and y and abs(x-y)>max(20,max(x,y)*.015):return False
    if kind=="STRENGTH":return abs(g.timer-s.timer)<=max(60,max(g.timer,s.timer)*.15)
    if kind=="GENERIC_WORKOUT":return abs(g.timer-s.timer)<=2
    if x and y:return bool(g.elapsed or s.timer<=g.timer+max(120,g.timer*.15))
    return abs(g.timer-s.timer)<=2
def link_copies(rows,cutoff=CUTOFF):
    gi=sorted((r.start,i) for i,r in enumerate(rows) if r.source=="GARMIN" and r.start>=cutoff)
    times=[t for t,_ in gi];by_s=defaultdict(list);by_g=defaultdict(list)
    for i,s in enumerate(rows):
        if s.source!="STRAVA" or s.start<cutoff or (s.sport not in KNOWN and subtype(s) is None):continue
        for _,j in gi[bisect_left(times,s.start-2000):bisect_right(times,s.start+2000)]:
            if copy_kind(rows[j],s) is None:continue
            by_s[i].append(j);by_g[j].append(i)
    return {i:js[0] for i,js in by_s.items() if len(js)==1 and len(by_g[js[0]])==1 and compatible(rows[js[0]],rows[i])}
def reconcile_with_provenance(rows,general,core,cutoff=CUTOFF):
    links=link_copies(rows,cutoff) if general else {}
    while links:
        keep=[i for i in range(len(rows)) if i not in links]
        result=core([rows[i] for i in keep],general)
        copies=defaultdict(list)
        for s,g in links.items():copies[g].append(s)
        out=[];conflicts=set()
        for p,members,fragments,external in result:
            mapped=[keep[i] for i in members];added=[s for g in mapped for s in copies.get(g,[])]
            if added and any(rows[i].source=="STRAVA" for i in mapped):conflicts.update(added)
            out.append((keep[p],sorted(mapped+added,key=lambda i:rows[i].key),[keep[i] for i in fragments],external))
        if conflicts:
            for i in conflicts:links.pop(i)
            continue
        assert sorted(i for _,members,_,_ in out for i in members)==list(range(len(rows)))
        return out
    return core(rows,general)
def actual_links(rows,episodes,cutoff=CUTOFF):
    owner={i:p for p,ids,_,_ in episodes for i in ids}
    return {i:g for i,g in link_copies(rows,cutoff).items() if owner[i]==owner[g]}
