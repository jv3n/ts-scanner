package ts.scanner

import kotlinx.coroutines.runBlocking
import ts.scanner.fixtures.BarFixture
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
 * - a known day — 16/10, ZTG and SGRX — comes out as the GUS and the double top the detectors read,
 *   pushed in `trade`'s shape, and the run is reported with its counts ;
 * - the trading day is read off the data : a Monday scans Friday without asking for the weekend, the
 *   morning after a holiday scans the session before it ;
 * - a ticker whose bars fail is named in the run's report, and the rest of the day is still pushed ;
 * - a day that reads no ticker is a failed run, still reported to `trade` with its reason.
 */
class D1JobTest {
    @Test
    fun `16-10 — ZTG's GUS and double top and SGRX's GUS are pushed, the run reported with its counts`() =
        runBlocking {
            val trade = RecordingTrade()

            val scan = job(market(), trade).scan(DAY)

            val pushed = trade.pushed.single()
            assertEquals("2026-10-16", pushed.tradingDate)
            assertEquals(setOf("ZTG GUS", "ZTG DT", "SGRX GUS"), pushed.stats.map { "${it.ticker} ${it.pattern}" }.toSet())
            assertEquals(Triple(2, 2, 1), Triple(scan.tickersScanned, scan.gusFound, scan.dtFound))
            val run = trade.runs.single()
            assertEquals(Triple(2, 2, 1), Triple(run.tickersScanned, run.gusFound, run.dtFound))
            assertNull(run.error)
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

            val days = job(market).tradingDays(LocalDate.of(2026, 9, 3), today = LocalDate.of(2026, 9, 9))

            assertEquals(listOf(3, 4, 8).map { LocalDate.of(2026, 9, it) }, days)
        }

    @Test
    fun `a ticker whose bars fail is named in the report, the rest of the day is still pushed`() =
        runBlocking {
            val trade = RecordingTrade()
            val market = market(failing = mapOf("SGRX" to IOException("Massive answered 500")))

            val scan = job(market, trade).scan(DAY)

            assertEquals(
                setOf("ZTG GUS", "ZTG DT"),
                trade.pushed
                    .single()
                    .stats
                    .map { "${it.ticker} ${it.pattern}" }
                    .toSet(),
            )
            assertEquals(listOf("SGRX (Massive answered 500)"), scan.missed)
            val run = trade.runs.single()
            assertEquals("1 of 2 tickers not read — SGRX (Massive answered 500)", run.error)
            assertTrue(run.finishedAt != null)
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
    ) = D1Job(market, trade, Clock.fixed(Instant.parse("2026-10-19T10:10:00Z"), ZoneOffset.UTC))

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
