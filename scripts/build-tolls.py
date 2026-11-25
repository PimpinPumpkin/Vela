#!/usr/bin/env python3
"""Builds the toll price file the app reads (SPEC 7, Toll prices).

Reads tools/tolls/roads.json and writes OUT/tolls.json and OUT/tolls_version.txt. A road with a
"fetch" key has its rates read from its source page here. Each run that reads a page vouches for
the rates on it for VOUCH_DAYS more days, and a chart that takes effect later is added as a period
of its own, which ends the one before it. So a source that stops answering, or a page this script
can no longer read, leaves the app with no price for that road once the days run out, never an old
price. A road without "fetch" keeps the periods written in the source file.

  build-tolls.py [--previous FILE] [--out DIR] [--today YYYY-MM-DD] [--page ID=FILE]

--previous is the published file, whose periods are carried over. --page reads a fetched road's
page from a file instead of the network.

Exit 0: built (--status FILE gets changed=true when the file differs from --previous). Exit 3: a source did
not answer, and the published file should stay. Exit 1: a source answered with a page this script
cannot read, which someone has to fix.
"""
import argparse
import datetime as dt
import html
import json
import os
import re
import sys
import urllib.request

VOUCH_DAYS = 60
# A vouched period is written again only when its end is this close, so a quiet week publishes
# nothing.
RENEW_DAYS = 30
UA = "VelaMaps-bake (https://github.com/PimpinPumpkin/Vela)"


class Unreadable(Exception):
    """The source answered with a page this script cannot read."""


class NoAnswer(Exception):
    """The source did not answer."""


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001 - any network failure is the same here
        raise NoAnswer(f"{url}: {e}") from e


def cells(row):
    return [re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", c))).strip()
            for c in re.findall(r"<t[hd][^>]*>(.*?)</t[hd]>", row, re.S)]


def minute_of(label):
    """'5 a.m.' -> 300, '9:30 a.m.' -> 570, '12 p.m.' -> 720."""
    m = re.fullmatch(r"(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\.?", label.strip().lower())
    if not m:
        raise Unreadable(f"not a time: {label!r}")
    h, mi = int(m.group(1)) % 12, int(m.group(2) or 0)
    return (h + (12 if m.group(3) == "p" else 0)) * 60 + mi


def cents_of(text):
    """'$1 per trip' -> 100, '$5.30 per trip' -> 530."""
    m = re.search(r"\$(\d+(?:\.\d{2})?)", text)
    if not m:
        raise Unreadable(f"no amount in {text!r}")
    return round(float(m.group(1)) * 100)


def read_407(page):
    """The light-vehicle chart: four tables of 12 zones (westbound weekdays, westbound weekends,
    eastbound weekdays, eastbound weekends, in the order of the page's tabs), the effective date,
    the trip toll and the camera charge."""
    vis = re.sub(r"<(script|style).*?</\1>", "", page, flags=re.S)
    text = re.sub(r"\|+", "|", html.unescape(re.sub(r"<[^>]+>", "|", vis)))
    m = re.search(r"rates are effective (\w+ \d{1,2}, \d{4})", text)
    if not m:
        raise Unreadable("no effective date")
    effective = dt.datetime.strptime(m.group(1), "%B %d, %Y").date()
    tabs = text.find("Westbound"), text.find("Eastbound")
    if tabs[0] < 0 or not tabs[0] < tabs[1]:
        raise Unreadable("the Westbound and Eastbound tabs are not where they were")
    starts = [t.start() for t in re.finditer(r"<table", vis)]
    tables = re.findall(r"<table.*?</table>", vis, re.S)
    zone_tables = []
    for start, t in zip(starts, tables):
        rows = [cells(r) for r in re.findall(r"<tr.*?</tr>", t, re.S)]
        if rows and rows[0][1:] == [f"Zone {i}" for i in range(1, 13)]:
            before = re.sub(r"<[^>]+>", " ", vis[max(0, start - 4000):start])
            kind = "weekday" if before.rfind("Weekdays") > before.rfind("Weekends") else "weekend"
            zone_tables.append((kind, rows[1:]))
    if [k for k, _ in zone_tables] != ["weekday", "weekend", "weekday", "weekend"]:
        raise Unreadable(f"zone tables {[k for k, _ in zone_tables]}")
    bands = {"west": {}, "east": {}}
    for i, (kind, rows) in enumerate(zone_tables):
        out = []
        for r in rows:
            if len(r) != 13:
                raise Unreadable(f"a row of {len(r)} cells")
            rates = []
            for c in r[1:]:
                v = re.fullmatch(r"(\d+(?:\.\d+)?)\s*¢", c)
                if not v:
                    raise Unreadable(f"not a rate: {c!r}")
                rates.append(float(v.group(1)))
            if not all(20.0 <= x <= 300.0 for x in rates):
                raise Unreadable(f"a rate out of range in {rates}")
            out.append([minute_of(r[0]), min(rates), max(rates)])
        if len(out) < 2 or [b[0] for b in out] != sorted({b[0] for b in out}):
            raise Unreadable(f"bands not in order: {[b[0] for b in out]}")
        bands["west" if i < 2 else "east"][kind] = out
    trip = fee = None
    for t in tables:
        for r in (cells(x) for x in re.findall(r"<tr.*?</tr>", t, re.S)):
            if len(r) == 3 and r[0].startswith("Trip Toll Charge"):
                trip = cents_of(r[2])
            if len(r) == 3 and r[0].startswith("Camera Charge"):
                fee = cents_of(r[2])
    if trip is None or fee is None:
        raise Unreadable("no trip toll or camera charge row")
    return {"effective": effective.isoformat(), "trip_cents": trip, "fee_cents": fee, "bands": bands}


