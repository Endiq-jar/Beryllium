#!/usr/bin/env python3
"""Coarse ASCII/luminance preview of a PNG, for eyeballing headless renders."""
import struct, sys, zlib

def load(path):
    d = open(path, "rb").read()
    pos, idat = 8, b""
    w = h = None
    while pos + 8 <= len(d):
        (ln,) = struct.unpack(">I", d[pos:pos+4]); typ = d[pos+4:pos+8]; data = d[pos+8:pos+8+ln]
        if typ == b"IHDR": w, h, bd, ct = struct.unpack(">IIBB", data[:10])
        elif typ == b"IDAT": idat += data
        elif typ == b"IEND": break
        pos += 12 + ln
    raw = zlib.decompress(idat); stride = w*4+1
    out = bytearray(w*h*4); prev = bytearray(w*4)
    for y in range(h):
        ft = raw[y*stride]; row = bytearray(raw[y*stride+1:(y+1)*stride])
        for x in range(w*4):
            a = row[x-4] if x >= 4 else 0; b = prev[x]; c = prev[x-4] if x >= 4 else 0
            v = row[x] + (0 if ft == 0 else a if ft == 1 else b if ft == 2 else (a+b)//2 if ft == 3
                          else min(abs(a+b-c-a) and (a if min(abs(a+b-c-a),abs(a+b-c-b),abs(a+b-c-c))==abs(a+b-c-a) else (b if abs(a+b-c-b)<=abs(a+b-c-c) else c))) if ft == 4 else 0)
            row[x] = v & 0xFF
        out[y*w*4:(y+1)*w*4] = row; prev = row
    return w, h, out

def main():
    path, cols = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 96
    ramp = " .:-=+*#%@"
    w, h, px = load(path)
    rows = int(cols * (h / w) * 0.5)
    for ry in range(rows):
        line = ""
        for rx in range(cols):
            r0, r1 = ry*h//rows, (ry+1)*h//rows
            c0, c1 = rx*w//cols, (rx+1)*w//cols
            n = lum = 0
            for y in range(r0, r1, max(1, (r1-r0)//7)):
                for x in range(c0, r1 and c1, max(1, (c1-c0)//7)):
                    i = (y*w+x)*4
                    lum += (px[i]*299 + px[i+1]*587 + px[i+2]*114)//1000
                    n += 1
            line += ramp[min(9, (lum//max(1,n))*10//256)]
        print(line)

main()
