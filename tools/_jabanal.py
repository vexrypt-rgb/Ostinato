import gzip, os, math
from collections import Counter
base=r"C:\Users\redfa\Documents\MinecraftDev\Ostinato-combat-1.21.11\fabric\run\client\pvplogs"
files=["fight-20261003-033538-Vex0.log.gz","fight-20261003-033616-vex1.log.gz"]

def ent(s):
    v=s.split(",")
    return dict(x=float(v[0]),y=float(v[1]),z=float(v[2]),vx=float(v[3]),vy=float(v[4]),vz=float(v[5]),yaw=float(v[6]),pitch=float(v[7]),hp=float(v[8]),gnd=v[10],item=v[11],fl=v[12] if len(v)>12 else "")

def ang_err(me,tg):
    dx=tg["x"]-me["x"]; dz=tg["z"]-me["z"]; dy=(tg["y"]+1.4)-(me["y"]+1.62)
    horiz=math.hypot(dx,dz)
    yaw=math.degrees(math.atan2(-dx, dz))
    ye=(yaw - me["yaw"] + 180) % 360 - 180
    pitch=math.degrees(-math.atan2(dy, max(horiz,1e-6)))
    pe=pitch-me["pitch"]
    return ye, pe, math.hypot(ye, pe)

for fn in files:
    path=os.path.join(base, fn)
    print("========", fn)
    rows=[]
    with gzip.open(path,"rt",errors="replace") as f:
        for ln in f:
            ln=ln.rstrip("\n")
            if ln.startswith("#"):
                print(ln[:220])
                continue
            if not ln: continue
            r=ln.split("|")
            if len(r)<8: continue
            rows.append(dict(t=int(r[0]),me=ent(r[1]),tg=ent(r[2]),dist=float(r[3]),cd=float(r[4][3:]),dec=r[5][4:],state=r[6],ev=r[7]))
    jab_decs=("spear","spear_fall","spear_air")
    a_ticks=[p for p in rows if "A" in p["ev"]]
    print("A events", len(a_ticks), "decs", Counter(p["dec"] for p in a_ticks))
    connects=0
    cds=[]; dists=[]
    for i,p in enumerate(rows):
        if p["dec"] not in jab_decs and "A" not in p["ev"]:
            continue
        ye,pe,ae=ang_err(p["me"],p["tg"])
        nxt=" ".join((rows[j]["ev"] or "-") for j in range(i, min(len(rows), i+4)))
        h=any("H" in rows[j]["ev"] for j in range(i, min(len(rows), i+6)))
        x=any("X" in rows[j]["ev"] for j in range(i, min(len(rows), i+6)))
        if "A" in p["ev"]:
            cds.append(p["cd"]); dists.append(p["dist"])
            if h or x: connects += 1
        swinging = "S" in p["me"]["fl"]
        print("%4d %-12s d=%.2f cd=%.2f ye=%6.1f pe=%6.1f ae=%5.1f ev=%s nxt=%s S=%s g=%s" % (
            p["t"], p["dec"], p["dist"], p["cd"], ye, pe, ae, p["ev"] or "-", nxt, swinging, p["me"]["gnd"]))
    print("A count", len(cds), "connect within 6t", connects)
    if cds:
        print("A cd avg %.2f min %.2f max %.2f" % (sum(cds)/len(cds), min(cds), max(cds)))
        print("A dist avg %.2f min %.2f max %.2f" % (sum(dists)/len(dists), min(dists), max(dists)))
        print("A cd buckets", Counter(round(c,1) for c in cds))
    print("--- H/X events ---")
    for p in rows:
        if "H" in p["ev"] or "X" in p["ev"]:
            print(" t", p["t"], p["dec"], "d", p["dist"], "cd", p["cd"], "ev", p["ev"])
    print("--- band by dec ---")
    for name in ("spear_close","spear_back","spear_hold","spear","spear_aim","chase"):
        ds=[p["dist"] for p in rows if p["dec"]==name]
        if not ds: continue
        sd=sorted(ds)
        print("%-12s n=%4d dist avg %.2f min %.2f max %.2f p50 %.2f" % (name, len(ds), sum(ds)/len(ds), min(ds), max(ds), sd[len(sd)//2]))
    flips=0
    prev=None
    run=0
    runs=[]
    for p in rows:
        d=p["dec"]
        if d in ("spear_close","spear_back","spear_hold"):
            if prev and d!=prev:
                flips += 1
                runs.append((prev, run))
                run=1
            elif prev is None:
                run=1
            else:
                run += 1
            prev=d
        else:
            if prev:
                runs.append((prev, run))
            prev=None
            run=0
    if prev:
        runs.append((prev, run))
    print("flips", flips, "runs", len(runs), "short<=2", sum(1 for n,l in runs if l<=2), "short<=3", sum(1 for n,l in runs if l<=3))
    lens=Counter()
    for name,ln in runs:
        lens[(name, ln if ln<10 else 10)] += 1
    for name in ("spear_close","spear_back","spear_hold"):
        print(name, {k[1]:v for k,v in sorted(lens.items()) if k[0]==name})
    holds=[p for p in rows if p["dec"]=="spear_hold"]
    if holds:
        hcds=[p["cd"] for p in holds]
        print("hold n", len(holds), "cd avg %.2f <0.9 %d >=0.9 %d >=0.85 %d" % (sum(hcds)/len(hcds), sum(1 for c in hcds if c<0.9), sum(1 for c in hcds if c>=0.9), sum(1 for c in hcds if c>=0.85)))
        hi=[p for p in holds if p["cd"]>=0.85]
        errs=[]
        big=0
        for p in hi:
            ye,pe,ae=ang_err(p["me"],p["tg"])
            errs.append(ae)
            if ae>8: big+=1
        if errs:
            print("hold cd>=0.85 n=%d aimerr avg %.1f >8 %d max %.1f" % (len(errs), sum(errs)/len(errs), big, max(errs)))
    print("--- transitions sample ---")
    shown=0
    prev=None
    for p in rows:
        d=p["dec"]
        if d not in ("spear_close","spear_back","spear_hold"):
            prev=d
            continue
        if prev in ("spear_close","spear_back","spear_hold") and d!=prev and shown<30:
            dx=p["tg"]["x"]-p["me"]["x"]; dz=p["tg"]["z"]-p["me"]["z"]
            dist=math.hypot(dx,dz) or 1
            rel=(p["tg"]["vx"]-p["me"]["vx"])*dx/dist + (p["tg"]["vz"]-p["me"]["vz"])*dz/dist
            print("  t=%d %s->%s dist=%.2f cd=%.2f relv=%.3f ydiff=%.2f meG=%s tgG=%s tgItem=%s" % (
                p["t"], prev, d, p["dist"], p["cd"], rel, p["tg"]["y"]-p["me"]["y"], p["me"]["gnd"], p["tg"]["gnd"], p["tg"]["item"]))
            shown += 1
        prev=d
    # exactReach vs logged dist: logged dist is horizontal? compare
    print("--- dist vs 3d eye ---")
    diffs=[]
    for p in rows[::10]:
        me=p["me"]; tg=p["tg"]
        # eye approx
        dx=tg["x"]-me["x"]; dy=(tg["y"]+1.0)-(me["y"]+1.62); dz=tg["z"]-me["z"]
        d3=math.sqrt(dx*dx+dy*dy+dz*dz)
        diffs.append(d3-p["dist"])
    print("eye3d-logdist avg", sum(diffs)/len(diffs), "min", min(diffs), "max", max(diffs))
