#!/usr/bin/env python3
"""The bake conductor: runs Vela's data bakes one at a time, hands off.

Every heavy bake (places, place packs, road features, basemap, grid cells, overlays) shares the
repository's 1,000 GitHub API requests an hour with CI, the F-Droid index and each other. On their
own crons they overlapped and failed each other with HTTP 403 (and mailed a failure per region).
Once an hour this script:

  1. reads its record (state.json on the `bake-conductor` release; created on first use);
  2. settles the run it started last: success -> the job is fresh; failed regions -> queued for a
     retry of just those regions (fresh dispatch with the job's retryInput, up to maxRetries);
     only a non-region step failed (a manifest merge) -> `gh run rerun --failed`;
  3. publishes a staged manifest (the schedule's `flips`) once every job feeding it finished a
     clean cycle and the staging manifest passes its checks;
  4. if no heavy bake is running and at least `reserve` API requests are left, starts ONE bake:
     a pending retry first, else the most overdue job in tools/bake-schedule.json.

It never fails its own run (every problem is a warning), so it cannot mail failures itself, and it
never overwrites a record it could not read.
"""
import calendar
import json
import os
import re
import subprocess
import sys
import time

REPO = os.environ.get("GITHUB_REPOSITORY", "PimpinPumpkin/Vela")
TAG = "bake-conductor"
DRY = os.environ.get("DRY_RUN") == "1"  # print what would happen, change nothing
MATRIX_JOB = re.compile(r"^[\w-]+ \(([a-z0-9][a-z0-9-]*)[,)]")


def gh(*args, check=True):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args[:3])}: {r.stderr.strip()[:300]}")
    return r


def ghj(*args):
    out = gh(*args).stdout.strip()
    return json.loads(out) if out else None


def root_commit():
    for line in open("scripts/bake-lib.sh"):
        if line.startswith("CELLS_RELEASE_TARGET="):
            return line.split("=", 1)[1].strip()
    return "HEAD"


def workflow_name(path):
    for line in open(f".github/workflows/{path}"):
        if line.startswith("name:"):
            return line.split(":", 1)[1].strip().strip('"')
    return path


def load_state():
    r = gh("release", "view", TAG, "--repo", REPO, "--json", "tagName", check=False)
    if r.returncode != 0:
        if "not found" in (r.stderr + r.stdout).lower():
            gh("release", "create", TAG, "--repo", REPO, "--prerelease", "--target", root_commit(),
               "--title", "Bake conductor state",
               "--notes", "State of the data bake conductor (bake-conductor.yml). Infrastructure, not an app release.")
            return {}
        raise RuntimeError(f"cannot read the {TAG} release: {r.stderr.strip()[:200]}")
    r = gh("release", "download", TAG, "--repo", REPO, "-p", "state.json", "-O", "state.json", "--clobber", check=False)
    if r.returncode != 0:
        if "no assets" in (r.stderr + r.stdout).lower() or "no asset" in (r.stderr + r.stdout).lower():
            return {}
        raise RuntimeError(f"cannot download state.json: {r.stderr.strip()[:200]}")
    return json.load(open("state.json"))


def save_state(state):
    with open("state.json", "w") as f:
        json.dump(state, f, indent=1, sort_keys=True)
    if DRY:
        print("dry run: state not saved")
        return
    gh("release", "upload", TAG, "state.json", "--clobber", "--repo", REPO)


def settle(job, st, now, max_retries):
    """Fold the outcome of the job's last run into its state. Returns True when state changed."""
    run_id = st.get("runId")
    if not run_id or st.get("settled"):
        return False
    v = ghj("run", "view", str(run_id), "--repo", REPO, "--json", "status,conclusion,jobs,updatedAt")
    if v["status"] != "completed":
        return False
    st["settled"] = True
    if v["conclusion"] == "success":
        # lastClean: every region of this cycle is baked (a retry that succeeds covers the regions
        # the first run lost). A cycle that gives up below sets lastSuccess only, which is what
        # keeps a flip (FLIPS) from publishing a half-baked catalog.
        st.update(lastSuccess=now, lastClean=now, retries=0, retryIds=[], rerun=False, lastError="")
        print(f"{job['id']}: run {run_id} succeeded")
        return True
    failed = [j["name"] for j in v["jobs"] if j.get("conclusion") in ("failure", "timed_out", "cancelled")]
    ids = sorted({m.group(1) for n in failed for m in [MATRIX_JOB.match(n)] if m})
    other = [n for n in failed if not MATRIX_JOB.match(n)]
    st["lastError"] = f"run {run_id}: {len(ids)} region(s) failed" + (f", plus {', '.join(other)}" if other else "")
    if st.get("retries", 0) >= max_retries:
        # Out of retries: count it as done for this cycle; the next cadence tries again from scratch.
        st.update(lastSuccess=now, retries=0, retryIds=[], rerun=False)
        print(f"::warning::{job['id']}: giving up until the next cycle ({st['lastError']})")
    elif ids:
        st.update(retryIds=ids, rerun=False)
        print(f"{job['id']}: {len(ids)} region(s) to retry: {' '.join(ids[:20])}")
    elif failed:
        st.update(retryIds=[], rerun=True)
        print(f"{job['id']}: rerunning the failed step(s): {', '.join(other)}")
    else:
        st.update(lastSuccess=now, retries=0, retryIds=[], rerun=False)
    return True


