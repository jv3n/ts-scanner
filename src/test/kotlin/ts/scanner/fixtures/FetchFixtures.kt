package ts.scanner.fixtures

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import ts.scanner.MassiveProvider
import ts.scanner.SessionBars
import java.io.File
import java.time.LocalDate

/** The free plan's quota : 5 requests a minute, so one every 12 s and a little margin. */
private const val PAUSE_MS = 12_500L

/**
 * Writes real days of 1-min bars into `src/test/resources/bars/` (#34) — a developer tool, run by
 * `./gradlew fetchFixtures -Pcases=TICKER:YYYY-MM-DD,…`. A fixture already there is kept : the
 * tests must not move under a re-fetch. Needs the Massive key of `.env`.
 */
fun main(args: Array<String>) =
    runBlocking {
        val dir = File("src/test/resources/bars").apply { mkdirs() }
        val cases =
            args.flatMap { it.split(",") }.filter { it.isNotBlank() }.map { case ->
                val (ticker, date) = case.trim().split(":")
                ticker.uppercase() to LocalDate.parse(date)
            }
        require(cases.isNotEmpty()) { "no case — -Pcases=TICKER:YYYY-MM-DD,…" }
        val massive = MassiveProvider.fromEnv()
        try {
            var first = true
            for ((ticker, date) in cases) {
                if (File(dir, BarFixture.fileOf(ticker, date.toString())).exists()) {
                    println("kept    $ticker $date")
                    continue
                }
                if (!first) delay(PAUSE_MS)
                first = false
                val bars = massive.getMinuteBars(ticker, date)
                delay(PAUSE_MS)
                val previousClose = massive.getPreviousClose(ticker, date)
                if (bars.isEmpty()) {
                    println("empty   $ticker $date — not a trading day for it ?")
                    continue
                }
                BarFixture.write(dir, SessionBars(ticker, date, previousClose, bars))
                println("written $ticker $date — ${bars.size} bars, previous close $previousClose")
            }
        } finally {
            massive.close()
        }
    }
