#!/usr/bin/env python3
"""Fail if any translatable string is missing from a locale, or a placeholder count drifts.

Android lint's MissingTranslation doesn't run on assemble/test, so gaps ship silently (a missed
backfill just shows English for that key). This is the cheap guard: it treats the default
res/values/strings.xml as the source of truth, skips anything marked translatable="false"
(brand names, the intentionally-English-only Flock strings), and requires every other
<string>/<plurals> key to exist in each res/values-<lang>/strings.xml. It also checks that the
%1$s / %2$d placeholder set matches the default, so a %d fed a String (a runtime crash) is caught.

Run: python3 tools/check-translations.py   (exit 1 on any gap). Wired into CI before the build.
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
    for locale_file in sorted(glob.glob(os.path.join(RES, "values-*", "strings.xml"))):
        lang = os.path.basename(os.path.dirname(locale_file)).replace("values-", "")
        loc = parse(locale_file)
        for name, ph in base.items():
            if name not in loc:
                problems.append(f"{lang}: missing '{name}'")
            elif loc[name] != ph:
                problems.append(f"{lang}: '{name}' placeholders {sorted(loc[name])} != default {sorted(ph)}")
    if problems:
        print("Translation check FAILED:\n  " + "\n  ".join(problems))
        print(
            "\nFix: add the key to every values-<lang>/strings.xml, or mark it "
            'translatable="false" in the default file if it is intentionally English-only.'
        )
        return 1
    print(f"Translation check OK: {len(base)} translatable keys present in every locale.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
