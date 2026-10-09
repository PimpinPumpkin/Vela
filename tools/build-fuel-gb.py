#!/usr/bin/env python3
"""Trim the UK Fuel Finder price file to what the app reads (SPEC 5.8).

    tools/build-fuel-gb.py SOURCE.csv OUT_DIR [--source NAME] [--source-url URL]
                           [--max-age-days 3] [--now EPOCH]

SOURCE.csv is the government's Fuel Finder CSV (or the matthewgall/fuelfinder-archive copy of
it, the same columns). Writes two files to OUT_DIR:

  fuel-gb.csv.gz        one row per open forecourt: brand,lat,lng,e10,e5,b7s,b7p,updated
                        (prices in pence, blank when the station sells none or reported a
                        placeholder; updated = the newest price report, unix seconds UTC)
  fuel-gb-manifest.json generated time, source, station count, newest price time, the
                        source's sha256 and the data file's sha256 and size

Rows that are permanently closed, have no usable coordinate, no price between 100 and 250 p
or no report time are dropped. The gzip carries no timestamp, so the same source gives the
same bytes.

Exit codes: 0 written; 2 the source is unreadable or has no usable row; 3 the newest price in
the source is older than --max-age-days (nothing is written, the published file stays).
"""
import argparse
import csv
import gzip
import hashlib
import io
import json
import os
import re
import sys
import time
from datetime import datetime, timezone

PRICE_MIN = 100.0
PRICE_MAX = 250.0
# A broad box around the UK; a coordinate outside it is a data error (0,0 and swapped pairs).
LAT_MIN, LAT_MAX = 49.0, 61.5
LNG_MIN, LNG_MAX = -9.5, 2.5
FUELS = ("E10", "E5", "B7S", "B7P")
DATA_FILE = "fuel-gb.csv.gz"
MANIFEST_FILE = "fuel-gb-manifest.json"

# "Fri Oct 02 2026 15:38:14 GMT+0000 (Coordinated Universal Time)"
TS = re.compile(r"^\w{3} (\w{3}) (\d{1,2}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT([+-])(\d{2})(\d{2})")
MONTHS = {m: i + 1 for i, m in enumerate("Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split())}


def parse_ts(s):
    m = TS.match((s or "").strip())
    if not m or m.group(1) not in MONTHS:
        return None
    mon, day, year, hh, mm, ss, sign, oh, om = m.groups()
    t = datetime(int(year), MONTHS[mon], int(day), int(hh), int(mm), int(ss), tzinfo=timezone.utc)
    off = (int(oh) * 60 + int(om)) * 60
    return int(t.timestamp()) - (off if sign == "+" else -off)


def price(s):
    try:
        p = float((s or "").strip())
    except ValueError:
        return None
    return p if PRICE_MIN <= p <= PRICE_MAX else None


def fmt_price(p):
    return ("%.2f" % p).rstrip("0").rstrip(".") if p is not None else ""


def clean_brand(s):
    s = re.sub(r'[,"\r\n\t]', " ", s or "")
    return re.sub(r"\s+", " ", s).strip()


def iso(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source")
    ap.add_argument("out_dir")
    ap.add_argument("--source", dest="source_name", default="file")
    ap.add_argument("--source-url", default="")
    ap.add_argument("--max-age-days", type=float, default=3.0)
    ap.add_argument("--now", type=int, default=None, help="epoch seconds (tests)")
    a = ap.parse_args()
    now = a.now if a.now is not None else int(time.time())

    try:
        raw = open(a.source, "rb").read()
    except OSError as e:
        print(f"cannot read {a.source}: {e}", file=sys.stderr)
        return 2
    sha = hashlib.sha256(raw).hexdigest()
    reader = csv.DictReader(io.StringIO(raw.decode("utf-8-sig", errors="replace")))
    need = ["forecourts.location.latitude", "forecourts.location.longitude", "forecourts.brand_name"]
    if not reader.fieldnames or any(c not in reader.fieldnames for c in need):
        print("not a Fuel Finder CSV: missing columns", file=sys.stderr)
        return 2

    rows = []
    seen = dropped_closed = dropped_coord = dropped_price = 0
    for r in reader:
        seen += 1
        if (r.get("forecourts.permanent_closure") or "").strip().lower() == "true":
            dropped_closed += 1
            continue
        try:
            lat = float(r["forecourts.location.latitude"])
            lng = float(r["forecourts.location.longitude"])
        except (TypeError, ValueError):
            dropped_coord += 1
            continue
        if not (LAT_MIN <= lat <= LAT_MAX and LNG_MIN <= lng <= LNG_MAX):
            dropped_coord += 1
            continue
        prices = {}
        updated = 0
        for f in FUELS:
            p = price(r.get(f"forecourts.fuel_price.{f}"))
            if p is None:
                continue
            t = parse_ts(r.get(f"forecourts.price_submission_timestamp.{f}")) or \
                parse_ts(r.get(f"forecourts.price_change_effective_timestamp.{f}"))
            if t is None:
                continue
            prices[f] = p
            updated = max(updated, t)
        if not prices or updated <= 0:
            dropped_price += 1
            continue
        rows.append((round(lat, 5), round(lng, 5), clean_brand(r.get("forecourts.brand_name")), prices, updated))

    if not rows:
        print(f"no usable rows in {seen}", file=sys.stderr)
        return 2
    newest = max(r[4] for r in rows)
    age_days = (now - newest) / 86400.0
    print(f"source rows {seen}, kept {len(rows)}, dropped closed {dropped_closed}, "
          f"coordinate {dropped_coord}, price {dropped_price}; newest price {iso(newest)} "
          f"({age_days:.1f} days old)")
    if age_days > a.max_age_days:
        print(f"STALE: the newest price is {age_days:.1f} days old (limit {a.max_age_days})", file=sys.stderr)
        return 3

    rows.sort(key=lambda r: (r[0], r[1], r[2]))
    text = io.StringIO()
    text.write("brand,lat,lng,e10,e5,b7s,b7p,updated\n")
    for lat, lng, brand, prices, updated in rows:
        text.write(",".join([
            brand, "%.5f" % lat, "%.5f" % lng,
            fmt_price(prices.get("E10")), fmt_price(prices.get("E5")),
            fmt_price(prices.get("B7S")), fmt_price(prices.get("B7P")),
            str(updated),
        ]) + "\n")
    plain = text.getvalue().encode("utf-8")
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0, compresslevel=9) as gz:
        gz.write(plain)
    data = buf.getvalue()

    os.makedirs(a.out_dir, exist_ok=True)
    data_path = os.path.join(a.out_dir, DATA_FILE)
    with open(data_path, "wb") as f:
        f.write(data)
    manifest = {
        "version": 1,
        "generated": iso(now),
        "source": a.source_name,
        "sourceUrl": a.source_url,
        "sourceSha256": sha,
        "stations": len(rows),
        "newestPrice": iso(newest),
        "newestPriceEpoch": newest,
        "file": DATA_FILE,
        "fileSha256": hashlib.sha256(data).hexdigest(),
        "fileBytes": len(data),
    }
    with open(os.path.join(a.out_dir, MANIFEST_FILE), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(f"wrote {data_path}: {len(plain)} bytes plain, {len(data)} gzipped, {len(rows)} stations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
