# Contributing to SplitSmart

Thanks for considering it. SplitSmart is a small offline-first Android app and contributions are welcome.

## Before you start

Open an issue first for anything larger than a bug fix. That avoids duplicated work and lets us agree on the approach before you spend time on it.

## Requirements

- JDK 17
- Android SDK with platform 35 and build-tools 35

Set `ANDROID_HOME`, or put `sdk.dir=/path/to/sdk` in `local.properties`. That file is git-ignored.

## Build and test

```sh
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # unit suite
```

A green `testDebugUnitTest` is the gate for every change. CI runs it on each push, so check it before opening a pull request.

## Code style

The codebase is formatted with [ktfmt](https://github.com/facebook/ktfmt), which is Google's Kotlin style using two-space indentation. Format before committing:

```sh
java -jar ktfmt.jar $(git ls-files '*.kt')
```

The jar is on the ktfmt releases page. Two-space indentation and 100-column lines are the house style, so please keep both.

## Tests

Prefer behavior tests over implementation tests. Call code the way a user does and assert against a literal expected value, not a mock's return.

Changes under `core.splits` or `core.settle` need a failing test written first. These two packages are pure Kotlin with no Android dependencies and carry the money math, so a regression there is silent and expensive. Never weaken or delete a test to make a build pass.

## Commit messages

Conventional Commits. Prefix the subject with a type.

```
feat: add per-group default categories
fix: keep split shares summing to the expense total
chore: format with ktfmt
```

## Pull requests

Keep them focused on one change. Describe what changed and why, and note anything a reviewer should look at closely. If the change touches money handling, say which cases you tested.

## Reporting bugs

Include your Android version, device model, what you expected, and what happened. Steps to reproduce are worth more than anything else in the report.

## Code of conduct

Be decent to each other. Assume good faith, and critique code rather than people.
