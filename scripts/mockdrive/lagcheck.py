#!/usr/bin/env python3
"""How far the drawn arrow and the camera trail a lagdrive.py drive, from the VelaNavTrace
logcat lines (`debug.vela.tune.navTraceLog 1`) and MockDrive's send times.
usage: lagcheck.py <tag> <truth.json> <folder holding tag.logcat and tag.mock>"""
import json, re, sys, bisect
tag, truthf = sys.argv[1], sys.argv[2]
D = sys.argv[3]
j = json.load(open(truthf)); T = j['rows']; pull = j['pull']
tt = [r[0] for r in T]
def truth(t):
    i = max(0, min(len(T)-2, bisect.bisect_right(tt, t)-1)); a, b = T[i], T[i+1]
    f = 0 if b[0] == a[0] else (t-a[0])/(b[0]-a[0]); f = max(0, min(1, f))
    dh = (b[3]-a[3]+540) % 360-180
    return a[1]+(b[1]-a[1])*f, a[2]+(b[2]-a[2])*f, (a[3]+dh*f) % 360
er = {}
for line in open(f"{D}/{tag}.mock"):
    m = re.match(r'(\d+) er=(\d+)', line)
    if m: er[int(m.group(1))] = int(m.group(2))
er1 = er[1]
def td(e): return 1 + (e-er1)/1000.0
rows = []; fixes = []
for line in open(f"{D}/{tag}.logcat"):
    m = re.search(r' row er=(\d+) p=([-\d.]+) v=([-\d.]+) win=([-\d.]+) chord=([-\d.NaN]+) disp=([-\d.NaN]+) cam=([-\d.NaN]+)', line)
    if m:
        e, p, v, w, c, d, cam = m.groups(); rows.append((td(int(e)), float(p), float(v), float(c), float(d), float(cam)))
    m = re.search(r' fix er=(\d+) m=([-\d.]+) v=([-\d.]+) age=(\d+) ok=(\d)', line)
    if m: fixes.append((td(int(m.group(1))), float(m.group(2)), float(m.group(3)), int(m.group(4)), int(m.group(5))))
# offset from the stop hold just before pull-away
hold = [r for r in rows if pull-6 < r[0] < pull-0.5]
off = 0.0  # the routes agree: fix m - truth is 0.0 m at the holds and in the cruise
print('arrow past the car at the stop: %.2f m' % (sum(r[1] for r in hold)/len(hold) - truth(pull-3)[0]))
print(f"{tag}: {len(rows)} rows, {len(fixes)} fixes, ages {sorted(set(f[3] for f in fixes))[:6]}, offset {off:.2f} m")
def ang(a, b): return (a-b+540) % 360-180
print(" t-pull  truth_s  truth_v  lag_m  lag_s | truth_h  arrow_err  cam_err")
for r in rows:
    t = r[0]
    if pull-0.2 < t < pull+9 and abs((t*10) % 2.5) < 0.17:
        s, v, h = truth(t); ps = r[1]-off
        # time lag: find tau with truth(t - tau).s = ps
        lo, hi = 0.0, 3.0
        for _ in range(30):
            mid = (lo+hi)/2
            if truth(t-mid)[0] > ps: lo = mid
            else: hi = mid
        print(f" {t-pull:5.1f}  {s:7.1f}  {v:5.1f}  {s-ps:5.2f}  {lo:4.2f} | {h:6.1f}  chord {ang(r[3], h):6.1f}  {ang(r[4], h):6.1f}  {ang(r[5], h):6.1f}")
# summary over the turn window: arc entry..exit in truth distance
a0, a1 = j['arcStart'], j['arcEnd']
win = [r for r in rows if a0-5 <= truth(r[0])[0] <= a1+10]
def lagtime(col):
    # shift tau minimizing mean |ang(col(t), h(t - tau))|
    best = None
    for k in range(0, 300):
        tau = k/100
        e = sum(abs(ang(r[col], truth(r[0]-tau)[2])) for r in win)/len(win)
        if best is None or e < best[1]: best = (tau, e)
    return best
mx_a = max(abs(ang(r[4], truth(r[0])[2])) for r in win); mx_c = max(abs(ang(r[5], truth(r[0])[2])) for r in win)
pos = [truth(r[0])[0]-(r[1]-off) for r in rows if pull < r[0] < pull+8]
mx_ch = max(abs(ang(r[3], truth(r[0])[2])) for r in win)
print(f" turn: chord lags {lagtime(3)[0]:.2f} s (max err {mx_ch:.0f} deg); arrow heading lags {lagtime(4)[0]:.2f} s (max err {mx_a:.0f} deg), camera lags {lagtime(5)[0]:.2f} s (max err {mx_c:.0f} deg); position behind truth over the first 8 s: mean {sum(pos)/len(pos):.2f} m, max {max(pos):.2f} m")
