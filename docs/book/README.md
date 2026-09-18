# The Vela book

How Vela actually works, subsystem by subsystem, with the real numbers in it.

The other docs answer different questions. [README](../../README.md) says what Vela is,
[FEATURES](../../FEATURES.md) is the running changelog, [FAQ](../FAQ.md) answers the ten
questions people ask first, and [PRIVACY](../../PRIVACY.md) is the request-by-request
accounting. This book is for the person who wants to know *why the map decided that*: which
dataset a pin came from, what made one shop win the label, when the data is rebuilt, what the
thresholds are.

Every chapter follows the same four beats, so you can skim one and know where to look in the
next:

1. **What you see** - the behavior, in the words a user would use.
2. **Where the data comes from** - the source, the license, and where it is hosted.
3. **How it is decided** - the actual rule, with the actual constants.
4. **Limits** - what it gets wrong, and what would have to change to fix it.

## Chapters

| # | Chapter | Covers |
| --- | --- | --- |
| 1 | [Places on the map](01-places.md) | Where the pins come from, how they are baked, what decides which ones you see |
| 2 | [Data and rebakes](02-data-and-rebakes.md) | Every hosted dataset, when it is rebuilt, how your phone picks up a new build |
| 3 | [Surveillance cameras](03-cameras.md) | The camera dataset, what counts as "on your route", the avoid rule, the warnings |
| 4 | [Navigation](04-navigation.md) | The per-fix loop, off-route and rerouting, traffic rechecks, and the pause |

## What the remaining chapters owe

Every question below was asked by a real person about how something actually works, and is the
reason the chapter exists. A chapter is not finished until it answers its list.

**Routing** (planned): which engine answers, and when the on-phone one takes over from the online
one; whether the arrival time is still Google's when the route is not; how a stop is added
mid-drive and what it does to the plan; what "avoid tolls / highways / ferries" can and cannot
honor per engine.

**The map itself** (planned): why streets and their labels are drawn at the widths and zooms they
are, and how that compares with Google's; how the day and night styles are chosen; what the 3D
buildings cost and why they are on by default; how the map behaves on a high-refresh screen and
what the frame budget actually goes on.

**The drive's chrome** (planned): the road-ahead bar, what it will and will not show, and why it
is portrait-only; the street and exit callouts, where they are placed and when they disappear;
how a stop sign or traffic light on a crossing street is told apart from one on your road; the
two-tap "add this place as a stop" and why it is opt-in.

**Search** (planned): what a typed query is actually sent to, what happens offline, how search
along a route differs from search on the map, and how contacts and saved places enter results.

**The route chooser** (planned): the Google-style picker and the classic panel, what each shows,
how alternates are picked and labeled, what the camera and toll badges mean, and what the steps
preview is for.

**Talking to Google** (planned): what the keyless scrape sends, the browser headers it claims,
how the user agent is kept current through signed calibration without an app release, and what
the app does when Google answers with the stripped early-session shape.

**Offline** (planned): what a region download contains, how big each part is, what still works
with no signal and what silently does not.

**Releases** (planned): canary, nightly and stable, what promotes what and when, and how the
in-app updater picks a build.

Also planned, with no question backlog yet: transit, languages and voices.

## How to search it

The book is plain Markdown in one directory, so the fastest search is the one you already have:

- On GitHub, press <kbd>t</kbd> in the repo and type, or use the search box scoped to
  `path:docs/book`.
- In a clone: `grep -rin "prominence" docs/book/` or `rg -i prominence docs/book`.
- Every constant is written as `NAME = value` next to the rule it controls, so searching the
  constant's name finds both the book's explanation and the code that uses it.

Chapter headings are stable. If you link someone to a rule, link the heading anchor; headings
are only renamed when the behavior itself changes.

## The rule for changing it

**A change that alters behavior updates its chapter in the same commit**, the same way it
updates [FEATURES](../../FEATURES.md). A number in this book that no longer matches the code is
a bug, and it is a worse bug than a stale changelog line, because someone will trust it. If a
change has no chapter yet, either write the chapter or add a line to the planned list above
saying what is missing.
