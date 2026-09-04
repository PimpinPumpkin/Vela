#!/usr/bin/env python3
# Per-frame map ROTATION (camera turn rate) from screenrecord frames, via an ECC Euclidean fit.
#   python3 scripts/jitter/rotation_fit.py nf 360
# Use through a bend: a smooth turn shows a slowly varying angle per frame; kinks or dropped
# frames show as double-then-zero steps. Needs opencv-python-headless.
import sys, glob, numpy as np, cv2
d=sys.argv[1]; fs=sorted(glob.glob(d+'/*.png'))[:int(sys.argv[2]) if len(sys.argv)>2 else 360]
def prep(f):
    a=cv2.imread(f, cv2.IMREAD_GRAYSCALE)[900:1900, 0:1080]
    a=cv2.resize(a,(540,500)); g=cv2.GaussianBlur(a,(0,0),1.2); return g.astype(np.float32)
P=[prep(f) for f in fs]
crit=(cv2.TERM_CRITERIA_EPS|cv2.TERM_CRITERIA_COUNT, 60, 1e-5)
th=[]; dys=[]
for i in range(len(P)-1):
    W=np.eye(2,3,dtype=np.float32)
    try:
        cc,W=cv2.findTransformECC(P[i],P[i+1],W,cv2.MOTION_EUCLIDEAN,crit,None,5)
        ang=np.degrees(np.arctan2(W[1,0],W[0,0])); th.append(ang); dys.append(W[1,2]*2)
    except cv2.error: th.append(np.nan); dys.append(np.nan)
th=np.array(th); dys=np.array(dys); ok=~np.isnan(th)
print("%s: %d frames, rotation per frame: mean %+.3f deg sd %.3f | frame-to-frame CHANGE sd %.3f deg (jerk) | |dtheta|>0.2deg jumps: %d | dy median %.2f px, zero-motion %d, double %d" % (d, ok.sum(), np.nanmean(th), np.nanstd(th), np.nanstd(np.diff(th)), (np.abs(np.diff(th))>0.2).sum(), np.nanmedian(np.abs(dys)), (np.abs(dys)<0.25*np.nanmedian(np.abs(dys))).sum(), (np.abs(dys)>1.6*np.nanmedian(np.abs(dys))).sum()))
print("  theta/frame (deg) first 150:", " ".join("%+.2f"%x for x in th[:150]))
