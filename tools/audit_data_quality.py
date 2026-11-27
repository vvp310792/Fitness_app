"""Read-only QC of independent source DB and reference canonical archive.
Keep all case identifiers and measurements in a private derived report directory.
No source DB or Firestore writes. Rerun after sync to refresh reports."""
import collections as C
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import sqlite3
from audit_all_workout_overlap import DB,load,KNOWN,ENDURANCE,num,ts
from materialize_canonical_workouts import reconcile,report,match,nested,overlap,journal_fit_pair,long_journal

OUT=DB.parent/"data_quality"
TODAY=(dt.datetime.now(dt.timezone.utc)+dt.timedelta(hours=7)).date()
def date(v):
    try:return dt.date.fromisoformat(v)
    except (TypeError,ValueError):return None
def number(x):
    try:
        n=float(x)
        return n if math.isfinite(n) else None
    except (TypeError,ValueError):return None
def get(d,*path):
    for k in path:
        if not isinstance(d,dict):return None
        d=d.get(k)
    return d
def utc(v):
    n=ts(v)
    return n if n is not None else None
def write_json(path,obj):
    tmp=path.with_suffix(path.suffix+".tmp")
    tmp.write_text(json.dumps(obj,ensure_ascii=False,indent=2,sort_keys=True),encoding="utf-8")
    os.replace(tmp,path)
