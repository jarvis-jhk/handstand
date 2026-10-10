# Contributing to Handstand Timer

Thanks for helping! A few things keep this project the way it is.

## Scope

A focused handstand timer: **sensor detection, timing, and the history/stats of holds.**
Good contributions make detection more reliable on more phones, timing more accurate, or
the history and records more useful. Out of scope: other exercises or general fitness
tracking, accounts, cloud sync, ads, analytics, crash reporting or any other network use —
the app deliberately has no INTERNET permission.

## Small on purpose

- Pure Java (source level 8), no Gradle, no AndroidX/Kotlin, no third-party libraries.
- The UI is built in code; the only resources are the launcher icon.
- `./build.sh` must keep building on a clean Linux machine with nothing but `curl`,
  `unzip` and `git` installed.

Anything that adds a dependency, a resource directory or a permission needs a good reason
in the pull request.

## Bugs, especially detection

Missed or false detections depend heavily on the phone and how it is carried. Please say
which phone and Android version, how the phone was carried (pocket, top up or down), and
what happened versus what you expected. A short excerpt of the raw sensor log around the
event (`Android/data/org.handstandtimer.app/files/sensors-*.csv.gz`) helps a lot —
only share what you are comfortable sharing.

## Pull requests

1. Keep each PR to one change, and describe what you tested and on which device.
2. Run `./build.sh` — it must build without warnings turning into errors.
3. Don't bump version numbers; releases are tagged by the maintainer after review.

By submitting a pull request you agree that your contribution may be distributed under
the same terms as the rest of this repository.
