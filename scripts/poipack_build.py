#!/usr/bin/env python3
"""Build a Vela offline POI pack (SQLite) from an osmium geojsonseq export.

Usage: poipack_build.py <features.geojsonl | -> <out.db>   ("-" reads the export from stdin)

Big regions MUST stream: the geojsonseq export is ~12x the filtered PBF (Washington: 161 MB PBF ->
1.9 GB of JSON), so a country-sized export written to disk blows a CI runner. build-poi-region.sh
pipes `osmium export -o -` straight into this script.

The pack holds a whole region's named POIs, address points and street centreline samples so the
app can search/geocode the entire region offline (Organic-Maps-style), not just saved map areas.

Pack schema (v1) — read by the app's OfflinePoiStore/OfflineAddressStore PACK query paths
(NOT identical to their own small on-device tables; the pack is normalized to keep a state-sized
file small — street names are deduped into `streetname` and referenced by integer `sid`):

  poi(id, name, lat, lng, category, address, phone, website, hours)   -- same columns the app SELECTs
  streetname(sid INTEGER PK, street, street_norm)                     -- ~tens of thousands of rows
  addr(hn, sid, city, lat, lng)                                       -- millions of rows, lean
  streetpt(sid, lat, lng)                                             -- sampled centreline points

KEEP IN SYNC with the app:
  - category formatting mirrors OverpassPois.toPlace ("fast_food" -> "Fast food")
  - ABBREV / normalize_street is a port of OfflineAddressStore.normalizeStreet — the stored
    street_norm must match what the app computes on the query side.

Input is `osmium export -f geojsonseq --add-unique-id=type_id` of a tags-filtered extract
(see build-poi-region.sh). Streaming, constant memory except the street-name dict.
"""
import hashlib
import json
import math
import sqlite3
import sys

ROAD_CLASSES = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
    "residential", "living_street", "service", "road",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
}

# Port of OfflineAddressStore.ABBREV — keep identical.
ABBREV = {
    "st": "street", "str": "street", "ave": "avenue", "av": "avenue",
    "blvd": "boulevard", "boul": "boulevard", "dr": "drive", "rd": "road",
    "ln": "lane", "ct": "court", "pl": "place", "sq": "square", "ter": "terrace",
    "cir": "circle", "hwy": "highway", "pkwy": "parkway", "pky": "parkway",
    "trl": "trail", "way": "way", "loop": "loop",
    "n": "north", "s": "south", "e": "east", "w": "west",
    "ne": "northeast", "nw": "northwest", "se": "southeast", "sw": "southwest",
}

SAMPLE_M = 120.0  # one street point per this many metres, matching OverpassPois.SAMPLE_M


def normalize_street(s):
    out = []
    for w in s.lower().replace(".", " ").replace(",", " ").replace("#", " ").split():
        out.append(ABBREV.get(w, w))
    return " ".join(out)