def flip(f, state, now):
    """Copy a staging manifest over the live one once every job feeding it finished a CLEAN cycle
    after the last flip, and the staging manifest passes its checks. The old live manifest is kept
    as `previous`, so a rollback is one copy back. Returns True when state changed."""
    fs = state.setdefault("flip:" + f["id"], {})
    last = fs.get("lastFlip", 0)
    sts = [state.get(j, {}) for j in f["jobs"]]
    if not all(st.get("settled", True) and st.get("lastClean", 0) > last for st in sts):
        return False
    if any(st.get("lastSuccess", 0) > st.get("lastClean", 0) for st in sts):
        # A later cycle gave up with regions missing: wait for a clean one.
        return False
    tag = f["tag"]
    for name, out in ((f["staging"], "staging.json"), (f["live"], "live.json")):
        gh("release", "download", tag, "--repo", REPO, "-p", name, "-O", out, "--clobber")
    staging = json.load(open("staging.json")).get("regions", [])
    live = json.load(open("live.json")).get("regions", [])
    assets = {a["name"] for a in (ghj("release", "view", tag, "--repo", REPO, "--json", "assets") or {}).get("assets", [])}
    by_id = {r.get("id"): r for r in staging}
    problems = []
    lost = [r["id"] for r in live if r.get("id") not in by_id]
    if lost:
        problems.append(f"{len(lost)} live region(s) missing from staging: {' '.join(lost[:10])}")
    older = [r["id"] for r in live if r.get("id") in by_id and (by_id[r["id"]].get("rev") or 0) < (r.get("rev") or 0)]
    if older:
        problems.append(f"{len(older)} region(s) older in staging: {' '.join(older[:10])}")
    broken = [r.get("id") for r in staging if not r.get("url") or not r.get("sizeMb")
              or r["url"].rsplit("/", 1)[-1] not in assets]
    if broken:
        problems.append(f"{len(broken)} staging row(s) without a file: {' '.join(map(str, broken[:10]))}")
    live_rev = {r.get("id"): r.get("rev") or 0 for r in live}
    newer = sum(1 for r in staging if (r.get("rev") or 0) > live_rev.get(r.get("id"), -1))
    if not problems and newer == 0:
        fs["lastFlip"] = now  # nothing new to publish; this cycle is done
        print(f"flip {f['id']}: staging has nothing newer than live")
        return True
    if problems:
        fs["lastError"] = "; ".join(problems)
        print(f"::warning::flip {f['id']}: not flipping: {fs['lastError']}")
        return True
    if DRY:
        print(f"dry run: would flip {f['id']} ({newer} newer region(s))")
        return False
    os.replace("live.json", f["previous"])
    gh("release", "upload", tag, f["previous"], "--clobber", "--repo", REPO)
    os.replace("staging.json", f["live"])
    gh("release", "upload", tag, f["live"], "--clobber", "--repo", REPO)
    fs.update(lastFlip=now, lastError="", newer=newer)
    print(f"flip {f['id']}: published {f['staging']} as {f['live']} ({newer} newer region(s)); the old one is {f['previous']}")
    return True


