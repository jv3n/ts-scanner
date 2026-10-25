package ts.scanner

import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

/**
 * `ts-scanner` — the previous trading day, or every trading day from `--from <YYYY-MM-DD>` to
 * `--to` (both included ; the previous trading day without one), pushed to `trade` (`TRADE_URL`, `TRADE_API_TOKEN` — jv3n/trade#766). Exits non-zero
 * when a day fails, after reporting it to `trade`.
 */
fun main(args: Array<String>) {
    val today = LocalDate.now(NEW_YORK)
    val run = RunArgs.parse(args, today)
    val tradeUrl = requireNotNull(envOrNull("TRADE_URL")) { "TRADE_URL is missing — trade's base URL" }
    val token = requireNotNull(envOrNull("TRADE_API_TOKEN")) { "TRADE_API_TOKEN is missing — trade's scanner token" }
    val massive = MassiveProvider.fromEnv()
    val trade = HttpTradeClient(tradeUrl, token)
    val notifier = notifierFromEnv()
    val failed =
        runBlocking {
            try {
                val job = D1Job(PacedMarketData(massive, Pacer()), trade, Clock.systemUTC(), progress = ::println)
                val days =
                    when (run.from) {
                        null -> listOf(job.previousTradingDay(today))
                        else -> job.tradingDays(run.from, run.to ?: today.minusDays(1))
                    }
                var failures = 0
                for (day in days) {
                    runCatching { job.scan(day) }
                        .onSuccess { notifier.send(summary(it)) }
                        .onFailure {
                            failures++
                            notifier.send("ts-scanner: $day failed — ${it.message}")
                        }
                }
                failures > 0
            } finally {
                notifier.close()
                trade.close()
                massive.close()
            }
        }
    if (failed) exitProcess(1)
}

private fun summary(scan: DayScan): String {
    val skipped =
        scan.result.skipped
            .groupingBy { it.reason }
            .eachCount()
    val missed = if (scan.missed.isEmpty()) "" else " ; not read : ${scan.missed.joinToString()}"
    return "ts-scanner: ${scan.day} — ${scan.tickersScanned} tickers read, ${scan.gusFound} GUS, ${scan.dtFound} DT ; " +
        "${scan.result.created.size} created" + (if (skipped.isEmpty()) "" else ", skipped $skipped") + missed
}
