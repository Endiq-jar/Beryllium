#!/usr/bin/env python3
"""PNG inspection helpers shared by the image tests and by manual poking.

Depends only on zlib: no PIL, no ImageMagick, nothing to install. That matters
because the engine's whole promise is that a headless run is reproducible, and
so is the check on its output.

Reads a PNG written by libberyl (8-bit RGBA or RGB, any per-row filter) and
reports the pixel statistics the tests assert on.
"""
import collections
import struct
import sys
import zlib

CLEAR_FALLBACK = (0x63, 0x91, 0xCC)  # engine night-free sky at day_factor 0.85


def _paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def read_png(path):
    """-> (width, height, bytes RGBA) for 8-bit truecolour PNGs."""
    d = open(path, "rb").read()
    if d[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path}: not a PNG")
    pos, idat, w, h, depth, ctype = 8, b"", None, None, None, None
    plte = None
    while pos + 8 <= len(d):
        (ln,) = struct.unpack(">I", d[pos:pos + 4])
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        (crc,) = struct.unpack(">I", d[pos + 8 + ln:pos + 12 + ln])
        if zlib.crc32(typ + data) & 0xFFFFFFFF != crc:
            raise ValueError(f"{path}: CRC mismatch in {typ!r}")
        if typ == b"IHDR":
            w, h, depth, ctype, comp, filt, inter = struct.unpack(">IIBBBBB", data)
            if depth != 8 or inter != 0 or comp != 0 or filt != 0:
                raise ValueError(f"{path}: unsupported IHDR depth={depth} ct={ctype} inter={inter}")
        elif typ == b"PLTE":
            plte = data
        elif typ == b"IDAT":
            idat += data
        elif typ == b"IEND":
            break
        pos += 12 + ln
    if ctype not in (2, 6):
        raise ValueError(f"{path}: expected colour type 2 or 6, got {ctype}")
    nch = 3 if ctype == 2 else 4
    raw = zlib.decompress(idat)
    stride = w * nch + 1
    if len(raw) != stride * h:
        raise ValueError(f"{path}: IDAT length {len(raw)} != {stride * h}")
    out = bytearray(w * h * 4)
    prev = bytearray(w * nch)
    for y in range(h):
        ft = raw[y * stride]
        row = bytearray(raw[y * stride + 1:(y + 1) * stride])
        for x in range(w * nch):
            a = row[x - nch] if x >= nch else 0
            b = prev[x]
            c = prev[x - nch] if x >= nch else 0
            if ft == 0:
                add = 0
            elif ft == 1:
                add = a
            elif ft == 2:
                add = b
            elif ft == 3:
                add = (a + b) >> 1
            elif ft == 4:
                add = _paeth(a, b, c)
            else:
                raise ValueError(f"{path}: bad filter {ft} on row {y}")
            row[x] = (row[x] + add) & 0xFF
        if ctype == 6:
            out[y * w * 4:(y + 1) * w * 4] = row
        else:
            for x in range(w):
                o = (y * w + x) * 4
                out[o:o + 3] = row[x * 3:x * 3 + 3]
                out[o + 3] = 255
        prev = row[:w * nch]
    if plte is not None:  # not produced by libberyl, kept for completeness
        pass
    return w, h, bytes(out)


def stats(path):
    """-> dict with the numbers a test can assert on."""
    w, h, px = read_png(path)
    n = w * h
    hist = collections.Counter()
    sky = 0
    luma_sum = 0
    for i in range(n):
        p = px[i * 4:i * 4 + 4]
        hist[p] += 1
        luma_sum += (p[0] * 299 + p[1] * 587 + p[2] * 114) // 1000
        if p[0] == CLEAR_FALLBACK[0] and p[1] == CLEAR_FALLBACK[1] and p[2] == CLEAR_FALLBACK[2]:
            sky += 1
    top = hist.most_common(1)[0]
    return {
        "width": w,
        "height": h,
        "pixels": n,
        "distinct": len(hist),
        "clear_fraction": sky / n,
        "mean_luma": luma_sum / n,
        "top_color": tuple(top[0]),
        "top_fraction": top[1] / n,
        "bytes": px,
    }


def main():
    strict = "--strict" in sys.argv
    ok = True
    for path in sys.argv[1:]:
        if path.startswith("--"):
            continue
        s = stats(path)
        print(f"{path}: {s['width']}x{s['height']} distinct={s['distinct']} "
              f"clear={100*s['clear_fraction']:.1f}% mean_luma={s['mean_luma']:.1f} "
              f"top=#{s['top_color'][0]:02x}{s['top_color'][1]:02x}{s['top_color'][2]:02x} "
              f"({100*s['top_fraction']:.1f}%)")
        if strict and (s["distinct"] <= 16 or s["clear_fraction"] > 0.9):
            print(f"   REJECT: blank or near-blank image")
            ok = False
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
