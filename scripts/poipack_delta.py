#!/usr/bin/env python3
"""Build a row-level delta between two place packs.

Usage: poipack_delta.py <old.db> <new.db> <out_delta.db>

The delta is itself a small SQLite file holding, per pack table, the rows to delete (del_*) and the
rows to insert (ins_*), computed with plain EXCEPT queries. The app applies it inside one transaction
and verifies the result against the manifest's row counts, so an update is a few MB of real OSM churn
instead of a full pack re-download. This only stays small because sids are STABLE hashes of the street
name (pack format v2, poipack_build.py) - with counter sids one new street renumbered millions of rows.

Deletes are stored as full rows: the pack tables have no surrogate keys, so a row's identity IS its
column tuple, and the apply side deletes by matching every column. Exits 0 and prints the change count;
the caller decides whether the delta is worth publishing (a v1->v2 format jump diffs everything, and a
delta bigger than the pack helps nobody).
"""
import json
import sqlite3
import sys

TABLES = {
    "poi": "id, name, lat, lng, category, address, phone, website, hours",
    "streetname": "sid, street, street_norm",
    "addr": "hn, sid, city, lat, lng",
    "streetpt": "sid, lat, lng",
}


def main():
    old, new, out = sys.argv[1], sys.argv[2], sys.argv[3]
    db = sqlite3.connect(out)
    db.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA user_version=2;")
    db.execute("ATTACH ? AS old", (old,))
    db.execute("ATTACH ? AS new", (new,))
    changes = {}
    for table, cols in TABLES.items():
        db.execute(f"CREATE TABLE del_{table} AS SELECT {cols} FROM old.{table} EXCEPT SELECT {cols} FROM new.{table}")
        db.execute(f"CREATE TABLE ins_{table} AS SELECT {cols} FROM new.{table} EXCEPT SELECT {cols} FROM old.{table}")
        dels = db.execute(f"SELECT COUNT(*) FROM del_{table}").fetchone()[0]
        ins = db.execute(f"SELECT COUNT(*) FROM ins_{table}").fetchone()[0]
        changes[table] = {"del": dels, "ins": ins}
    db.commit()
    db.execute("VACUUM")
    db.close()
    total = sum(c["del"] + c["ins"] for c in changes.values())
    print(json.dumps({"total": total, "changes": changes}))


if __name__ == "__main__":
    main()
