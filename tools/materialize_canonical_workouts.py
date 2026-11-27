"""Materialize the PC's canonical workout audit index, without editing source SQLite.
Reference implementation of the Android full-copy / nested-journal rules.
This operates on the PC source DB and historical phone mirror, not live phone Room.
No individual workout data is printed or committed."""
from audit_all_workout_overlap import load, KNOWN, ENDURANCE, DIRECT, TRANSPORT
from collections import Counter, defaultdict
from pathlib import Path
import sqlite3, json, hashlib, os, time
from itertools import combinations

RANK={"GARMIN":0,"GYMUP":1,"STRAVA":2,"HEALTH_CONNECT":3,"GOOGLE_HEALTH":4,"GOOGLE_FIT":5}
DEVICE={"GARMIN","STRAVA"}
# 0 unrelated, 1 requires review, 2 confident.
def overlap(a,b):
    return max(0,min(a.end,b.end)-max(a.start,b.start)) if a.end is not None and b.end is not None else 0
def exact(a,b):
    if a.source not in {"HEALTH_CONNECT","GOOGLE_HEALTH"} and b.source not in {"HEALTH_CONNECT","GOOGLE_HEALTH"}:return False
    if a.sport!=b.sport or a.sport not in KNOWN or abs(a.start-b.start)>2000:return False
    x,y=a.distance,b.distance
    if x and y and abs(x-y)>max(10,max(x,y)*.05):return False
    if a.timer is None or b.timer is None:return False
    hc="HEALTH_CONNECT" in (a.source,b.source)
    if abs(a.timer-b.timer)>(59 if hc else 2):return False
    if hc and "GARMIN" not in (a.source,b.source) and a.elapsed and b.elapsed and abs(a.elapsed-b.elapsed)>60 and abs(a.timer-b.timer)>2:return False
    return True
def match(a,b):
    if a.source==b.source and a.channel==b.channel:
        if abs(a.start-b.start)>1000 or a.sport!=b.sport or a.sport not in KNOWN:return 0
        if not a.elapsed or not b.elapsed or abs(a.elapsed-b.elapsed)>2:return 0
        if a.sport in ENDURANCE:
            if not a.distance or not b.distance or abs(a.distance-b.distance)>max(5,max(a.distance,b.distance)*.01):return 0
        return 1
    if abs(a.start-b.start)>180000:return 0
    if a.sport in KNOWN and b.sport in KNOWN and a.sport!=b.sport:return 0
    if a.sport not in KNOWN or b.sport not in KNOWN:return 1
    if exact(a,b):return 2
    if not a.elapsed or not b.elapsed:return 1
    ov=overlap(a,b)
    if not ov:return 0
    if ov/(max(a.elapsed,b.elapsed)*1000)<.8 or abs(a.elapsed-b.elapsed)>max(120,max(a.elapsed,b.elapsed)*.12):return 1
    if a.sport in ENDURANCE:
        x,y=a.distance,b.distance
        if not x or not y or abs(x-y)>max(100,max(x,y)*.05):return 1
    return 2
def long_journal(s):
    return s.source=="GYMUP" and (s.elapsed or 0)>4*3600
def nested(child,anchor):
    journal=child.source=="GYMUP" and anchor.source in DEVICE
    if not journal and (child.source not in TRANSPORT or anchor.source not in DIRECT):return 0
    if child.sport!=anchor.sport or child.sport not in KNOWN or (journal and child.sport!="STRENGTH"):return 0
    f,a=child.elapsed,anchor.elapsed
    if journal and long_journal(child):
        delta=abs(child.start-anchor.start)
        if delta>900000:return 0
        timer=anchor.timer
        if a and timer and a>=180 and abs(a-timer)<=max(120,a*.15) and delta<=180000 and anchor.start+a*1000>child.start+60000:return 2
        return 1
    if not journal and long_journal(anchor):
        return 1 if abs(child.start-anchor.start)<=180000 else 0
    if not f or not a:return 1 if abs(child.start-anchor.start)<=180000 else 0
    ov=overlap(child,anchor)
    short,long=min(f,a),max(f,a)
    if ov<180000 or ov/(short*1000)<.5:return 0
    cs,cl=ov/(short*1000),ov/(long*1000)
    if cs<.9 and cl<.8:return 1
    if journal:return 2
    timer=anchor.timer
    if not timer or abs(a-timer)>max(120,a*.15):return 1
    x,y=child.distance,anchor.distance
    if f<a-60:
        if cs<.9 or child.start<anchor.start-60000 or child.end>anchor.end+60000 or timer<f-60:return 1
        if x and y and x>y+max(10,y*.05):return 1
        return 2
    if child.sport in ENDURANCE and (x is None or y is None):return 1
    if cl<.85 or abs(f-a)>max(120,a*.15):return 1
    if x and y and abs(x-y)>max(10,max(x,y)*.05):return 1
    return 2
