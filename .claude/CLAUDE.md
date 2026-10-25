# CLAUDE.md

Source of truth for the conventions of `ts-scanner`. Read this first.

## Project

`ts-scanner` is part of **Ticker Story**, the user's multi-repo trading platform (`ts-` is the
platform prefix, not TypeScript). It scans the previous session's **GUS** and **double tops** on US
small-caps ($0.30–$10) and records them as stats to review in [`trade`](https://github.com/jv3n/trade),
the platform's tracker app.

- **D-1 batch job** — market data comes from **Massive** (formerly Polygon.io) on the **free Basic
  plan** : end-of-day data (published the next day), REST only, **5 requests a minute**, no
  snapshots, no WebSocket. Every call is batched with that quota in mind.
- **The pattern is defined in `trade`** — `docs/pattern/DT.md` there is the single source (the
  BARES points : Base · Apex · Rejection · Echo · Sink). Never copy a pattern sheet here.
- **Recorded stats arrive to review** in `trade` ; the user validates or rejects each one, and a
  rejected one never comes back on a re-scan (`trade` skips what exists).
- **No trading** — the repo started as `mtr`, an autonomous short bot (TradeZero). That code is
  gone ; git history holds it. Live alerting (Telegram, while a DT forms) is a later phase that
  needs a paid real-time plan.

## Stack

| Layer   | Tech                                                         |
| ------- | ------------------------------------------------------------ |
| Runtime | Kotlin 2.4 / JVM 25 (toolchain)                              |
| HTTP    | Ktor client (CIO) + kotlinx-serialization                    |
| Config  | dotenv-kotlin (`.env` locally, Secret Manager in production) |
| Build   | Gradle (Kotlin DSL), Spotless (ktlint)                       |
| Tests   | kotlin-test on JUnit Platform                                |
| Image   | Docker (JDK 25 build → JRE 25 runtime), pushed to GHCR       |
| CI      | GitHub Actions (`spotlessCheck build`), Dependabot           |

## Structure

```
src/main/kotlin/ts/scanner/
  Main.kt               # entry point : the previous trading day, or --from <date>
  D1Job.kt              # one day : universe → bars → detectors → push → run report
  Universe.kt           # the daily cut down to the tickers worth their bars
  GusDetector.kt        # OPS off 1-min bars
  DoubleTopDetector.kt  # BARES off 1-min bars, legs as target ± acceptance bands
  SinkTracker.kt        # the Sink : the low of the first fall
  Detection.kt, Bar.kt  # Point, Band, Bar — the shared vocabulary
  ScannedStats.kt       # a detection in trade's shape (chart minute, four decimals)
  Trade.kt              # TradeClient port + HttpTradeClient (trade's /api/scanner/**)
  MarketData.kt         # MarketDataProvider port + MassiveProvider (REST)
  Pacer.kt              # 5 requests a minute, the free plan's quota
  Ssr.kt                # Reg SHO Rule 201, derived from prices
  Notifier.kt           # run reports — log only, or Telegram when configured
  Config.kt             # envOrNull : .env first, then the environment
src/test/resources/bars/  # real days of 1-min bars, the detectors' fixtures
```

Pure functions for every rule (no I/O), the I/O wired around them — the rules are tested without
the network.

## Commands

```bash
./gradlew run            # run the job
./gradlew test           # unit tests
./gradlew spotlessApply  # format
./gradlew spotlessCheck build
```

## Conventions

### Comments — the strict minimum

Code says *what* ; a comment is only worth its line when it says *why*, and only when the why
isn't obvious. Keep a non-obvious constraint (an API quirk, a value verified empirically, a
warning about what breaks). Drop anything restating the code or narrating history — git holds
the history, the issue holds the rationale. One or two lines ; KDoc says what a type is for and
what a caller must know.

### Kotlin

- Idiomatic Kotlin (data classes, sealed classes, extension functions), ktlint official style.
- **No wildcard imports.**
- Secrets never in code or in a committed file — `.env` (git-ignored) locally, `.env.example`
  documents the keys.
- **Never log PII.**

### Tests as documentation

- A class-level docstring : the area under test and the failure modes it protects against.
- Test names are full sentences describing the behaviour.
- Setup factories with sensible defaults, one scenario per test, realistic fixtures (a real
  ticker and real prices beat `"x"`).
- DT detection is tested on **real charts**, counter-examples included (a breakout through the
  Apex is not a double top).

### Commits

- Conventional Commits in **English**, **title only** — one line, < 72 chars, no body, no footer.
- **No reference to Claude** — no `Co-Authored-By`, no « Generated with », in commits, PR titles
  or descriptions.
- **Every commit is linked to an issue**, its number prefixing the scope :
  `feat(32/detector): …`, `chore(31/repo): …`. No issue → ask the user which one to use.
- **Default = suggest, don't execute** — never run `git add/commit/push/branch/tag/rebase` or
  `gh pr/issue` unless the user asks in the current turn.
- **`master` is protected** — every change goes through a PR whose CI `build` check is green,
  admins included ; no force push, no deletion. One commit per PR (amend + force-push the PR
  branch), and the user merges.

### Backlog

Issues live in this repo and on the platform board, **TickerStory Board** (project 2, owner
`jv3n`), shared with `trade`. A new issue goes on the board with **Status** and **Priority** set,
and carries a `prio:*` and a `scope:*` label. A PR references its issues with `Refs #N`, never
`Closes` / `Fixes`.

## Languages

| Where                                                    | Language    |
| -------------------------------------------------------- | ----------- |
| Talking to the user in the terminal                      | **French**  |
| Source code, comments, KDoc, test names, logs, config    | **English** |
| GitHub — commits, PRs, issues, comments                  | **English** |
| Docs — `README.md`, `.claude/`                           | **English** |

## Instructions for Claude

- **Line endings** — LF only, enforced by `.gitattributes` (`* text=auto eol=lf`) and
  `.editorconfig`. IntelliJ on Windows tends to bring CRLF back, which breaks ktlint ; never
  remove `.gitattributes`.
- **`gradlew` stays executable** (`100755` in git), or the Linux CI fails with `Permission
  denied` — `git update-index --chmod=+x gradlew`.
- **Dependabot wrapper PRs** regenerate `gradlew.bat` with other line endings : review them.
- **Builds and tests** — never run `./gradlew build/test` autonomously ; say what is worth
  running and let the user drive it, unless they ask in the current turn.
