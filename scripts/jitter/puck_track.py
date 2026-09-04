#!/usr/bin/env python3
# Track the nav ARROW on screen, frame by frame, in an adb screenrecord.
# This measures the thing a driver's eye is on. Nothing else in this folder does.
#   adb shell screenrecord --time-limit 12 --bit-rate 16000000 /sdcard/v.mp4 && adb pull /sdcard/v.mp4
#   mkdir nf && ffmpeg -i v.mp4 -fps_mode passthrough nf/f%08d.png
#   python3 scripts/jitter/puck_track.py nf 400
# Thresholds the WHITE chevron inside a 200 px box around the puck (portrait 1080x2340, puck low
# on screen) and prints its centroid's per-frame motion. Healthy: |dy| mean well under 0.1 px and
# ~0% of frames over half a pixel. Broken (2026-09-03, GeoJSON-symbol puck): 1.4 px, 91%.
import sys, glob, numpy as np, cv2
d=sys.argv[1]; fs=sorted(glob.glob(d+'/*.png'))[:int(sys.argv[2]) if len(sys.argv)>2 else 400]
cs=[]
for f in fs:
    im=cv2.imread(f); roi=im[1560:1760, 440:640]
    b,g,r=roi[:,:,0].astype(int),roi[:,:,1].astype(int),roi[:,:,2].astype(int)
    white=(b>225)&(g>225)&(r>225)
    ys,xs=np.nonzero(white)
    if len(xs)<40: cs.append((np.nan,np.nan)); continue
    cs.append((xs.mean()+440, ys.mean()+1560))
c=np.array(cs); dx=np.diff(c[:,0]); dy=np.diff(c[:,1])
print("%-14s white-glyph px/frame n=%d | x sd %.2f, y sd %.2f | |dx| mean %.2f p90 %.2f | |dy| mean %.2f p90 %.2f max %.2f | frames with |dy|>0.5: %d%%" % (d, len(c), np.nanstd(c[:,0]), np.nanstd(c[:,1]), np.nanmean(abs(dx)), np.nanpercentile(abs(dx),90), np.nanmean(abs(dy)), np.nanpercentile(abs(dy),90), np.nanmax(abs(dy)), int(100*np.nanmean(abs(dy)>0.5))))
print("   y (first 50):", " ".join("%.1f"%y for y in c[:50,1]))