def dispatch(job, inputs, now):
    if DRY:
        print(f"dry run: would dispatch {job['workflow']} {inputs}")
        return None
    args = ["workflow", "run", job["workflow"], "--repo", REPO, "--ref", "main"]
    for k, v in inputs.items():
        args += ["-f", f"{k}={v}"]
    gh(*args)
    for _ in range(12):
        time.sleep(5)
        runs = ghj("run", "list", "--repo", REPO, "--workflow", job["workflow"], "--event", "workflow_dispatch",
                   "-L", "3", "--json", "databaseId,createdAt") or []
        for r in runs:
            created = calendar.timegm(time.strptime(r["createdAt"], "%Y-%m-%dT%H:%M:%SZ"))
            if created >= now - 30:
                return r["databaseId"]
    return None


def main():
    sched = json.load(open("tools/bake-schedule.json"))
    jobs = sched["jobs"]
    now = int(time.time())
    remaining = ghj("api", "rate_limit")["resources"]["core"]["remaining"]
    print(f"API requests left this hour: {remaining}")
    if remaining < 60:
        print("::warning::too little API budget even to check on the bakes; next hour")
        return
    state = load_state()
    changed = False
    for job in jobs:
        st = state.setdefault(job["id"], {})
        try:
            changed |= settle(job, st, now, sched.get("maxRetries", 3))
        except Exception as e:  # a run we can no longer read: forget it, do not block the schedule
            print(f"::warning::{job['id']}: could not read run {st.get('runId')}: {e}")
            st["settled"] = True
            changed = True

    for f in sched.get("flips", []):
        try:
            changed |= flip(f, state, now)
        except Exception as e:  # never block the schedule on a flip
            print(f"::warning::flip {f['id']}: {e}")

    heavy = {workflow_name(j["workflow"]) for j in jobs} | {workflow_name(w) for w in sched.get("alsoHeavy", [])}
    active = []
    for status in ("in_progress", "queued"):
        for r in ghj("run", "list", "--repo", REPO, "--status", status, "-L", "50", "--json", "workflowName,databaseId") or []:
            if r["workflowName"] in heavy:
                active.append(f"{r['workflowName']} #{r['databaseId']}")
    if active:
        print(f"a heavy bake is running ({', '.join(active)}); nothing new this hour")
    elif remaining < sched.get("reserve", 400):
        print(f"only {remaining} API requests left (reserve {sched.get('reserve', 400)}); nothing new this hour")
    else:
        pick = None
        for job in jobs:  # retries first, in schedule order
            st = state[job["id"]]
            if st.get("settled") and (st.get("retryIds") or st.get("rerun")):
                pick = (job, "retry")
                break
        if pick is None:
            best = None
            for i, job in enumerate(jobs):
                st = state[job["id"]]
                if st.get("runId") and not st.get("settled"):
                    continue
                overdue = now - st.get("lastSuccess", 0) - job["everyHours"] * 3600
                if overdue >= 0 and (best is None or overdue > best[0]):
                    best = (overdue, i, job)
            if best:
                pick = (best[2], "due")
        if pick:
            job, why = pick
            st = state[job["id"]]
            if why == "retry" and st.get("rerun"):
                if not DRY:
                    gh("run", "rerun", str(st["runId"]), "--failed", "--repo", REPO)
                st.update(settled=False, rerun=False, retries=st.get("retries", 0) + 1)
                print(f"{job['id']}: reran the failed steps of run {st['runId']}")
            else:
                if why == "retry":
                    inputs = {job["retryInput"]: job.get("retryJoin", ",").join(st["retryIds"])}
                    st["retries"] = st.get("retries", 0) + 1
                else:
                    inputs = {k: (str(time.gmtime(now).tm_wday) if v == "@slice7" else v) for k, v in job.get("inputs", {}).items()}
                    st["retries"] = 0
                run_id = dispatch(job, inputs, now)
                st.update(runId=run_id, settled=run_id is None, retryIds=[], dispatchedAt=now)
                print(f"{job['id']}: dispatched {job['workflow']} ({why}) {inputs} -> run {run_id}")
            changed = True
        else:
            print("nothing is due")

    if changed:
        save_state(state)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("| job | last good bake | next due | current run | note |\n|---|---|---|---|---|\n")
            for job in jobs:
                st = state[job["id"]]
                last = st.get("lastSuccess", 0)
                fmt = lambda t: time.strftime("%Y-%m-%d %H:%M", time.gmtime(t)) if t else "never"
                cur = "" if st.get("settled", True) else str(st.get("runId"))
                f.write(f"| {job['id']} | {fmt(last)} | {fmt(last + job['everyHours'] * 3600) if last else 'now'} | {cur} | {st.get('lastError', '')} |\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"::warning::bake conductor: {e}")
        sys.exit(0)