def rep_point(geom):
    """A representative lat/lng for any geometry (centroid-ish, cheap)."""
    t = geom["type"]
    c = geom["coordinates"]
    if t == "Point":
        return c[1], c[0]
    if t == "LineString":
        m = c[len(c) // 2]
        return m[1], m[0]
    if t == "Polygon":
        ring = c[0]
        return (sum(p[1] for p in ring) / len(ring), sum(p[0] for p in ring) / len(ring))
    if t == "MultiPolygon":
        ring = c[0][0]
        return (sum(p[1] for p in ring) / len(ring), sum(p[0] for p in ring) / len(ring))
    if t == "MultiLineString":
        line = c[0]
        m = line[len(line) // 2]
        return m[1], m[0]
    return None


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def category(tags):
    """Mirror of OverpassPois.toPlace: first matching key, '_'->' ', first letter upper."""
    for k in ("amenity", "shop", "tourism", "leisure", "public_transport", "boundary"):
        v = tags.get(k)
        if v:
            disp = v.replace("_", " ")
            return disp[:1].upper() + disp[1:]
    return None


def address(tags):
    hn = tags.get("addr:housenumber")
    street = tags.get("addr:street")
    first = " ".join(x for x in (hn, street) if x) or None
    state_zip = " ".join(x for x in (tags.get("addr:state"), tags.get("addr:postcode")) if x) or None
    parts = [p for p in (first, tags.get("addr:city"), state_zip) if p]
    return ", ".join(parts) or None


def stable_sid(norm):
    """A street's sid is a STABLE hash of its normalized name, not an encounter-order counter.

    This is what makes pack DELTAS small: with a counter, one new street early in the stream
    renumbered every later sid and rewrote millions of addr/streetpt rows; with a hash, unchanged
    streets keep their sid across rebuilds so a monthly delta only carries real OSM churn.
    Truncated SHA-1 to a positive 63-bit int (fits SQLite INTEGER / Kotlin Long); collision odds
    across even a million names are ~1e-7, and a collision is caught at build time below.
    """
    return int.from_bytes(hashlib.sha1(norm.encode("utf-8")).digest()[:8], "big") & 0x7FFFFFFFFFFFFFFF


class StreetNames:
    """norm -> (sid, best display name). Longest original wins (fully spelled out beats 'St SE')."""

    def __init__(self):
        self.by_norm = {}
        self.by_sid = {}

    def sid(self, name):
        norm = normalize_street(name)
        e = self.by_norm.get(norm)
        if e is None:
            s = stable_sid(norm)
            clash = self.by_sid.get(s)
            if clash is not None and clash != norm:
                raise SystemExit(f"sid hash collision: {norm!r} vs {clash!r} - pick a new hash")
            self.by_sid[s] = norm
            e = [s, name]
            self.by_norm[norm] = e
        elif len(name) > len(e[1]):
            e[1] = name
        return e[0]

    def rows(self):
        # sorted for a deterministic table regardless of encounter order
        return sorted((sid, disp, norm) for norm, (sid, disp) in self.by_norm.items())


def main():
    src, out = sys.argv[1], sys.argv[2]
    db = sqlite3.connect(out)
    db.executescript(
        """
        PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
        PRAGMA user_version=2; -- pack format: 2 = stable hashed sids (delta-friendly)
        CREATE TABLE poi(id TEXT, name TEXT, lat REAL, lng REAL, category TEXT,
                         address TEXT, phone TEXT, website TEXT, hours TEXT);
        CREATE TABLE streetname(sid INTEGER PRIMARY KEY, street TEXT, street_norm TEXT);
        CREATE TABLE addr(hn TEXT, sid INTEGER, city TEXT, lat REAL, lng REAL);
        CREATE TABLE streetpt(sid INTEGER, lat REAL, lng REAL);
        """
    )
    names = StreetNames()
    seen_poi = set()
    pois, addrs, streetpts = [], [], []
    n_poi = n_addr = n_pt = 0

    def flush():
        nonlocal pois, addrs, streetpts, n_poi, n_addr, n_pt
        db.executemany("INSERT INTO poi VALUES(?,?,?,?,?,?,?,?,?)", pois)
        db.executemany("INSERT INTO addr VALUES(?,?,?,?,?)", addrs)
        db.executemany("INSERT INTO streetpt VALUES(?,?,?)", streetpts)
        n_poi += len(pois); n_addr += len(addrs); n_pt += len(streetpts)
        pois, addrs, streetpts = [], [], []

    stream = sys.stdin if src == "-" else open(src, encoding="utf-8")
    with stream as f:
        for line in f:
            line = line.strip().lstrip("\x1e")  # geojsonseq RS separator
            if not line:
                continue
            try:
                feat = json.loads(line)
            except ValueError:
                continue
            tags = feat.get("properties") or {}
            geom = feat.get("geometry") or {}
            fid = tags.pop("@id", None) or feat.get("id") or ""
            name = tags.get("name")

            # Named road → sampled centreline points (the geocoder's street-level fallback).
            hwy = tags.get("highway")
            if hwy in ROAD_CLASSES:
                if name and geom.get("type") in ("LineString", "MultiLineString"):
                    lines = [geom["coordinates"]] if geom["type"] == "LineString" else geom["coordinates"]
                    sid = names.sid(name)
                    for line_coords in lines:
                        last = None
                        for i, (lon, lat) in enumerate(line_coords):
                            keep = (
                                i == 0 or i == len(line_coords) - 1 or last is None
                                or haversine_m(last[0], last[1], lat, lon) >= SAMPLE_M
                            )
                            if keep:
                                streetpts.append((sid, lat, lon))
                                last = (lat, lon)
                continue  # roads are never POIs/addresses

            pt = rep_point(geom)
            if pt is None:
                continue
            lat, lng = pt

            hn = tags.get("addr:housenumber")
            street_tag = tags.get("addr:street")
            if hn and street_tag:
                addrs.append((hn, names.sid(street_tag), tags.get("addr:city"), lat, lng))

            cat = category(tags)
            if name and cat and fid not in seen_poi:
                # boundary rows only count when they're a national park (mirrors the Overpass query)
                if tags.get("boundary") and tags.get("boundary") != "national_park" and not any(
                    tags.get(k) for k in ("amenity", "shop", "tourism", "leisure", "public_transport")
                ):
                    continue
                seen_poi.add(fid)
                pois.append((
                    f"osm:{fid}", name, lat, lng, cat, address(tags),
                    tags.get("phone") or tags.get("contact:phone"),
                    tags.get("website") or tags.get("contact:website"),
                    tags.get("opening_hours"),
                ))

            if len(pois) + len(addrs) + len(streetpts) >= 20000:
                flush()

    flush()
    db.executemany("INSERT INTO streetname VALUES(?,?,?)", names.rows())
    db.executescript(
        """
        CREATE INDEX idx_streetname_norm ON streetname(street_norm);
        CREATE INDEX idx_addr_sid ON addr(sid);
        CREATE INDEX idx_addr_hn ON addr(hn);
        CREATE INDEX idx_addr_lat ON addr(lat);
        CREATE INDEX idx_streetpt_sid ON streetpt(sid);
        CREATE INDEX idx_streetpt_lat ON streetpt(lat);
        ANALYZE;
        """
    )
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"pack built: {n_poi} pois, {n_addr} addresses, {n_pt} street points, {len(names.by_norm)} street names")
    # machine-readable row counts for the manifest (the app verifies a delta-applied pack against these)
    counts = {"poi": n_poi, "addr": n_addr, "streetpt": n_pt, "streetname": len(names.by_norm)}
    with open(out + ".counts.json", "w", encoding="utf-8") as cf:
        json.dump(counts, cf)


if __name__ == "__main__":
    main()
