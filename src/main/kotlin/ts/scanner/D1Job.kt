package ts.scanner

import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/** What one day's scan found and pushed — and the tickers whose bars could not be read. */
data class DayScan(
    val day: LocalDate,
    val tickersScanned: Int,
    val gusFound: Int,
    val dtFound: Int,
    val result: ScannedStatsResult,
    val missed: List<Missed> = emptyList(),
)

/** A ticker whose bars could not be read, and why — the why stays in the log, `trade` gets the ticker. */
data class Missed(
    val ticker: String,
    val reason: String,
) {
    override fun toString() = "$ticker ($reason)"
}

/**
 * The D-1 job (#35) : for a trading day, cut the market down to its gappers and wide ranges, read
 * each one's 1-min bars, run both detectors, push what they found to `trade`, then report the run
 * — a failed run too, so Today can say the scanner did not get through. A ticker whose bars fail
 * to load is named in the report and the rest of the day still goes : twelve minutes of requests
 * at 5 a minute are a wide window to ask for none to fail. Only a day that reads no ticker fails.
 *
 * A trading day is one Massive publishes grouped bars for : weekdays, minus the market holidays,
 * read off the data rather than a calendar to maintain. Each day needs the session before it for
 * the previous closes ; they are kept, so a backfill reads each session once.
 */
class D1Job(
    private val market: MarketDataProvider,
    private val trade: TradeClient,
    private val clock: Clock,
    private val universeRules: UniverseRules = UniverseRules(),
    private val gusRules: GusRules = GusRules(),
    private val doubleTopRules: DoubleTopRules = DoubleTopRules(),
    /** Where the progress goes, line by line — a day is a quarter of an hour of reading (#42). */
    private val progress: (String) -> Unit = {},
) {
    private val sessions = mutableMapOf<LocalDate, Map<String, DailyBar>?>()

    /** The last trading day before [today] — what a morning run scans. */
    suspend fun previousTradingDay(today: LocalDate): LocalDate = sessionBefore(today).first

    /** Every trading day from [from] to [to], both included — one day alone, a range, a backfill. */
    suspend fun tradingDays(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        val days = mutableListOf<LocalDate>()
        var date = from
        while (date <= to) {
            if (date.isWeekday && session(date) != null) days += date
            date = date.plusDays(1)
        }
        return days
    }

    /** Scans [day] and pushes it ; the run is reported either way, and a failure is thrown on. */
    suspend fun scan(day: LocalDate): DayScan {
        val startedAt = clock.instant()
        var scanned = 0
        var gus = 0
        var dt = 0
        try {
            val session = session(day) ?: error("$day is not a trading day")
            val (previousDay, previous) = sessionBefore(day)
            val cut = universe(session, previous, universeRules)
            // A reverse split since the previous session makes its close a pre-split price : a fake
            // gap of thousands of percent (PASW 23/10, +4 415 %) — the sheet's criterion 8.
            val split = if (cut.isEmpty()) emptySet() else market.getReverseSplits(cut.map { it.ticker }, previousDay.plusDays(1), day)
            val candidates = cut.filterNot { it.ticker in split }
            val splitNote = if (split.isEmpty()) "" else " ; left out for a reverse split : ${split.sorted().joinToString()}"
            progress("$day — ${candidates.size} tickers to read, ~${minutesFor(candidates.size)} min$splitNote")
            val stats = mutableListOf<ScannedStat>()
            val chartBars = mutableListOf<TickerBars>()
            val missed = mutableListOf<Missed>()
            for ((index, candidate) in candidates.withIndex()) {
                val step = "[${index + 1}/${candidates.size}] ${candidate.ticker}"
                val bars =
                    try {
                        market.getMinuteBars(candidate.ticker, day)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val reason = failure.message ?: failure::class.simpleName.orEmpty()
                        missed += Missed(candidate.ticker, reason)
                        progress("$step — not read ($reason)")
                        continue
                    }
                scanned++
                val gusDetector = GusDetector(candidate.previousClose, gusRules)
                val topDetector = DoubleTopDetector(doubleTopRules)
                bars.forEach {
                    gusDetector.feed(it)
                    topDetector.feed(it)
                }
                val found = mutableListOf<String>()
                gusDetector.result()?.let {
                    stats += it.toScannedStat(candidate.ticker)
                    gus++
                    found += "GUS"
                }
                topDetector.result()?.let {
                    stats += it.toScannedStat(candidate.ticker, candidate.dayVolume)
                    dt++
                    found += "DT"
                }
                if (found.isNotEmpty()) chartBars += bars.forChart(candidate.ticker, day)
                progress("$step — ${found.joinToString().ifEmpty { "nothing" }}")
            }
            if (candidates.isNotEmpty() && scanned == 0) error("no ticker read — ${missed.joinToString()}")
            val result = trade.pushStats(ScannedStatsRequest(day.toString(), stats, chartBars))
            trade.reportRun(ScannerRunReport(day.toString(), "$startedAt", "${clock.instant()}", scanned, gus, dt, missedTickers = missed.map { it.ticker }))
            val took = Duration.between(startedAt, clock.instant()).toMinutes()
            progress("$day — done in $took min : $gus GUS, $dt DT, ${result.created.size} created, ${result.skipped.size} skipped")
            return DayScan(day, scanned, gus, dt, result, missed)
        } catch (failure: Exception) {
            val reason = failure.message ?: failure::class.simpleName.orEmpty()
            runCatching {
                trade.reportRun(ScannerRunReport(day.toString(), "$startedAt", null, scanned, gus, dt, reason.take(ERROR_MAX)))
            }
            throw failure
        }
    }

    /** The trading session before [day], walking back over weekends and holidays. */
    private suspend fun sessionBefore(day: LocalDate): Pair<LocalDate, Map<String, DailyBar>> {
        var date = day.minusDays(1)
        repeat(MAX_PROBES) {
            if (date.isWeekday) session(date)?.let { return date to it }
            date = date.minusDays(1)
        }
        error("no trading session in the $MAX_PROBES days before $day")
    }

    /** One request a ticker, at the free plan's five a minute. */
    private fun minutesFor(tickers: Int) = (tickers + 4) / 5

    private suspend fun session(date: LocalDate): Map<String, DailyBar>? = sessions.getOrPut(date) { market.getGroupedDaily(date) }

    private val LocalDate.isWeekday get() = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY

    private companion object {
        /** A weekend and the longest run of market holidays still fit. */
        const val MAX_PROBES = 7

        /** `trade` keeps 2 000 characters of an error. */
        const val ERROR_MAX = 2000
    }
}