def journal_fit_pair(j,f):
    if j.source!="GYMUP" or j.sport!="STRENGTH" or f.sport!="STRENGTH":return False
    if not (f.source=="GOOGLE_FIT" or (f.source=="GOOGLE_HEALTH" and f.origin=="com.google.android.apps.fitness")):return False
    if abs(j.start-f.start)>1000 or not j.elapsed or not f.elapsed or not f.timer:return False
    if min(j.elapsed,f.elapsed)<180 or abs(f.elapsed-f.timer)>max(120,f.elapsed*.15):return False
    ov=overlap(j,f)
    return ov>=180000 and ov/(min(j.elapsed,f.elapsed)*1000)>=.9
def fit_supports_device(f,w):
    if w.source not in DEVICE or w.sport!="STRENGTH" or abs(f.start-w.start)>900000:return False
    if not f.elapsed or not w.elapsed or not w.timer or abs(w.elapsed-w.timer)>max(120,w.elapsed*.15):return False
    ov=overlap(f,w)
    return ov>=180000 and ov/(min(f.elapsed,w.elapsed)*1000)>=.7

def reconcile(rows,general):
    strong=[set() for _ in rows];related=[set() for _ in rows]
    for i,a in enumerate(rows):
        for j in range(i+1,len(rows)):
            b=rows[j]
            if b.start-a.start>180000:break
            m=match(a,b)
            if not m:continue
            related[i].add(j);related[j].add(i)
            journal=(a.source=="GYMUP")!=(b.source=="GYMUP")
            if m==2 and not (general and journal):
                strong[i].add(j);strong[j].add(i)
    visited=set();groups=[]
    for i in range(len(rows)):
        if i in visited:continue
        stack=[i];visited.add(i);group=[]
        while stack:
            n=stack.pop();group.append(n)
            for j in strong[n]-visited:
                visited.add(j);stack.append(j)
        group.sort()
        safe=len({rows[i].channel for i in group})==len(group) and all(b in strong[a] for a,b in combinations(group,2))
        groups.extend([group] if safe else [[i] for i in group])
    fragments=set();attached={}
    if general:
        by_group={i:g for g,items in enumerate(groups) for i in items}
        anchors=[j for j,r in enumerate(rows) if r.source in DIRECT]
        pairs=[(j,f) for j,r in enumerate(rows) if r.source=="GYMUP" for f,x in enumerate(rows) if journal_fit_pair(r,x)]
        cj=Counter(j for j,f in pairs);cf=Counter(f for j,f in pairs); exact_journal_fit={}
        for j,f in pairs:
            if cj[j]==1 and cf[f]==1:
                exact_journal_fit[j]=f;exact_journal_fit[f]=j
                related[j].add(f);related[f].add(j)
        def root(g):return attached.get(g,g)
        def candidates(g,children,journal):
            out=defaultdict(list)
            for i in children:
                for j in anchors:
                    target=root(by_group[j])
                    if target==g or (journal and rows[j].source not in DEVICE):continue
                    n=nested(rows[i],rows[j])
                    full=not journal and not long_journal(rows[j]) and by_group[j] not in attached and match(rows[i],rows[j])==2
                    paired=exact_journal_fit.get(i)
                    corroborated=False
                    if paired is not None:
                        if journal:corroborated=fit_supports_device(rows[paired],rows[j])
                        elif rows[j].source=="GYMUP" and j==paired and by_group[j] in attached:
                            corroborated=any(fit_supports_device(rows[i],rows[k]) for k in groups[target])
                        elif rows[j].source in DEVICE and by_group[paired] in attached and root(by_group[paired])==target:
                            corroborated=fit_supports_device(rows[i],rows[j])
                    m=2 if full or corroborated else 1 if by_group[j] in attached and n else n
                    if not m:continue
                    related[i].add(j);related[j].add(i)
                    out[target].append((i,m))
            return out
        def attach(g,target,journal):
            attached[g]=target
            for i in groups[g]:
                if journal or not any(rows[j].source in DIRECT and match(rows[i],rows[j])==2 for j in groups[target]):
                    fragments.add(i)
        choices=defaultdict(list)
        for g,group in enumerate(groups):
            if not any(rows[i].source=="GYMUP" for i in group) or any(rows[i].source in DEVICE for i in group):continue
            c=candidates(g,[i for i in group if rows[i].source=="GYMUP"],True)
            if len(c)==1:
                target,ev=next(iter(c.items()))
                if all(m==2 for i,m in ev):choices[target].append(g)
        for target,gs in choices.items():
            if len(gs)==1:attach(gs[0],target,True)
        for g,group in enumerate(groups):
            if g in attached or any(rows[i].source not in TRANSPORT for i in group):continue
            c=candidates(g,group,False)
            if len(c)!=1:continue
            target,e=next(iter(c.items()))
            if not all(any(k==i and m==2 for k,m in e) for i in group):continue
            if any(root(by_group[j])!=target and by_group[j]!=g for i in group for j in strong[i]):continue
            attach(g,target,False)
        for j,f in pairs:
            if cj[j]!=1 or cf[f]!=1 or (rows[j].elapsed or 0)<=4*3600:continue
            jg,fg=by_group[j],by_group[f]
            if jg==fg or jg in attached or fg in attached or jg in attached.values() or fg in attached.values() or len(groups[jg])!=1 or len(groups[fg])!=1:continue
            for k in anchors:
                if rows[k].source in DEVICE and (nested(rows[f],rows[k]) or match(rows[f],rows[k]) or fit_supports_device(rows[f],rows[k])):
                    related[f].add(k);related[k].add(f)
            attached[jg]=fg;fragments.add(j)
        sub=defaultdict(list)
        for g,target in attached.items():sub[target].extend(groups[g])
        groups=[group+sub[g] for g,group in enumerate(groups) if g not in attached]
    result=[]
    for group in groups:
        group=sorted(group,key=lambda i:rows[i].key)
        members=set(group)
        external=sorted({rows[j].key for i in group for j in related[i] if j not in members})
        full=[i for i in group if i not in fragments]
        assert full
        primary=min(full,key=lambda i:(RANK.get(rows[i].source,6),rows[i].key))
        result.append((primary,group,[i for i in group if i in fragments],external))
    assert sorted(i for _,g,_,_ in result for i in g)==list(range(len(rows)))
    return result