def main():
    OUT.mkdir(parents=True,exist_ok=True)
    now=dt.datetime.now(dt.timezone.utc).isoformat()
    c=sqlite3.connect(DB.resolve().as_uri()+"?mode=ro",uri=True)
    c.execute("BEGIN")
    integrity=c.execute("PRAGMA integrity_check").fetchone()[0]
    fk=len(c.execute("PRAGMA foreign_key_check").fetchall())
    categories=C.defaultdict(lambda:{"count":0,"days":set(),"hash_mismatch":0,"invalid_json":0,"invalid_date":0,"future_date":0})
    issue_counts=C.Counter()
    coverage=C.defaultdict(set)
    issues=[]
    def issue(src,k,key,day,kind,value=None):
        issue_counts[src+"/"+k+"/"+kind]+=1
        issues.append((src,k,key,day,kind,json.dumps(value,ensure_ascii=False)))
    def metric(src,k,key,day,label,x,lo,hi,zero_missing=False):
        v=number(x)
        if v is None or (zero_missing and v==0):
            issue_counts[src+"/"+k+"/missing_"+label]+=1
            return False
        if v<lo or v>hi:
            issue(src,k,key,day,"range_"+label,v)
            return False
        return True
    gh_fields={"steps":("steps","count",0,200000),
        "distance":("distance","millimeters",0,1e9),
        "heart-rate":("heartRate","beatsPerMinute",20,260),
        "weight":("weight","weightGrams",20000,350000)}
    gh_interval= C.defaultdict(list)
    duplicate_payloads=C.Counter()
    gym_stats=C.Counter()
    weights=C.defaultdict(set)
    for src,k,key,day,p,sha in c.execute("select source,kind,native_id,day,payload,sha from records order by source,kind,native_id"):
        cat=categories[(src,k)];cat["count"]+=1;day_obj=date(day)
        if day_obj is None:
            cat["invalid_date"]+=1;issue(src,k,key,day,"invalid_date")
        else:
            cat["days"].add(day_obj)
            if day_obj>TODAY+dt.timedelta(days=1):
                cat["future_date"]+=1;issue(src,k,key,day,"future_date")
        if hashlib.sha256(p.encode("utf-8")).hexdigest()!=sha:
            cat["hash_mismatch"]+=1;issue(src,k,key,day,"payload_hash_mismatch")
        try:
            r=json.loads(p)
            # Garmin series/history legitimately use arrays. Only object-shaped
            # source kinds that are inspected below require a JSON object.
            object_kind=(src=="garmin" and k in {"daily","sleep_summary","sleep_detail","activity","weight","body_composition"}) or (src=="gymup" and k=="training") or src=="google_health" or (src in {"zepp","zepp_archive"} and k=="weight") or (src=="legacy_app" and k in {"scale_measurements","workouts","strength_sets"})
            if object_kind and not isinstance(r,dict):raise ValueError("non-object payload")
        except (TypeError,ValueError):
            cat["invalid_json"]+=1;issue(src,k,key,day,"invalid_json");continue
        duplicate_payloads[(src,k,sha)]+=1
        if src=="garmin" and k=="daily":
            for name,val,lo,hi,z in [
                ("steps",get(r,"movement","steps","value"),0,200000,False),
                ("distance_m",get(r,"movement","steps","distanceInMeters"),0,1000000,False),
                ("resting_hr",get(r,"heartRate","restingValue"),20,220,True),
                ("total_kcal",get(r,"calories","burnedTotal"),0,20000,True),
                ("stress",get(r,"stress","avgLevel"),0,100,False)]:
                if val==-1 and name=="stress":val=None
                if metric(src,k,key,day,name,val,lo,hi,z) and day_obj:coverage["garmin_daily/"+name].add(day_obj)
        elif src=="garmin" and k in ("sleep_summary","sleep_detail"):
            v=get(r,"values","totalSleepTimeInSeconds") if k=="sleep_summary" else get(r,"dailySleepDTO","sleepTimeSeconds")
            if metric(src,k,key,day,"sleep_seconds",v,1,86400,True) and day_obj:
                coverage["garmin_sleep/valid"].add(day_obj)
        elif src=="garmin" and k=="activity":
            elapsed=number(r.get("elapsedDuration"));timer=number(r.get("duration"))
            if not metric(src,k,key,day,"timer_s",timer,1,604800,True):pass
            if not metric(src,k,key,day,"elapsed_s",elapsed,1,604800,True):pass
            if elapsed is not None and timer is not None and timer>elapsed+2:issue(src,k,key,day,"timer_exceeds_elapsed",{"timer":timer,"elapsed":elapsed})
            a=utc(r.get("startTimeGMT"));b=utc(r.get("endTimeGMT"))
            if a and b and b<a:issue(src,k,key,day,"reversed_interval")
            for m in ("averageHR","maxHR"):
                if r.get(m) is not None:metric(src,k,key,day,m,r.get(m),20,260,True)
        elif src=="gymup" and k=="training":
            t=r.get("training") or {};a=number(t.get("startDateTime"));b=number(t.get("finishDateTime"))
            sets=[x for w in r.get("workouts",[]) for x in w.get("sets",[]) if number(x.get("reps")) is not None and number(x.get("reps"))>0]
            if not sets:gym_stats["empty_or_planned"]+=1
            else:
                gym_stats["performed"]+=1
                if not a or not b or b<=a:
                    gym_stats["missing_or_invalid_finish"]+=1;issue(src,k,key,day,"performed_without_valid_finish")
                elif b-a>4*3600*1000:
                    gym_stats["wall_clock_over_4h"]+=1;issue(src,k,key,day,"journal_open_over_4h",round((b-a)/3600000,2))
                if a and b and b>a and b-a>24*3600*1000:gym_stats["wall_clock_over_24h"]+=1
            for x in sets:
                w=number(x.get("weight"));rep=number(x.get("reps"))
                if w is None or w<0 or w>1000 or rep>300:issue(src,k,key,day,"set_value_review",{"weight":w,"reps":rep})
        elif src=="google_health":
            if k in gh_fields:
                top,field,lo,hi=gh_fields[k];v=get(r,top,field)
                if metric(src,k,key,day,field,v,lo,hi) and day_obj:coverage["google_health/"+k].add(day_obj)
                if k=="weight" and number(v) is not None and 20000<=number(v)<=350000 and day_obj:weights[src].add(day_obj)
            top={"steps":"steps","distance":"distance","exercise":"exercise","sleep":"sleep","active-energy-burned":"activeEnergyBurned","total-calories":"totalCalories"}.get(k)
            if top:
                iv=get(r,top,"interval")
                if iv:
                    a=utc(iv.get("startTime"));b=utc(iv.get("endTime"))
                    if a is None or b is None or b<=a:
                        issue(src,k,key,day,"invalid_interval")
                    else:
                        origin=get(r,"dataSource","application","packageName") or "unknown"
                        gh_interval[(k,origin)].append((a,b,key,day))
        elif src=="garmin" and k in ("weight","body_composition"):
            samples=r.get("allWeightMetrics",[]) if k=="weight" else [r]
            for sample in samples:
                w=sample.get("weight")
                if metric(src,k,key,day,"weight_g",w,20000,350000,True) and day_obj:weights[src].add(day_obj)
                for field in ("bodyFat","bodyWater"):
                    if sample.get(field) is not None:metric(src,k,key,day,field,sample[field],0,100,True)
        elif src in ("zepp","zepp_archive") and k=="weight":
            w=get(r,"summary","weight")
            if metric(src,k,key,day,"weight_kg",w,20,350,True) and day_obj:weights[src].add(day_obj)
        elif src=="legacy_app" and k=="scale_measurements":
            if metric(src,k,key,day,"weight_g",r.get("weightGrams"),20000,350000,True) and day_obj:weights[src].add(day_obj)
    source_counts=C.Counter()
    group_summary=[]
    for (src,k),v in sorted(categories.items()):
        source_counts[src]+=v["count"];days=v.pop("days")
        group_summary.append(dict(source=src,kind=k,**v,first=str(min(days)) if days else None,last=str(max(days)) if days else None,days=len(days)))
    states=[]
    for src,checked,status,detail in c.execute("select source,checked_at,status,detail from sync_state order by source"):
        # Preserve statuses without credentials/opaque detail.
        states.append(dict(source=src,checked_at=checked,status=status))
    rows,input_stats=load(c)
    c.close()
    # Intra-stream interval collisions are potential double-counting candidates,
    # not proof of duplicate observations. The report keeps them separate from
    # workout canonical membership.
    interval_collisions=C.Counter()
    for (k,origin),items in gh_interval.items():
        items.sort()
        seen=[]
        for a,b,key,day in items:
            seen=[x for x in seen if x[1]>a]
            for x,y,other,_ in seen:
                interval_collisions[k+":"+("same_bounds" if (a,b)==(x,y) else "overlap")]+=1
                if k in ("steps","distance","active-energy-burned","total-calories"):
                    issue("google_health",k,key,day,"interval_collision_"+("exact" if (a,b)==(x,y) else "overlap"),{"other_key":other,"origin":origin})
            seen.append((a,b,key,day))
    episodes=reconcile(rows,True)
    owner={i:g for g,(_,members,_,_) in enumerate(episodes) for i in members}
    membership=[i for _,members,_,_ in episodes for i in members]
    assert len(membership)==len(rows) and sorted(membership)==list(range(len(rows))), "canonical membership mismatch"
    assert all(p in members and p not in nested_ids and set(nested_ids)<=set(members)
        for p,members,nested_ids,_ in episodes), "invalid primary or nested membership"
    fragments={i for _,_,members,_ in episodes for i in members}
    pair_cases=[]
    pair_counts=C.Counter()
    source_pairs=C.Counter()
    unresolved_episodes=set()
    for i,a in enumerate(rows):
        # Full interval scan; no arbitrary 3-minute truncation when elapsed is known.
        for j in range(i+1,len(rows)):
            b=rows[j]
            if b.start>a.start+max((a.elapsed or 0)*1000,180000):break
            ov=overlap(a,b)
            near=abs(a.start-b.start)<=180000
            if ov<=0 and not (near and (a.elapsed is None or b.elapsed is None)):continue
            same=owner[i]==owner[j]
            short=min(a.elapsed,b.elapsed) if a.elapsed and b.elapsed else None
            long=max(a.elapsed,b.elapsed) if a.elapsed and b.elapsed else None
            frac_short=ov/(short*1000) if short else None
            frac_long=ov/(long*1000) if long else None
            if same:
                status="resolved_nested" if i in fragments or j in fragments else "resolved_full_copy"
                reason="canonical_membership"
            elif a.sport in KNOWN and b.sport in KNOWN and a.sport!=b.sport:
                status="different_sport";reason="different_known_sport"
            elif (long_journal(a) or long_journal(b)) and abs(a.start-b.start)>900000:
                status="overlap_only";reason="unreliable_journal_wall_clock_tail"
            elif a.sport not in KNOWN or b.sport not in KNOWN:
                status="review";reason="unknown_sport"
            elif a.elapsed is None or b.elapsed is None:
                status="review";reason="missing_interval"
            elif a.channel==b.channel:
                # Same-stream overlaps can be distinct workouts. Only flag
                # high-coverage or exactly timed collisions for review.
                status="review" if frac_long>=.8 or near else "overlap_only"
                reason="same_stream_different_ids"
            elif frac_short<.5:
                status="overlap_only";reason="low_overlap"
            else:
                status="review"
                if journal_fit_pair(a,b) or journal_fit_pair(b,a):reason="exact_journal_fit_start_unresolved"
                elif max(match(a,b),nested(a,b),nested(b,a))==2:reason="competing_or_inconsistent_match"
                elif a.sport in ENDURANCE and a.distance and b.distance and abs(a.distance-b.distance)>max(100,max(a.distance,b.distance)*.05):
                    reason="distance_disagreement"
                elif frac_short<.9:reason="partial_overlap"
                else:reason="nested_or_duration_disagreement"
            pair_counts[status]+=1;source_pairs[(status,"+".join(sorted((a.source,b.source))))]+=1
            if status=="review":unresolved_episodes.update((owner[i],owner[j]))
            case_id=hashlib.sha256(json.dumps(sorted((a.key,b.key)),separators=(",",":")).encode()).hexdigest()
            pair_cases.append((case_id,a.key,b.key,a.day,a.source,b.source,a.sport,b.sport,a.start,b.start,a.elapsed,b.elapsed,
                round(ov/1000,3),frac_short,frac_long,episodes[owner[i]][0],episodes[owner[j]][0],status,reason))
    # Account for every review episode in a connected case group, without
    # declaring a chain of possible matches one actual workout.
    group_for_primary={p:g for g,(p,_,_,_) in enumerate(episodes)}
    review_links=C.defaultdict(set)
    for p in pair_cases:
        if p[-2]!="review":continue
        g,h=group_for_primary[p[15]],group_for_primary[p[16]]
        review_links[g].add(h);review_links[h].add(g)
    seen_groups=set()
    review_groups=[]
    for g in sorted(unresolved_episodes):
        if g in seen_groups:continue
        todo=[g];seen_groups.add(g);members=[]
        while todo:
            n=todo.pop();members.append(n)
            for h in review_links[n]-seen_groups:
                seen_groups.add(h);todo.append(h)
        review_groups.append(members)
    assert seen_groups==unresolved_episodes
    assert sum(pair_counts.values())==len(pair_cases)
    # Store case registry in a derived private database, with atomic replacement.
    # Membership and source rows are never changed here.
    path=OUT/"quality_cases.db";tmp=path.with_name(path.name+".staging."+str(os.getpid()))
    assert not tmp.exists()
    q=sqlite3.connect(tmp)
    try:
        q.executescript("""
        CREATE TABLE overlap_cases(case_id TEXT PRIMARY KEY, key_a TEXT,key_b TEXT,day_a TEXT,source_a TEXT,source_b TEXT,
          sport_a TEXT,sport_b TEXT,start_a INTEGER,start_b INTEGER,elapsed_a INTEGER,elapsed_b INTEGER,overlap_seconds REAL,
          fraction_short REAL,fraction_long REAL,canonical_a TEXT,canonical_b TEXT,status TEXT,reason TEXT);
        CREATE INDEX ix_overlap_status ON overlap_cases(status,reason);
        CREATE TABLE quality_issues(source TEXT,kind TEXT,native_id TEXT,day TEXT,issue TEXT,details_json TEXT);
        CREATE INDEX ix_quality_issue ON quality_issues(source,kind,issue);
        """)
        with q:
            data=[(*p[:15],rows[p[15]].key,rows[p[16]].key,*p[17:]) for p in pair_cases]
            q.executemany("INSERT INTO overlap_cases VALUES ("+",".join(["?"]*19)+")",data)
            q.executemany("INSERT INTO quality_issues VALUES (?,?,?,?,?,?)",issues)
        assert q.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
        assert q.execute("select count(*) from overlap_cases").fetchone()[0]==len(pair_cases)
        assert q.execute("select count(*) from quality_issues").fetchone()[0]==len(issues)
    finally:q.close()
    os.replace(tmp,path)
    def window(days,n):
        start=TODAY-dt.timedelta(days=n)
        return len({d for d in days if start<=d<TODAY})
    windows={k:{str(n):window(v,n) for n in (30,90,365)} for k,v in sorted(coverage.items())}
    weight_summary={k:{"days":len(v),"first":str(min(v)) if v else None,"last":str(max(v)) if v else None,
        "last30":window(v,30)} for k,v in weights.items()}
    allweights=set().union(*weights.values()) if weights else set()
    result={"generated_at":now,"day_nsk":str(TODAY),"scope":"PC independent SQLite + historical phone mirror; not live phone",
        "sqlite":{"integrity":integrity,"foreign_key_issues":fk,"records":sum(source_counts.values()),
            "hash_mismatches":sum(g["hash_mismatch"] for g in group_summary),
            "invalid_json":sum(g["invalid_json"] for g in group_summary),
            "invalid_date":sum(g["invalid_date"] for g in group_summary),"future_date":sum(g["future_date"] for g in group_summary)},
        "workout_input_stats":dict(input_stats),
        "source_counts":dict(source_counts),"groups":group_summary,"sync_states":states,
        "coverage_completed_days":windows,"weight_coverage":weight_summary,
        "combined_weight_days":len(allweights),"combined_weight_days_last30":window(allweights,30),
        "gymup_quality":dict(gym_stats),"metric_flags":dict(issue_counts),
        "same_payload_repeats":sum(n-1 for n in duplicate_payloads.values() if n>1),
        "google_health_interval_collisions":dict(interval_collisions),"canonical_reference":report(rows,episodes),
        "accounting":{"unique_source_observations":len(rows),"canonical_memberships":len(membership),
            "duplicate_memberships":len(membership)-len(set(membership)),
            "unassigned_observations":len(rows)-len(owner),
            "canonical_review_episode_count":sum(bool(ext) for _,_,_,ext in episodes)},
        "overlap_registry":{"cases":len(pair_cases),"by_status":dict(pair_counts),
            "review_episodes":len(unresolved_episodes),"review_groups":len(review_groups),
            "review_group_sizes":dict(C.Counter(len(g) for g in review_groups)),
            "by_source_pair":[[s,p,n] for (s,p),n in sorted(source_pairs.items())],
            "review_reasons":dict(C.Counter(p[-1] for p in pair_cases if p[-2]=="review")),
            "path":str(path),"issues":len(issues)}}
    # Keep a stable history keyed by source-pair identity. A missing case is
    # explicitly not_seen, never silently marked resolved. Only transitions
    # and per-run aggregate summaries are appended, not source payloads.
    h=sqlite3.connect(OUT/"quality_history.db")
    try:
        h.executescript("""
        CREATE TABLE IF NOT EXISTS runs(run_at TEXT PRIMARY KEY,summary_json TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS case_state(case_id TEXT PRIMARY KEY,first_seen TEXT,last_seen TEXT,last_status TEXT NOT NULL,last_reason TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS case_transitions(run_at TEXT,case_id TEXT,status TEXT,reason TEXT,PRIMARY KEY(run_at,case_id));
        """)
        with h:
            previous={k:(status,reason) for k,status,reason in h.execute("SELECT case_id,last_status,last_reason FROM case_state")}
            current={p[0]:(p[-2],p[-1]) for p in pair_cases}
            transitions=C.Counter()
            for key,(status,reason) in current.items():
                old=previous.get(key)
                if old!=(status,reason):
                    h.execute("INSERT INTO case_transitions VALUES (?,?,?,?)",(now,key,status,reason))
                    transitions["new_case" if old is None else "changed_status_or_reason"]+=1
                h.execute("INSERT INTO case_state VALUES (?,?,?,?,?) ON CONFLICT(case_id) DO UPDATE SET last_seen=excluded.last_seen,last_status=excluded.last_status,last_reason=excluded.last_reason",(key,now,now,status,reason))
            for key,(status,reason) in previous.items():
                if key not in current and status!="not_seen":
                    h.execute("UPDATE case_state SET last_status='not_seen',last_reason='not_in_current_scan' WHERE case_id=?",(key,))
                    h.execute("INSERT INTO case_transitions VALUES (?,?,?,?)",(now,key,"not_seen","not_in_current_scan"))
                    transitions["not_seen"]+=1
            result["overlap_registry"]["history_transitions"]=dict(transitions)
            h.execute("INSERT INTO runs VALUES (?,?)",(now,json.dumps(result,ensure_ascii=True)))
        assert h.execute("PRAGMA quick_check").fetchone()[0]=="ok"
    finally:h.close()
    write_json(OUT/"quality_latest.json",result)
    # Only aggregate metrics in console.
    out={k:v for k,v in result.items() if k not in ("groups","metric_flags")}
    out["metric_flags"]={k:v for k,v in issue_counts.items() if "missing_" not in k}
    print(json.dumps(out,ensure_ascii=True,sort_keys=True))
if __name__=="__main__":main()
