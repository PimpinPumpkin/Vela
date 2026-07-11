#!/usr/bin/env python3
"""Composite two SDF glyph PBF sets per 256-range: first set wins per glyph id.

Output keeps the SECOND (base) set's fontstack name, so a style whose layers
request "Noto Sans Regular" can be pointed at these files with only a glyphs-URL
change. Glyph submessages are copied byte-verbatim.
"""
import os, sys

def varint(buf, i):
    r = 0; s = 0
    while True:
        b = buf[i]; i += 1
        r |= (b & 0x7f) << s
        if not b & 0x80:
            return r, i
        s += 7

def fields(buf):
    i = 0
    while i < len(buf):
        key, i = varint(buf, i)
        fn, wt = key >> 3, key & 7
        if wt == 0:
            v, i = varint(buf, i); yield fn, wt, v
        elif wt == 2:
            ln, i = varint(buf, i); yield fn, wt, buf[i:i+ln]; i += ln
        elif wt == 5:
            yield fn, wt, buf[i:i+4]; i += 4
        elif wt == 1:
            yield fn, wt, buf[i:i+8]; i += 8
        else:
            raise ValueError(f"wire type {wt}")

def glyphs_of(path):
    """-> (name, range, {id: raw_glyph_bytes})"""
    data = open(path, 'rb').read()
    name = rng = None
    out = {}
    for fn, wt, v in fields(data):
        if fn == 1 and wt == 2:  # fontstack
            for f2, w2, v2 in fields(v):
                if f2 == 1 and w2 == 2: name = v2
                elif f2 == 2 and w2 == 2: rng = v2
                elif f2 == 3 and w2 == 2:
                    gid = None
                    for f3, w3, v3 in fields(v2):
                        if f3 == 1 and w3 == 0:
                            gid = v3; break
                    if gid is not None:
                        out[gid] = v2
    return name, rng, out

def enc_varint(v):
    out = bytearray()
    while True:
        b = v & 0x7f; v >>= 7
        if v: out.append(b | 0x80)
        else: out.append(b); return bytes(out)

def ld(fn, payload):
    return enc_varint((fn << 3) | 2) + enc_varint(len(payload)) + payload

def composite(win_path, base_path, out_path):
    _, _, win = glyphs_of(win_path) if os.path.exists(win_path) else (None, None, {})
    bname, brng, base = glyphs_of(base_path)
    merged = dict(base)
    merged.update(win)  # winner replaces base per id
    stack = ld(1, bname) + ld(2, brng)
    for gid in sorted(merged):
        stack += ld(3, merged[gid])
    open(out_path, 'wb').write(ld(1, stack))
    return len(win), len(base), len(merged)

if __name__ == '__main__':
    win_root, base_root, out_root = sys.argv[1:4]
    pairs = [("Roboto Regular", "Noto Sans Regular"),
             ("Roboto Bold", "Noto Sans Bold"),
             ("Roboto Italic", "Noto Sans Italic")]
    for win_stack, base_stack in pairs:
        os.makedirs(os.path.join(out_root, base_stack), exist_ok=True)
        tw = tb = tm = 0
        for i in range(0, 65536, 256):
            fn = f"{i}-{i+255}.pbf"
            w, b, m = composite(
                os.path.join(win_root, win_stack, fn),
                os.path.join(base_root, base_stack, fn),
                os.path.join(out_root, base_stack, fn))
            tw += w; tb += b; tm += m
        print(f"{base_stack}: roboto {tw} glyphs win, noto {tb}, merged {tm}")
