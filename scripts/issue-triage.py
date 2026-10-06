#!/usr/bin/env python3
"""First-pass triage of a newly opened issue. Run by .github/workflows/issue-triage.yml.

Two checks, both plain text matching (no model, no outside service, nothing but the GitHub API):

  related   Compares the new issue with every earlier issue, open and closed, by the words they
            share, and comments with the closest few. Rare words count for more than common ones.
  version   On a bug report, reads the "Vela version" field. A build older than the current
            stable gets the `incomplete` label and a comment asking for an update first.

It never closes anything: both are notes for the reporter and the maintainer.

    GH_TOKEN=... python3 scripts/issue-triage.py                  # the issue in $GITHUB_EVENT_PATH
    GH_TOKEN=... python3 scripts/issue-triage.py --dry-run 123    # print what #123 would get
"""
import json
import math
import os
import re
import sys
import urllib.request
from datetime import datetime, timedelta

REPO = os.environ.get("GITHUB_REPOSITORY", "PimpinPumpkin/Vela")
TOKEN = os.environ.get("GH_TOKEN", "")
MAX_RELATED = 3
# Below this similarity a match is more likely a coincidence of common words than the same topic.
MIN_SCORE = 0.31
# A stable this new may not have reached the reporter yet, so nothing is flagged against it.
STABLE_GRACE = timedelta(days=3)

STOP = set("""
a an and are as at be been but by can cannot could did do does doesn dont for from get gets had
has have how i if in into is isn it its just like make me my need needs no not of on one only or
our out please should show shows so some than that the their them then there these they this to
too up use used using vela want was we were what when where which while will with without work
working works would you your app add added adding support option feature request bug issue
""".split())


def api(path, method="GET", body=None):
    req = urllib.request.Request(
        "https://api.github.com" + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "vela-issue-triage",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def all_issues():
    out, page = [], 1
    while True:
        batch = api(f"/repos/{REPO}/issues?state=all&per_page=100&page={page}")
        out += [i for i in batch if "pull_request" not in i]
        if len(batch) < 100:
            return out
        page += 1


def section(body, heading):
    """The text under one `### heading` of an issue form."""
    m = re.search(rf"^### {re.escape(heading)}\s*\n(.*?)(?=^### |\Z)", body or "", re.S | re.M)
    text = m.group(1).strip() if m else ""
    return "" if text == "_No response_" else text


def words(issue):
    """The issue's title, plus the start of what the reporter wrote, as a set of word stems."""
    body = issue.get("body") or ""
    main = section(body, "What happened") or section(body, "What you want")
    text = f"{issue['title']} {' '.join(main.split()[:60])}".lower()
    out = set()
    for w in re.findall(r"[a-z][a-z0-9]{2,}", text):
        w = re.sub(r"(ing|ed|es|s)$", "", w) if len(w) > 4 else w
        if w not in STOP:
            out.add(w)
    return out


def related(new, others):
    docs = {i["number"]: words(i) for i in others}
    mine = words(new)
    n = len(docs) + 1
    df = {}
    for ws in list(docs.values()) + [mine]:
        for w in ws:
            df[w] = df.get(w, 0) + 1
    idf = {w: math.log(n / c) for w, c in df.items()}
    norm = lambda ws: math.sqrt(sum(idf[w] ** 2 for w in ws)) or 1.0
    scored = []
    for i in others:
        shared = mine & docs[i["number"]]
        if len(shared) < 2:
            continue
        score = sum(idf[w] ** 2 for w in shared) / (norm(mine) * norm(docs[i["number"]]))
        if score >= MIN_SCORE:
            scored.append((score, i))
    return [i for _, i in sorted(scored, key=lambda s: -s[0])[:MAX_RELATED]]


def state(issue):
    if issue["state"] == "open":
        return "open"
    return {"not_planned": "closed as not planned", "duplicate": "closed as a duplicate"}.get(
        issue.get("state_reason"), "closed")


def build(text):
    """(minor, run) from a version such as 0.4.1912, v0.4.1912 or 0.4.1950-canary."""
    m = re.search(r"(?<![\d.])0\.(\d+)\.(\d+)", text)
    return (int(m.group(1)), int(m.group(2))) if m else None


def outdated(new):
    """The stable tag the reporter should be on, when the build they named is older than it."""
    if not any(l["name"] == "bug" for l in new.get("labels", [])):
        return None
    mine = build(section(new.get("body"), "Vela version"))
    if not mine:
        return None
    # releases/latest is the newest release that is not a prerelease, which is the stable.
    stable = api(f"/repos/{REPO}/releases/latest")
    opened = datetime.fromisoformat(new["created_at"].replace("Z", "+00:00"))
    published = datetime.fromisoformat(stable["published_at"].replace("Z", "+00:00"))
    if opened - published < STABLE_GRACE or not build(stable["tag_name"]):
        return None
    return stable["tag_name"] if mine < build(stable["tag_name"]) else None


def main():
    dry = "--dry-run" in sys.argv
    if dry:
        new = api(f"/repos/{REPO}/issues/{int(sys.argv[sys.argv.index('--dry-run') + 1])}")
    else:
        with open(os.environ["GITHUB_EVENT_PATH"]) as f:
            new = json.load(f)["issue"]
    number = new["number"]

    notes = []
    stable = outdated(new)
    if stable:
        notes.append(
            f"This report names a build older than the current stable ({stable}). Please update, "
            "check whether it still happens, and edit the version above. Reports on older builds "
            "are closed (see CONTRIBUTING.md).")
    hits = related(new, [i for i in all_issues() if i["number"] < number])
    if hits:
        lines = "\n".join(f"- #{i['number']} {i['title']} ({state(i)})" for i in hits)
        notes.append(
            "Earlier issues that share wording with this one. If one of them is the same thing, "
            "say so here and add any new detail there.\n\n" + lines)

    if dry:
        print(f"#{number} {new['title']}\n" + ("\n\n".join(notes) if notes else "(nothing to add)"))
        return
    if stable:
        api(f"/repos/{REPO}/issues/{number}/labels", "POST", {"labels": ["incomplete"]})
    if notes:
        body = "\n\n".join(notes) + "\n\n<sub>Automatic note from matching text, not a decision.</sub>"
        api(f"/repos/{REPO}/issues/{number}/comments", "POST", {"body": body})
    print(f"#{number}: {len(hits)} related, outdated={bool(stable)}")


if __name__ == "__main__":
    main()
