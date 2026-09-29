# Vela documentation

Everything written about Vela Maps, in one place: how to use it, what it sends where, how each
part works, and the full technical specification. Use the search box to find anything, from
"offline routing" to a class name like `NavSession`.

This folder is also the source of the documentation website at
<https://pimpinpumpkin.github.io/Vela/docs/>, rebuilt from `main` on every push.

## Start here

<div class="vela-cards" markdown>
<a href="FAQ.md"><strong>Questions and answers</strong><span>Are the places Google's? Does it work offline? How do I run it with no Google at all?</span></a>
<a href="../PRIVACY.md"><strong>What reaches Google</strong><span>Every request the app can make, which setting controls it, and what stays on the phone.</span></a>
<a href="../FDROID.md"><strong>Install with F-Droid</strong><span>Add Vela's own repository to any F-Droid client, or use Obtainium.</span></a>
<a href="../FEATURES.md"><strong>Every feature</strong><span>The complete list, newest first, with the date each one landed.</span></a>
</div>

## How it works

<div class="vela-cards" markdown>
<a href="book/README.md"><strong>The Vela book</strong><span>Subsystem by subsystem, with the real numbers: where pins come from, when data is rebuilt, how rerouting decides.</span></a>
<a href="../SPEC.md"><strong>Technical specification</strong><span>The authoritative reference: every contract, constant and constraint in the app.</span></a>
<a href="../ROADMAP.md"><strong>Roadmap</strong><span>What is still open, and the big bets.</span></a>
</div>

## Chapters of the book

| Chapter | Covers |
| --- | --- |
| [Places on the map](book/01-places.md) | Where the pins come from, how they are baked, which ones you see |
| [Data and rebakes](book/02-data-and-rebakes.md) | Every hosted dataset and when it is rebuilt |
| [Surveillance cameras](book/03-cameras.md) | The camera dataset, the warnings, avoiding them |
| [Navigation](book/04-navigation.md) | The per-fix loop, rerouting, traffic rechecks, pausing a drive |
| [Routing](book/05-routing.md) | Which router answers, and how traffic is added |
| [Search](book/06-search.md) | Suggestions, voice commands, offline search |
| [Talking to Google](book/07-talking-to-google.md) | What Vela asks Google, and how it looks like a browser doing it |
| [Offline](book/08-offline.md) | Region downloads, the offline map, updates |
| [Transit](book/09-transit.md) | Departure boards, transit directions, stop timelines |
| [Android Auto](book/10-android-auto.md) | The car screens, and the gate Google keeps |
| [The drive's chrome](book/11-drive-chrome.md) | Street callouts, the road-ahead bar, the route line |
| [Releases](book/12-releases.md) | Canary, nightly and stable, and how updates reach you |

## For contributors

- [Contributing](../CONTRIBUTING.md): the ground rules, and how requests are handled.
- [Translating](TRANSLATING.md): adding or fixing a language.
- [Building from source](BUILDING.md): the toolchain and the runtime pieces CI fetches.
- [Security](../SECURITY.md): reporting a vulnerability.
- [Maintainer notes](../CLAUDE.md): the long-form build rules, gotchas and history behind the code.
