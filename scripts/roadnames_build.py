#!/usr/bin/env python3
"""Build a region's offline road-name map: local road name -> its Latin/English name.

Reads an osmium GeoJSONSeq stream on stdin (one road way per line, exported with the
name / name:en / name:latin tags) and writes a gzipped TSV `<local>\t<english>` to the
output path. This is the sidecar the app downloads next to a routing graph so offline
turn-by-turn can SAY/SHOW the romanized street name instead of a rule-based skeleton
(issue #184), the same data the online tiles carry as name:latin.

We keep only rows where the Latin name is a genuinely Latin-script string that differs
from the local name, so a road already in Latin (a US street) contributes nothing and a
non-Latin alias is never stored as if it were romanized. name:en wins over name:latin
(a real English name beats a transliteration).

Usage:  osmium export ... -f geojsonseq | roadnames_build.py <out.tsv.gz>
"""
import gzip
import json
import sys
import unicodedata


def is_latin(s: str) -> bool:
    """True if s has at least one Latin letter and no letter in another script."""
    saw_latin = False
    for ch in s:
        if not ch.isalpha():
            continue
        try:
            name = unicodedata.name(ch)
        except ValueError:
            return False  # unnamed letter, treat as foreign
        if name.startswith("LATIN"):
            saw_latin = True
        else:
            return False  # a non-Latin letter (Hebrew, Cyrillic, Han, ...)
    return saw_latin


def main() -> int:
    out_path = sys.argv[1]
    seen: dict[str, str] = {}
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            props = json.loads(line).get("properties") or {}
        except (ValueError, AttributeError):
            continue
        name = (props.get("name") or "").strip()
        if not name or name in seen:
            continue
        eng = (props.get("name:en") or props.get("name:latin") or "").strip()
        if not eng or eng == name or not is_latin(eng):
            continue
        # tabs/newlines would corrupt the TSV; roads never legitimately contain them.
        seen[name] = eng.replace("\t", " ").replace("\n", " ")

    with gzip.open(out_path, "wt", encoding="utf-8") as f:
        for local, eng in seen.items():
            f.write(f"{local}\t{eng}\n")
    print(f"road-names: {len(seen)} local->latin pairs -> {out_path}", file=sys.stderr)
    # exit 1 on an empty map so the caller skips uploading a useless sidecar (a region with no
    # name:en/name:latin roads, e.g. an all-Latin area) instead of publishing an empty file.
    return 0 if seen else 1


if __name__ == "__main__":
    sys.exit(main())
