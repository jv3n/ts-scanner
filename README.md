# ts-scanner

Part of **Ticker Story**. Scans the previous session's Double Tops on US small-caps ($1–$10) and
records them as stats in [`trade`](https://github.com/jv3n/trade).

- **D-1 batch job** — Massive (formerly Polygon.io) free Basic plan : end-of-day data, REST only,
  5 requests a minute.
- The DT pattern is defined in `trade` (`docs/pattern/DT.md`, the BARES points).
- Recorded stats are flagged as automatic in `trade`, to be reviewed by hand.

Conventions : [`.claude/CLAUDE.md`](.claude/CLAUDE.md).

## Setup

Kotlin / JVM 25 with Gradle (the wrapper is committed).

```bash
cp .env.example .env   # add your Massive API key
```

## Run

```bash
./gradlew run                  # run the job
./gradlew test                 # unit tests
./gradlew spotlessCheck build  # what the CI runs
```

Docker : `docker compose run --rm ts-scanner` (image `ghcr.io/jv3n/ts-scanner`, built on every
push to `master`).
