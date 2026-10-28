package ts.scanner

import kotlinx.coroutines.runBlocking
import ts.scanner.fixtures.BarFixture
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The D-1 job end to end (#35), against a Massive stubbed with real fixtures and a `trade` that
 * records what it is sent. What it protects :
 *
 * - a known day — 16/10, ZTG and SGRX — comes out as the GUSs and the double top the detectors read,
 *   pushed in `trade`'s shape, and the run is reported with its counts ;
 * - the trading day is read off the data : a Monday scans Friday without asking for the weekend, the
 *   morning after a holiday scans the session before it ;
 * - a ticker whose bars fail is named in the run's report, and the rest of the day is still pushed ;
 * - a day that reads no ticker is a failed run, still reported to `trade` with its reason.
 */
class D1JobTest {
    @Test
    fun `16-10 — ZTG's GUS, SGRX's GUS and double top are pushed, the run reported with its counts`() =
        runBlocking {
            val trade = RecordingTrade()

            val scan = job(market(), trade).scan(DAY)

            val pushed = trade.pushed.single()
            assertEquals("2026-10-16", pushed.tradingDate)
            assertEquals(setOf("ZTG GUS", "SGRX GUS", "SGRX DT"), pushed.stats.map { "${it.ticker} ${it.pattern}" }.toSet())
            assertEquals(Triple(2, 2, 1), Triple(scan.tickersScanned, scan.gusFound, scan.dtFound))
            val run = trade.runs.single()
            assertEquals(Triple(2, 2, 1), Triple(run.tickersScanned, run.gusFound, run.dtFound))
            assertNull(run.error)
        }

    @Test
    fun `each ticker a stat was found on goes over with its day of bars, the quiet one without`() =
        runBlocking {
            val trade = RecordingTrade()

            job(market(), trade).scan(DAY)

            val bars =
                trade.pushed
                    .single()
                    .bars
                    .associateBy { it.ticker }
            assertEquals(setOf("ZTG", "SGRX"), bars.keys)
            val ztg = bars.getValue("ZTG").bars
            // The extended day only, as instants : 04:00 New York is 08:00Z in October.
            assertTrue(ztg.all { it.time >= "2026-10-16T08:00:00Z" && it.time < "2026-10-17T00:00:00Z" }, "${ztg.first()}")
            assertEquals(ztg.sortedBy { it.time }, ztg)
            assertTrue(ztg.size > 300, "${ztg.size} bars")
        }

    @Test
    fun `a bar out of the extended day, or with its close outside its range, stays out of the chart`() {
        val day = LocalDate.of(2026, 10, 16)
        val bars =
            listOf(
                Bar(LocalTime.of(3, 59), 1.0, 1.0, 1.0, 1.0, 10),
                Bar(LocalTime.of(9, 30), 1.1, 1.14, 1.02, 1.04, 182_340),
                Bar(LocalTime.of(9, 31), 1.04, 1.1, 1.03, 1.2, 96_015),
                Bar(LocalTime.of(20, 0), 1.0, 1.0, 1.0, 1.0, 10),
            )

        val chart = bars.forChart("ZTG", day)

        assertEquals(listOf("2026-10-16T13:30:00Z"), chart.bars.map { it.time })
    }

    @Test
    fun `ZTG's GUS goes over as the sheet reads it — push at 09·40, the first fall's 0·75`() =
        runBlocking {
            val trade = RecordingTrade()

            job(market(), trade).scan(DAY)

            val ztg =
                trade.pushed
                    .single()
                    .stats
                    .single { it.ticker == "ZTG" && it.pattern == "GUS" }
            assertEquals(0.623, ztg.previousClose)
            assertEquals(1.1, ztg.pushOpenPrice)
            assertEquals("09:40", ztg.pushOpenTime)
            assertEquals(0.75, ztg.gusDropPrice)
        }

    @Test
    fun `the run says how many tickers it will read, then each one and what it found`() =
        runBlocking {
            val lines = mutableListOf<String>()
            val market = market(failing = mapOf("ZTG" to IOException("Massive answered 500")))

            D1Job(market, RecordingTrade(), CLOCK, progress = { lines += it }).scan(DAY)

            assertEquals("2026-10-16 — 2 tickers to read, ~1 min", lines.first())
            assertTrue(lines.any { it.endsWith("SGRX — GUS, DT") }, "$lines")
            assertTrue(lines.any { it.endsWith("ZTG — not read (Massive answered 500)") }, "$lines")
            assertTrue(lines.drop(1).dropLast(1).all { it.startsWith("[") }, "$lines")
            assertEquals("2026-10-16 — done in 0 min : 1 GUS, 1 DT, 2 created, 0 skipped", lines.last())
        }

    @Test
    fun `a ticker out of the universe is never fetched`() =
        runBlocking {
            val market = market()

            job(market, RecordingTrade()).scan(DAY)

            assertEquals(setOf("ZTG", "SGRX"), market.barsAskedFor)
        }

    @Test
    fun `a reverse split since the previous session is left out — its close is a pre-split price`() =
        runBlocking {
            val market = market(reverseSplits = setOf("SGRX"))
            val trade = RecordingTrade()

            job(market, trade).scan(DAY)

            assertEquals(setOf("ZTG"), market.barsAskedFor)
            assertEquals(listOf(DAY to DAY), market.splitsAskedFor)
            assertTrue(
                trade.pushed
                    .single()
                    .stats
                    .none { it.ticker == "SGRX" },
            )
        }

    @Test
    fun `a Monday scans Friday, without asking for the weekend`() =
        runBlocking {
            val market = market()

            val day = job(market).previousTradingDay(LocalDate.of(2026, 10, 19))

            assertEquals(DAY, day)
            assertEquals(listOf(DAY), market.daysAskedFor)
        }

    @Test
    fun `the morning after a holiday scans the session before it`() =
        runBlocking {
            // Tuesday 08/09, the morning after Labor Day : Massive publishes nothing for Monday.
            val market = StubMarket(sessions = mapOf(LocalDate.of(2026, 9, 4) to mapOf("ZTG" to DailyBar(1.0, 1.0, 1.0, 1.0, 0.0))))

            assertEquals(LocalDate.of(2026, 9, 4), job(market).previousTradingDay(LocalDate.of(2026, 9, 8)))
        }

    @Test
    fun `a backfill walks every trading day up to yesterday, holidays and weekends left out`() =
        runBlocking {
            val open = mapOf("ZTG" to DailyBar(1.0, 1.0, 1.0, 1.0, 0.0))
            val market = StubMarket(sessions = listOf(3, 4, 8).associate { LocalDate.of(2026, 9, it) to open })

            val days = job(market).tradingDays(LocalDate.of(2026, 9, 3), to = LocalDate.of(2026, 9, 8))

            assertEquals(listOf(3, 4, 8).map { LocalDate.of(2026, 9, it) }, days)
        }

    @Test
    fun `a ticker whose bars fail is reported as missed, not as an error — the rest of the day is still pushed`() =
        runBlocking {
            val trade = RecordingTrade()
            val market = market(failing = mapOf("ZTG" to IOException("Massive answered 500")))

            val scan = job(market, trade).scan(DAY)

            assertEquals(
                setOf("SGRX GUS", "SGRX DT"),
                trade.pushed
                    .single()
                    .stats
                    .map { "${it.ticker} ${it.pattern}" }
                    .toSet(),
            )
            assertEquals(listOf(Missed("ZTG", "Massive answered 500")), scan.missed)
            val run = trade.runs.single()
            // A partial run is not a failed one : Today reads `error` as amber.
            assertNull(run.error)
            assertEquals(listOf("ZTG"), run.missedTickers)
            assertTrue(run.finishedAt != null)
        }

    @Test
    fun `one past day is scanned alone — the same day at both ends`() =
        runBlocking {
            val open = mapOf("ZTG" to DailyBar(1.0, 1.0, 1.0, 1.0, 0.0))
            val market = StubMarket(sessions = listOf(3, 4, 8).associate { LocalDate.of(2026, 9, it) to open })
            val day = LocalDate.of(2026, 9, 4)

            assertEquals(listOf(day), job(market).tradingDays(day, to = day))
        }

    @Test
    fun `a day that reads no ticker is a failed run, still reported to trade with its reason`() =
        runBlocking {
            val trade = RecordingTrade()
            val throttled = IOException("Massive answered 429")
            val market = market(failing = mapOf("ZTG" to throttled, "SGRX" to throttled))

            assertFailsWith<IllegalStateException> { job(market, trade).scan(DAY) }

            val run = trade.runs.single()
            assertTrue(run.error!!.contains("429"))
            assertNull(run.finishedAt)
            assertTrue(trade.pushed.isEmpty())
        }

    private fun job(
        market: MarketDataProvider,
        trade: TradeClient = RecordingTrade(),
    ) = D1Job(market, trade, CLOCK)

    /** 16/10 from the fixtures, a quiet ticker beside them, and the closes of 15/10 they gapped from. */
    private fun market(
        failing: Map<String, Exception> = emptyMap(),
        reverseSplits: Set<String> = emptySet(),
    ): StubMarket {
        val fixtures = listOf("ZTG", "SGRX").map { BarFixture.load(it, DAY.toString()) }
        val session = fixtures.associate { it.ticker to it.daily() } + ("QUIET" to DailyBar(5.0, 5.1, 4.95, 5.0, 2_000_000.0))
        val previous = fixtures.associate { it.ticker to DailyBar(0.0, 0.0, 0.0, it.previousClose!!, 0.0) } + ("QUIET" to DailyBar(5.0, 5.0, 5.0, 5.0, 0.0))
        return StubMarket(
            sessions = mapOf(DAY to session, DAY.minusDays(1) to previous),
            bars = fixtures.associate { it.ticker to it.bars },
            failing = failing,
            reverseSplits = reverseSplits,
        )
    }

    private fun SessionBars.daily(): DailyBar {
        val regular = bars.filter { it.time >= MARKET_OPEN && it.time < MARKET_CLOSE }
        return DailyBar(regular.first().open, regular.maxOf { it.high }, regular.minOf { it.low }, regular.last().close, bars.sumOf { it.volume }.toDouble())
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 10, 16)
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-19T10:10:00Z"), ZoneOffset.UTC)
    }
}

