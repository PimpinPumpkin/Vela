#!/usr/bin/env python3
# Per-frame MAP translation from a screenrecord (gradient phase correlation of the map band).
#   python3 scripts/jitter/map_motion.py v.mp4
# Prints the step per frame and how many frames froze (duplicate) or doubled (catch-up). On a
# Pixel 4a in SurfaceView mode the floor is ~3-4% each; a fixed cadence in the frozen frames means
# something periodic on the render thread (2026-09-03: the 150 ms route-line re-upload).
import sys, glob, os, subprocess, numpy as np
from PIL import Image
try:
    import cv2
except ImportError:
    cv2=None
vid=sys.argv[1]; d='nf_'+os.path.basename(vid).split('.')[0]
if not os.path.isdir(d) or len(glob.glob(d+'/*.png'))<50:
    subprocess.run(['rm','-rf',d]); os.makedirs(d, exist_ok=True); subprocess.run(['ffmpeg','-v','quiet','-i',vid,'-fps_mode','passthrough',d+'/f%08d.png'])
fs=sorted(glob.glob(d+'/*.png'))[:420]
def grad(f):
    a=np.asarray(Image.open(f).convert('L').crop((0,900,1080,1900)),dtype=np.float32)
    gy,gx=np.gradient(a); g=np.hypot(gx,gy); return g
G=[grad(f) for f in fs]
win=np.outer(np.hanning(G[0].shape[0]), np.hanning(G[0].shape[1])).astype(np.float32)
def pc(a,b):
    if cv2 is not None:
        (dx,dy),resp=cv2.phaseCorrelate(a*win,b*win); return dx,dy,resp
    A=np.fft.fft2(a*win); B=np.fft.fft2(b*win); R=A*np.conj(B); R/=np.abs(R)+1e-9; r=np.fft.ifft2(R).real
    iy,ix=np.unravel_index(np.argmax(r), r.shape); dy=iy if iy<r.shape[0]/2 else iy-r.shape[0]; dx=ix if ix<r.shape[1]/2 else ix-r.shape[1]
    return -dx,-dy,r.max()
mv=np.array([pc(G[i],G[i+1]) for i in range(len(G)-1)])
dx,dy=mv[:,0],mv[:,1]; mag=np.hypot(dx,dy); med=np.median(mag)
print("%-16s frames %d | step median %.2f px (dy %.2f) sd %.2f | zero-motion (<25%% of median): %d (%.0f%%) | double-step (>160%%): %d (%.0f%%) | dx sd %.2f" % (os.path.basename(vid), len(G), med, np.median(dy), mag.std(), (mag<0.25*med).sum(), 100*(mag<0.25*med).sum()/len(mag), (mag>1.6*med).sum(), 100*(mag>1.6*med).sum()/len(mag), dx.std()))
print("   first 60 steps:", " ".join("%.1f"%m for m in mag[:60]))
