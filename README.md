# ts-scanner

Part of **Ticker Story**. Scans the previous session's **GUS** and **double tops** on US small-caps
($0.30–$10) and records them as stats **to review** in [`trade`](https://github.com/jv3n/trade).

- **D-1 batch job** — Massive (formerly Polygon.io) free Basic plan : end-of-day data, REST only,
  5 requests a minute.
- The patterns are defined in `trade` (`docs/pattern/GUS.md` — OPS, `docs/pattern/DT.md` — BARES).
- Recorded stats arrive **to review** in `trade` ; one that exists — typed, to review, rejected — is
  never recreated, so re-running a day is harmless.

Conventions : [`.claude/CLAUDE.md`](.claude/CLAUDE.md).

## Setup

Kotlin / JVM 25 with Gradle (the wrapper is committed).

```bash
cp .env.example .env   # add your Massive API key
```

## Run

```bash
./gradlew run                              # scan the previous trading day
./gradlew run --args="--from 2026-09-25"   # backfill : every trading day since
./gradlew run --args="--from 2026-10-21 --to 2026-10-21"   # one past day alone (or a range)
./gradlew test                             # unit tests
./gradlew spotlessCheck build              # what the CI runs
./gradlew fetchFixtures -Pcases=ZTG:2026-10-16,…   # real 1-min bars into the test fixtures
```

It pushes to `trade` at `TRADE_URL` with the bearer `TRADE_API_TOKEN`. Day to day, `trade`'s Tilt
runs it and sets both (resource `ts-scanner 📡`, jv3n/trade#766) ; run by hand, put them in `.env`.
Each run is reported to `trade` — a failed one too — and exits non-zero when a day failed.

Each stat goes over with its day of 1-min bars, for its chart in `trade` (jv3n/trade#784). After the
scan, a run also fetches the bars of the stats still without them — typed by hand, or older than the
charts — at most 20 ticker-days a run (~4 min), newest first ; a day Massive has nothing for is
marked and never asked again. That pass fails no run : what it could not read is tried the next one.

Docker : `docker compose run --rm ts-scanner` (image `ghcr.io/jv3n/ts-scanner`, built on every
push to `master`).
