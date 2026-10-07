#!/usr/bin/env python3
"""Decides whether a Weblate pull request may be merged without a person looking at it.

Usage: weblate-automerge-check.py <pull request number>   (needs GH_TOKEN and GITHUB_REPOSITORY)

Exit 0 = every rule below holds. Exit 1 = leave it for a person, with the reasons printed.
The pull request is treated as DATA: its files are fetched as text and parsed, nothing from it
is checked out or run.

Rules:
  - opened by Weblate's own account, from Weblate's own copy of the repository
  - touches nothing but app/src/main/res/values-<language>/strings.xml
  - every string key exists in the English file and is translatable there
  - each translation carries exactly the placeholders its English string has
  - no web address, no markup link and no em dash in any translation
It cannot judge what a translation SAYS. A rude word in a language nobody here reads passes, as
it would pass a person who cannot read it either; who may translate is set in Weblate.
"""
import base64, json, os, re, subprocess, sys
import xml.etree.ElementTree as ET
from collections import Counter

REPO = os.environ.get("GITHUB_REPOSITORY", "PimpinPumpkin/Vela")
WEBLATE_LOGIN, WEBLATE_ID, WEBLATE_REPO = "weblate", 1607653, "weblate/Vela"
PATH = re.compile(r"^app/src/main/res/values-[A-Za-z]{2,3}(-r?[A-Za-z0-9]{2,4})?/strings\.xml$")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sdf]|%%")
FORBIDDEN = [(re.compile(r"https?://|www\.", re.I), "a web address"),
             (re.compile(r"<\s*a\b|href\s*=", re.I), "a link"),
             (re.compile("—"), "an em dash")]
MAX_BYTES = 2_000_000


def gh(path):
    return json.loads(subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True).stdout)


def file_text(repo, path, ref):
    data = gh(f"repos/{repo}/contents/{path}?ref={ref}")
    if data.get("size", 0) > MAX_BYTES:
        raise ValueError("file too large")
    if data.get("encoding") == "base64" and data.get("content"):
        return base64.b64decode(data["content"]).decode("utf-8")
    blob = gh(f"repos/{repo}/git/blobs/{data['sha']}")
    return base64.b64decode(blob["content"]).decode("utf-8")


def entries(text):
    """{key: [texts]} for strings and plural items; refuses entity declarations outright."""
    if "<!DOCTYPE" in text or "<!ENTITY" in text:
        raise ValueError("document type or entity declaration")
    out, fixed = {}, set()
    for el in ET.fromstring(text):
        name = el.get("name")
        if name is None:
            continue
        if el.get("translatable") == "false":
            fixed.add(name)
        if el.tag == "string":
            out[name] = ["".join(el.itertext())]
        elif el.tag == "plurals":
            out[name] = ["".join(i.itertext()) for i in el.findall("item")]
        elif el.tag == "string-array":
            out[name] = ["".join(i.itertext()) for i in el.findall("item")]
    return out, fixed


def placeholders(s):
    return Counter(m for m in PLACEHOLDER.findall(s) if m != "%%")


def check(number):
    problems = []
    pr = gh(f"repos/{REPO}/pulls/{number}")
    user, head = pr["user"], pr["head"]
    if user["login"] != WEBLATE_LOGIN or user["id"] != WEBLATE_ID:
        return [f"not opened by Weblate's account ({user['login']})"]
    if (head.get("repo") or {}).get("full_name") != WEBLATE_REPO:
        return ["does not come from Weblate's copy of the repository"]
    # WEBLATE_CHECK_CLOSED=1 runs the content rules on an already merged pull request, for trying
    # a rule change against history. The workflow never sets it.
    replay = os.environ.get("WEBLATE_CHECK_CLOSED") == "1"
    if pr["base"]["ref"] != "main" or pr.get("draft") or (pr["state"] != "open" and not replay):
        return ["not an open pull request against main"]
    files = gh(f"repos/{REPO}/pulls/{number}/files?per_page=100")
    if len(files) >= 100:
        return ["too many files to check"]
    for f in files:
        if not PATH.match(f["filename"]) or f["status"] not in ("modified", "added"):
            problems.append(f"touches {f['filename']} ({f['status']})")
    if problems:
        return problems
    english, fixed = entries(file_text(REPO, "app/src/main/res/values/strings.xml", pr["base"]["sha"]))
    for f in files:
        lang = f["filename"].split("/")[-2]
        try:
            translated, _ = entries(file_text(WEBLATE_REPO, f["filename"], head["sha"]))
        except Exception as e:  # unreadable or malformed: a person decides
            problems.append(f"{lang}: cannot read the file ({e})")
            continue
        for key, texts in translated.items():
            if key not in english:
                problems.append(f"{lang}: {key} is not a string in the English file")
                continue
            if key in fixed:
                problems.append(f"{lang}: {key} is marked not translatable")
                continue
            # A plural's forms may each use a subset (the "one" form often drops the number), so a
            # form must not use a placeholder its English string lacks; a plain string must match.
            allowed = Counter()
            for e in english[key]:
                allowed |= placeholders(e)
            for t in texts:
                got = placeholders(t)
                exact = len(english[key]) == 1 and len(texts) == 1
                if (exact and got != allowed) or (not exact and got - allowed):
                    problems.append(f"{lang}: {key} has placeholders {dict(got)}, English has {dict(allowed)}")
                for pattern, what in FORBIDDEN:
                    if pattern.search(t):
                        problems.append(f"{lang}: {key} contains {what}")
    return problems


if __name__ == "__main__":
    found = check(int(sys.argv[1]))
    if found:
        print("Left for a person:")
        for p in found[:40]:
            print(" -", p)
        sys.exit(1)
    print("All rules hold.")