FETCHERS = {
    "407etr-light": ("https://www.407etr.com/en/rate-chart-light", read_407),
}


def merge(previous, fetched, source, today):
    """The road's periods once [fetched] (read [today] from [source]) is added to [previous]."""
    eff = dt.date.fromisoformat(fetched["effective"])
    periods = [dict(p) for p in previous]
    same = next((p for p in periods if p["effective"] == fetched["effective"]), None)
    end = (max(today, eff) + dt.timedelta(days=VOUCH_DAYS)).isoformat()
    new = dict(fetched, source=source, valid_until=end)
    if same is not None:
        rates_same = all(same.get(k) == new[k] for k in ("trip_cents", "fee_cents", "bands"))
        left = (dt.date.fromisoformat(same["valid_until"]) - today).days
        if rates_same and left > RENEW_DAYS:
            new = same  # nothing to write this week
        periods.remove(same)
    for p in periods:
        if dt.date.fromisoformat(p["effective"]) < eff:
            p["valid_until"] = min(p["valid_until"], (eff - dt.timedelta(days=1)).isoformat())
    periods.append(new)
    periods = [p for p in periods if dt.date.fromisoformat(p["valid_until"]) >= today]
    return sorted(periods, key=lambda p: p["effective"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default="tools/tolls/roads.json")
    ap.add_argument("--previous")
    ap.add_argument("--out", default="app/src/main/assets")
    ap.add_argument("--today")
    ap.add_argument("--page", action="append", default=[])
    ap.add_argument("--status", help="a file to write changed=true or changed=false to")
    a = ap.parse_args()
    today = dt.date.fromisoformat(a.today) if a.today else dt.datetime.now(dt.timezone.utc).date()
    pages = dict(p.split("=", 1) for p in a.page)
    src = json.load(open(a.source, encoding="utf-8"))
    prev = {}
    if a.previous:
        try:
            prev = {r["id"]: r for r in json.load(open(a.previous, encoding="utf-8"))["roads"]}
        except (OSError, ValueError, KeyError):
            prev = {}
    roads = []
    for road in src["roads"]:
        out = {k: v for k, v in road.items() if k not in ("fetch", "periods")}
        if "fetch" in road:
            url, reader = FETCHERS[road["fetch"]]
            page = open(pages[road["id"]], encoding="utf-8").read() if road["id"] in pages else fetch(url)
            fetched = reader(page)
            out["periods"] = merge(prev.get(road["id"], {}).get("periods", []), fetched, url, today)
        else:
            out["periods"] = [p for p in road.get("periods", []) if dt.date.fromisoformat(p["valid_until"]) >= today]
        for p in out["periods"]:
            left = (dt.date.fromisoformat(p["valid_until"]) - today).days
            print(f"{road['id']}: rates from {p['effective']} used until {p['valid_until']} ({left} days)")
        if not out["periods"]:
            print(f"::warning::{road['id']} has no rates in force; the app shows no price for it.")
        roads.append(out)
    body = {
        "note": "Toll prices for Vela Maps. Road lines from OpenStreetMap, ODbL. Rates from each period's source.",
        "roads": roads,
    }
    old = None
    if a.previous:
        try:
            old = json.load(open(a.previous, encoding="utf-8"))
        except (OSError, ValueError):
            old = None
    changed = old is None or {k: v for k, v in old.items() if k not in ("version", "generated")} != body
    version = int(today.strftime("%Y%m%d")) if changed or old is None else old["version"]
    doc = {"version": version, "generated": today.isoformat(), **body} if changed else old
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "tolls.json"), "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    with open(os.path.join(a.out, "tolls_version.txt"), "w") as f:
        f.write(f"{doc['version']}\n")
    if a.status:
        with open(a.status, "w") as f:
            f.write("changed=true\n" if changed else "changed=false\n")
    print(f"version {doc['version']}, {'changed' if changed else 'unchanged'}")


if __name__ == "__main__":
    try:
        main()
    except NoAnswer as e:
        print(f"no answer: {e}", file=sys.stderr)
        sys.exit(3)
    except Unreadable as e:
        print(f"::error::A toll source page could not be read: {e}", file=sys.stderr)
        sys.exit(1)
