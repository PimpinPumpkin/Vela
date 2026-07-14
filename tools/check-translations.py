#!/usr/bin/env python3
"""Fail on placeholder drift; WARN (not fail) on missing translations.

Translations flow through Weblate now (docs/TRANSLATING.md): new strings are added to the
English base only and translators fill the locales via PRs, with untranslated keys falling back
to English by design - so a missing key is expected life-cycle state, not a bug, and it prints
as a warning. What stays FATAL is placeholder drift: a translation whose %1$s / %2$d set differs
from the default is a runtime crash (a %d fed a String), and Weblate PRs are hand-merged, so
this is the net that catches a bad one. translatable="false" keys (brand names, the
intentionally-English-only Flock strings) are skipped entirely.

Run: python3 tools/check-translations.py   (exit 1 only on placeholder drift). Wired into CI.
"""
import glob
import os
import re
import sys

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")


def parse(path):
    """Return {name: set(placeholders)} for translatable <string>/<plurals> in one file."""
    if not os.path.exists(path):
        return {}
    text = open(path, encoding="utf-8").read()
    out = {}
    # <string name="x" [translatable="false"]>...</string>
    for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', text, re.S):
        name, attrs, body = m.group(1), m.group(2), m.group(3)
        if 'translatable="false"' in attrs:
            continue
        out[name] = set(re.findall(r"%\d+\$[sd]", body))
    # <plurals name="x"> ... </plurals> — union the placeholders across all <item>s
    for m in re.finditer(r'<plurals name="([^"]+)"([^>]*)>(.*?)</plurals>', text, re.S):
        name, attrs, body = m.group(1), m.group(2), m.group(3)
        if 'translatable="false"' in attrs:
            continue
        out[name] = set(re.findall(r"%\d+\$[sd]", body))
    return out


def main():
    base = parse(os.path.join(RES, "values", "strings.xml"))
    if not base:
        print("could not read default strings.xml", file=sys.stderr)
        return 1
    problems = []
    missing = []
    for locale_file in sorted(glob.glob(os.path.join(RES, "values-*", "strings.xml"))):
        lang = os.path.basename(os.path.dirname(locale_file)).replace("values-", "")
        loc = parse(locale_file)
        for name, ph in base.items():
            if name not in loc:
                missing.append(f"{lang}: '{name}'")
            elif loc[name] != ph:
                problems.append(f"{lang}: '{name}' placeholders {sorted(loc[name])} != default {sorted(ph)}")
    if missing:
        print(
            f"Translation gaps ({len(missing)} key/locale pairs, English fallback shows until "
            "Weblate fills them - see docs/TRANSLATING.md):\n  " + "\n  ".join(missing)
        )
    if problems:
        print("Translation check FAILED (placeholder drift = runtime crash):\n  " + "\n  ".join(problems))
        print("\nFix: make the translation's %n$s/%n$d set match the default file exactly.")
        return 1
    print(f"Translation check OK: {len(base)} translatable keys, no placeholder drift.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