def report(rows,groups):
    return dict(
        source_observations=len(rows),
        canonical_episodes=len(groups),
        absorbed_observations=len(rows)-len(groups),
        nested_observations=sum(len(f) for _,_,f,_ in groups),
        confirmed_episodes=sum(not ext for _,_,_,ext in groups),
        review_episodes=sum(bool(ext) for _,_,_,ext in groups),
        nested_by_source=dict(Counter(rows[i].source for _,_,f,_ in groups for i in f)),
        merged_group_sizes=dict(sorted(Counter(len(g) for _,g,_,_ in groups if len(g)>1).items())),
        review_by_primary=dict(Counter(rows[p].source for p,_,_,ext in groups if ext))
    )
def materialize(rows,groups,stats,baseline):
    path=Path.home()/"fitness_data"/"canonical_workouts_pc.db"
    tmp=path.with_name(path.name+".staging."+str(os.getpid()))
    assert not tmp.exists()
    digest=hashlib.sha256()
    for r in rows:digest.update(json.dumps(vars(r),sort_keys=True,separators=(",",":")).encode())
    c=sqlite3.connect(tmp)
    try:
        c.execute("PRAGMA foreign_keys=ON")
        c.executescript("""
        CREATE TABLE canonical_workouts (
          episode_key TEXT PRIMARY KEY, day TEXT NOT NULL, start_ms INTEGER NOT NULL,
          sport TEXT NOT NULL, primary_source TEXT NOT NULL, timer_seconds INTEGER,
          elapsed_seconds INTEGER, distance_meters REAL, needs_review INTEGER NOT NULL);
        CREATE TABLE workout_members (
          observation_key TEXT PRIMARY KEY, episode_key TEXT NOT NULL REFERENCES canonical_workouts(episode_key),
          source TEXT NOT NULL, day TEXT NOT NULL, start_ms INTEGER NOT NULL,
          elapsed_seconds INTEGER, timer_seconds INTEGER, distance_meters REAL,
          role TEXT NOT NULL CHECK(role IN ('primary','full_copy','nested')));
        CREATE INDEX ix_workout_members_episode ON workout_members(episode_key);
        CREATE TABLE possible_matches (episode_key TEXT NOT NULL REFERENCES canonical_workouts(episode_key),
          observation_key TEXT NOT NULL REFERENCES workout_members(observation_key),
          PRIMARY KEY(episode_key,observation_key));
        CREATE TABLE metadata (key TEXT PRIMARY KEY,value TEXT NOT NULL);
        """)
        with c:
            for p,items,nested_ids,ext in groups:
                r=rows[p]
                full=sorted((rows[i] for i in items if i not in nested_ids),key=lambda r:(RANK.get(r.source,6),r.key))
                long_journal=any(rows[i].source=="GYMUP" and (rows[i].elapsed or 0)>4*3600 for i in items)
                def usable_timer(v):
                    if v.timer is None:return False
                    if v.source=="GYMUP" and v.timer>4*3600:return False
                    if long_journal and v.key==r.key and (v.source=="GOOGLE_FIT" or (v.source=="GOOGLE_HEALTH" and v.origin=="com.google.android.apps.fitness")) and v.timer==3600:return False
                    return True
                timer=next((v.timer for v in full if usable_timer(v)),None)
                dist=next((v.distance for v in full if v.distance is not None),None)
                c.execute("INSERT INTO canonical_workouts VALUES (?,?,?,?,?,?,?,?,?)",(r.key,r.day,r.start,r.sport,r.source,timer,r.elapsed,dist,int(bool(ext))))
                c.executemany("INSERT INTO workout_members VALUES (?,?,?,?,?,?,?,?,?)",[
                    (rows[i].key,r.key,rows[i].source,rows[i].day,rows[i].start,rows[i].elapsed,rows[i].timer,rows[i].distance,
                     "primary" if i==p else "nested" if i in nested_ids else "full_copy") for i in items])
            for p,items,nested_ids,ext in groups:
                c.executemany("INSERT INTO possible_matches VALUES (?,?)",[(rows[p].key,key) for key in ext])
            meta={"input_sha256":digest.hexdigest(),"generated_epoch":str(int(time.time())),
                  "algorithm":"overlap-reconciliation-reference-v5","input_stats":json.dumps(dict(stats)),
                  "baseline":json.dumps(baseline),"result":json.dumps(report(rows,groups))}
            c.executemany("INSERT INTO metadata VALUES (?,?)",meta.items())
        assert c.execute("PRAGMA integrity_check").fetchone()==("ok",)
        assert c.execute("PRAGMA foreign_key_check").fetchall()==[]
        assert c.execute("SELECT COUNT(*) FROM workout_members").fetchone()[0]==len(rows)
        assert c.execute("SELECT COUNT(*) FROM canonical_workouts").fetchone()[0]==len(groups)
        assert c.execute("SELECT COUNT(*) FROM workout_members WHERE role='primary'").fetchone()[0]==len(groups)
    finally:c.close()
    # Separate derived DB only; never replace independent.db or any raw source.
    os.replace(tmp,path)
    check=sqlite3.connect(path.resolve().as_uri()+"?mode=ro",uri=True)
    print("INDEX_READBACK",json.dumps(dict(path=str(path),episodes=check.execute("SELECT COUNT(*) FROM canonical_workouts").fetchone()[0],
          members=check.execute("SELECT COUNT(*) FROM workout_members").fetchone()[0],integrity=check.execute("PRAGMA integrity_check").fetchone()[0])))
    check.close()
if __name__=="__main__":
    rows,stats=load()
    before=report(rows,reconcile(rows,False))
    groups=reconcile(rows,True)
    after=report(rows,groups)
    print("BEFORE_BUILD81_REFERENCE",json.dumps(before,ensure_ascii=True))
    print("AFTER_GENERAL_REFERENCE",json.dumps(after,ensure_ascii=True))
    materialize(rows,groups,stats,before)
