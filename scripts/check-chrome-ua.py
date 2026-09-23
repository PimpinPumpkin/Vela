#!/usr/bin/env python3
"""Is the Chrome version Vela claims to Google still the current stable?

The user agent and Sec-CH-UA hints Vela sends to Google claim one Chrome major version. Chrome ships
stable about every four weeks, so a compiled constant goes stale by construction, and a stale version
can pin the scrape to an older response shape. This compares the version in effect (calibration.json's
`userAgent` when it sets one, else VelaConfig.USER_AGENT) with Chrome stable for Windows (the platform
the UA claims), from chromiumdash.

Exit 1 when the claimed major is behind a stable major that has been out for GRACE_DAYS (Chrome rolls
out in stages, so a brand-new major is still a minority for its first week), or ahead of stable (a
version that does not exist yet). Exit 0 otherwise. Prints a markdown summary either way.
"""
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

GRACE_DAYS = 7
ROOT = Path(__file__).resolve().parent.parent


def sec_ch_ua(major):
    """The Sec-CH-UA desktop Chrome `major` sends (BrowserHeaders.secChUaFor, from Chromium's
    user_agent_utils.cc)."""
    chars = [" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_"]
    versions = ["8", "99", "24"]
    items = [(f"Not{chars[major % 11]}A{chars[(major + 1) % 11]}Brand", versions[major % 3]),
             ("Chromium", str(major)), ("Google Chrome", str(major))]
    orders = [(0, 1, 2), (0, 2, 1), (1, 0, 2), (1, 2, 0), (2, 0, 1), (2, 1, 0)]
    out = [None] * 3
    for i, pos in enumerate(orders[major % 6]):
        out[pos] = items[i]
    return ", ".join(f'"{n}";v="{v}"' for n, v in out)


def claimed():
    cal = json.loads((ROOT / "calibration.json").read_text())
    ua = cal.get("userAgent")
    src = "calibration.json userAgent"
    if not ua:
        kt = (ROOT / "core/src/main/java/app/vela/core/VelaConfig.kt").read_text()
        m = re.search(r'const val USER_AGENT\s*=\s*((?:\s*"[^"]*"\s*\+?)+)', kt)
        ua = "".join(re.findall(r'"([^"]*)"', m.group(1))) if m else ""
        src = "VelaConfig.USER_AGENT (calibration.json sets none)"
    m = re.search(r"Chrome/(\d+)", ua)
    if not m:
        sys.exit(f"could not read a Chrome version from {src}: {ua!r}")
    return int(m.group(1)), src


def stable():
    url = "https://chromiumdash.appspot.com/fetch_releases?channel=Stable&platform=Windows&num=60"
    rows = json.load(urllib.request.urlopen(url, timeout=30))
    latest = max(r["milestone"] for r in rows)
    first_ms = min(r["time"] for r in rows if r["milestone"] == latest)
    return latest, first_ms / 1000.0


def main():
    have, src = claimed()
    latest, since = stable()
    pushed = json.loads((ROOT / "calibration.json").read_text()).get("secChUa")
    rc = 0
    if pushed and pushed != sec_ch_ua(have):
        # Builds from before 2026-09-23 send the pushed hint as-is; newer ones derive it.
        print(f"**calibration.json secChUa is not what Chrome {have} sends.** Use: `{sec_ch_ua(have)}`\n")
        rc = 1
    age = (time.time() - since) / 86400
    day = time.strftime("%Y-%m-%d", time.gmtime(since))
    print("## Chrome version Vela claims\n")
    print(f"- Vela claims Chrome **{have}** ({src})")
    print(f"- Chrome stable for Windows is **{latest}**, first stable build {day} ({age:.0f} days ago)\n")
    if have > latest:
        print(f"**Ahead of stable**: Chrome {have} does not exist yet. Lower `userAgent` and `secChUa` to {latest}.")
        return 1
    if have < latest and age >= GRACE_DAYS:
        print(f"**Behind stable**: set, bump `version`, re-sign with ./scripts/sign-calibration.sh, commit to main:\n")
        print(f"- `userAgent`: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{latest}.0.0.0 Safari/537.36`")
        print(f"- `secChUa`: `{sec_ch_ua(latest)}`\n")
        print("and move VelaConfig's compiled pair at the next release.")
        return 1
    if have < latest:
        print(f"Chrome {latest} is still rolling out ({age:.0f} of {GRACE_DAYS} days); no action yet.")
    else:
        print("Current.")
    return rc


if __name__ == "__main__":
    sys.exit(main())
