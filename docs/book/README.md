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

Planned, not written yet: search and how a query is resolved; routing and the three engines;
navigation (the puck, rerouting, voice); offline downloads; transit; languages and voices;
release channels.

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
