#!/usr/bin/env python3
"""Turn-from-stop mock drive with a known true path, for measuring how far the drawn arrow
trails the car. The truth is integrated at 100 Hz along the OSRM line with its first big turn
rounded to a radius; fixes are the truth AGE ms before each send, stamped that old.
usage: lagdrive.py out.txt truth.json start dest [--age MS] [--radius M] [--stop M]"""
import json, math, sys, urllib.request
a = sys.argv[1:]; out = a.pop(0); tout = a.pop(0)
def opt(k, d):
    if k in a: i = a.index(k); v = float(a[i+1]); del a[i:i+2]; return v
    return d
AGE = opt("--age", 150.0); RAD = opt("--radius", 15.0); STOP = opt("--stop", 18.0); TURNV = opt("--turnv", 8.0)
pts = [tuple(map(float, x.split(","))) for x in a]
url = ("https://routing.openstreetmap.de/routed-car/route/v1/driving/%s?overview=full&geometries=geojson&steps=true"
       % ";".join("%.6f,%.6f" % (q[1], q[0]) for q in pts))
r = json.load(urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "vela-test-rig/1.0"}), timeout=30))["routes"][0]
line = [(c[1], c[0]) for c in r["geometry"]["coordinates"]]
R = 6371008.8
o = line[0]
def xy(p): return (math.radians(p[1]-o[1])*math.cos(math.radians(o[0]))*R, math.radians(p[0]-o[0])*R)
def back(x, y): return (o[0] + math.degrees(y / R), o[1] + math.degrees(x / (R * math.cos(math.radians(o[0])))))
L = [xy(p) for p in line]
# drop duplicate vertices
P = [L[0]]
for q in L[1:]:
    if math.hypot(q[0]-P[-1][0], q[1]-P[-1][1]) > 0.3: P.append(q)
# first vertex with a big heading change (> 60 deg within 8 m either side)
def hd(p, q): return math.atan2(q[0]-p[0], q[1]-p[1])
cum = [0.0]
for p, q in zip(P, P[1:]): cum.append(cum[-1] + math.hypot(q[0]-p[0], q[1]-p[1]))
def at(s):
    s = max(0.0, min(cum[-1], s))
    k = next((i for i in range(len(cum)-1) if cum[i+1] >= s), len(cum)-2)
    f = (s-cum[k])/max(1e-9, cum[k+1]-cum[k]); p, q = P[k], P[k+1]
    return (p[0]+(q[0]-p[0])*f, p[1]+(q[1]-p[1])*f)
corner = None
for i in range(1, len(P)-1):
    if cum[i] < 40: continue
    h1 = hd(at(cum[i]-8), P[i]); h2 = hd(P[i], at(cum[i]+8))
    if abs((math.degrees(h2-h1)+540) % 360-180) > 60: corner = i; break
cs = cum[corner]
h1 = hd(at(cs-15), P[corner]); h2 = hd(P[corner], at(cs+15))
turn = (h2-h1+math.pi) % (2*math.pi) - math.pi
# fillet: tangent length t = R tan(|turn|/2)
tl = RAD*math.tan(abs(turn)/2)
A = at(cs-tl); B = at(cs+tl)
# truth path = polyline to A, arc A->B, polyline from B; sample densely
path = []
s = 0.0
while s < cs-tl: path.append(at(s)); s += 0.5
n = 40
# arc center: left or right of the incoming heading
side = 1 if turn > 0 else -1   # turn>0: heading increases = right turn (compass)
nx, ny = math.cos(h1)*side, -math.sin(h1)*side  # right-hand normal of heading h1 (compass: x=sin,y=cos)
C = (A[0]+nx*RAD, A[1]+ny*RAD)
a0 = math.atan2(A[1]-C[1], A[0]-C[0])
for k in range(n+1):
    ang = a0 - side*abs(turn)*k/n
    path.append((C[0]+RAD*math.cos(ang), C[1]+RAD*math.sin(ang)))
s = cs+tl
while s < cum[-1]: path.append(at(s)); s += 0.5
pc = [0.0]
for p, q in zip(path, path[1:]): pc.append(pc[-1]+math.hypot(q[0]-p[0], q[1]-p[1]))
tot = pc[-1]
arcStart = cs-tl  # same along both (polyline before the arc)
def tp(s):
    s = max(0.0, min(tot, s))
    k = next((i for i in range(len(pc)-1) if pc[i+1] >= s), len(pc)-2)
    f = (s-pc[k])/max(1e-9, pc[k+1]-pc[k]); p, q = path[k], path[k+1]
    return (p[0]+(q[0]-p[0])*f, p[1]+(q[1]-p[1])*f), (math.degrees(hd(p, q))+360) % 360
# speed schedule at 100 Hz
dt = 0.01; t = 0.0; s = 0.0; v = 0.0; T = []
stopAt = arcStart - STOP
def run_to(target, vmax, acc, dec, hold_after):
    global t, s, v
    while s < target - 0.01:
        left = target - s
        vt = min(vmax, math.sqrt(max(0.0, 2*dec*left)))
        if s < arcStart + RAD*abs(turn):  # the corner at TURNV, braking for it ahead
            vt = min(vt, math.sqrt(TURNV**2 + 2*dec*max(0.0, arcStart - s)))
        v = min(v + acc*dt, vt) if v < vt else max(vt, v - dec*dt)
        v = max(v, 0.3 if left > 0.05 else 0.0)
        s = min(target, s + v*dt); t += dt; T.append((t, s, v))
    v = 0.0
    for _ in range(int(hold_after/dt)): t += dt; T.append((t, s, 0.0))
for _ in range(int(8/dt)): t += dt; T.append((t, 0.0, 0.0))
run_to(stopAt, 11.0, 2.0, 2.5, 10.0)
pull = t
run_to(min(tot, arcStart + 260), 13.0, 2.2, 2.5, 6.0)
# fixes: send k at k seconds (k>=1), carry the truth at k - AGE
def truth(tq):
    if tq <= T[0][0]: return T[0]
    lo, hi = 0, len(T)-1
    while hi-lo > 1:
        m = (lo+hi)//2
        if T[m][0] <= tq: lo = m
        else: hi = m
    return T[lo]
fixes = []
k = 1
while k <= T[-1][0]:
    tq = k - AGE/1000.0
    _, sq, vq = truth(tq)
    (x, y), h = tp(sq)
    lat, lng = back(x, y)
    fixes.append("%.7f,%.7f,%.2f,%.1f,1000,%d" % (lat, lng, vq, h if vq > 0.3 else 0.0, int(AGE)))
    k += 1
open(out, "w").write("\n".join(fixes)+"\n")
tr = [(round(tt, 2), round(ss, 3), round(vv, 3), round(tp(ss)[1], 2)) for (tt, ss, vv) in T[::5]]
json.dump({"pull": pull, "arcStart": arcStart, "arcEnd": arcStart+RAD*abs(turn), "turnDeg": math.degrees(turn), "rows": tr}, open(tout, "w"))
print("route %.0f m, turn %.0f deg at %.0f m, stop %.0f m before the arc, %d fixes, pull-away at %.1f s"
      % (cum[-1], math.degrees(turn), cs, STOP, len(fixes), pull), file=sys.stderr)