/** Massive, answered from memory : a date it has no session for was not a trading day. */
private class StubMarket(
    private val sessions: Map<LocalDate, Map<String, DailyBar>>,
    private val bars: Map<String, List<Bar>> = emptyMap(),
    private val failing: Map<String, Exception> = emptyMap(),
    private val reverseSplits: Set<String> = emptySet(),
) : MarketDataProvider {
    val daysAskedFor = mutableListOf<LocalDate>()
    val splitsAskedFor = mutableListOf<Pair<LocalDate, LocalDate>>()
    val barsAskedFor = mutableSetOf<String>()

    override suspend fun getGroupedDaily(date: LocalDate): Map<String, DailyBar>? {
        daysAskedFor += date
        return sessions[date]
    }

    override suspend fun getMinuteBars(
        ticker: String,
        date: LocalDate,
    ): List<Bar> {
        failing[ticker]?.let { throw it }
        barsAskedFor += ticker
        return bars[ticker].orEmpty()
    }

    override suspend fun getPreviousClose(
        ticker: String,
        date: LocalDate,
    ) = error("not used by the job")

    override suspend fun getReference(ticker: String) = error("not used by the job")

    override suspend fun getShortInterest(tickers: List<String>) = error("not used by the job")

    override suspend fun getReverseSplits(
        tickers: List<String>,
        from: LocalDate,
        to: LocalDate,
    ): Set<String> {
        splitsAskedFor += from to to
        return reverseSplits.intersect(tickers.toSet())
    }

    override suspend fun getInheritedSsr(tickers: List<String>) = error("not used by the job")
}

/** `trade`, recording what it is sent and creating everything. */
private class RecordingTrade : TradeClient {
    val pushed = mutableListOf<ScannedStatsRequest>()
    val runs = mutableListOf<ScannerRunReport>()

    override suspend fun pushStats(request: ScannedStatsRequest): ScannedStatsResult {
        pushed += request
        return ScannedStatsResult(request.stats.map { ScannedStatCreated(it.ticker, it.pattern) }, emptyList())
    }

    override suspend fun reportRun(report: ScannerRunReport) {
        runs += report
    }
}
