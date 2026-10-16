## What and why

<!-- One or two sentences. Link the issue: "Closes #123" or "Part of #123". -->

## Checks

<!-- The commands you ran and what they printed. "Looks fine" is not a result. -->

```
./gradlew :core-domain:test :core-data:test :core-engine:test :app:testPlayDebugUnitTest
./gradlew detekt :app:verifyPlayFlavorBoundaries
```

- [ ] Tests added or updated for the behaviour changed
- [ ] `git diff --check` is clean

## Invariants

- [ ] This does not touch the write path, or it keeps lock, snapshot, journal, atomic write, re-parse and commit before reporting success
- [ ] No floating point for money
- [ ] Unknown directives, metadata, comments and whitespace are still preserved
- [ ] No new network listener, telemetry, or dependency (a new dependency needs a licence check: the app is GPL-2.0-only)

## Review of a baseline area

<!-- Only for pull requests that belong to #16. -->

- [ ] `review/<area>.md` lists every file of the area (`tools/review-files.py check <area>`)
- [ ] Every finding is fixed here or has its own issue
