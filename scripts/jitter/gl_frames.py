#!/usr/bin/env python3
# Map render-thread frame times from a Perfetto trace (see perfetto.cfg in this folder).
#   adb shell 'cat perfetto.cfg | perfetto -c - --txt -o /data/misc/perfetto-traces/t.pb'
#   adb pull /data/misc/perfetto-traces/t.pb && python3 scripts/jitter/gl_frames.py t.pb
# A "render" is a run of the GL thread separated by sleeps > 0.3 ms. Prints the distribution and
# the share over a 60 Hz budget. Also print the SPACING of the over-budget renders: a fixed
# spacing is a periodic culprit. Needs the perfetto pip package.
import sys, numpy as np
from perfetto.trace_processor import TraceProcessor
for f in sys.argv[1:]:
    tp=TraceProcessor(trace=f); q=lambda s: list(tp.query(s))
    rows=q("""select ts.ts, ts.dur, ts.state, t.name tname from thread_state ts join thread t using(utid) join process p using(upid) where p.name='app.vela' and (t.name like 'RenderThread %' or t.name='TextureViewRend') order by ts.ts""")
    bs=None; fr=[]
    for r in rows:
        if r.state=='S' and r.dur>0.3e6:
            if bs is not None: fr.append((bs,be)); bs=None
        else:
            if bs is None: bs=r.ts
            be=r.ts+r.dur
    d=np.array([(b-a)/1e6 for a,b in fr]); d=d[d>1.0]
    cpu=q("""select sum(ts.dur)/1e6 ms from thread_state ts join thread t using(utid) join process p using(upid) where p.name='app.vela' and (t.name like 'RenderThread %' or t.name='TextureViewRend') and ts.state='Running'""")[0].ms
    print("%s: GL renders %d | median %.1f ms p75 %.1f p90 %.1f p99 %.1f | >16.7ms %.0f%% | GL cpu %.0f%%" % (f, len(d), np.median(d), np.percentile(d,75), np.percentile(d,90), np.percentile(d,99), 100*(d>16.7).mean(), cpu/80))
