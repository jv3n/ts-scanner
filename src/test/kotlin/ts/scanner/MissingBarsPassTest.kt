package ts.scanner

import kotlinx.coroutines.runBlocking
import ts.scanner.fixtures.BarFixture
import java.io.IOException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bars of the stats that miss them (trade#784), fetched after the day's scan. What it protects :
 *
 * - each ticker-day `trade` lists is fetched and pushed with its bars ;
 * - an empty answer is a day Massive has nothing for : marked not found, so `trade` stops listing it ;
 * - a failed request marks **nothing** — a 429 or a timeout is no « none served », and the next run
 *   tries again — and the rest of the list still goes ;
 * - `trade` is asked for the run's limit, and an empty list costs no request to Massive.
 */
class MissingBarsPassTest {
    @Test
    fun `a missing day is fetched and pushed with its bars`() =
        runBlocking {
            val trade = FakeTrade(listOf(ZTG))

            val result = MissingBarsPass(market(), trade).run()

            val push = trade.pushed.single()
            assertEquals("ZTG" to "2026-10-16", push.ticker to push.tradingDate)
            assertTrue(push.bars.size > 300, "${push.bars.size} bars")
            assertEquals(1, result.filled)
        }

    @Test
    fun `a day Massive serves nothing for is marked not found`() =
        runBlocking {
            val trade = FakeTrade(listOf(DELISTED))

            val result = MissingBarsPass(market(), trade).run()

            assertEquals(listOf(DELISTED), trade.notFound)
            assertTrue(trade.pushed.isEmpty())
            assertEquals(1, result.notFound)
        }

    @Test
    fun `a failed request marks nothing, and the rest of the list still goes`() =
        runBlocking {
            val trade = FakeTrade(listOf(SGRX, ZTG))

            val result = MissingBarsPass(market(failing = mapOf("SGRX" to IOException("Massive answered 429"))), trade).run()

            assertTrue(trade.notFound.isEmpty())
            assertEquals(listOf("ZTG"), trade.pushed.map { it.ticker })
            assertEquals(listOf(Missed("SGRX", "Massive answered 429")), result.failed)
        }

    @Test
    fun `trade is asked for the run's limit, and nothing missing costs no request`() =
        runBlocking {
            val trade = FakeTrade(emptyList())
            val market = market()

            MissingBarsPass(market, trade, limit = 7).run()

            assertEquals(listOf(7), trade.limitsAsked)
            assertTrue(market.asked.isEmpty())
        }

    private fun market(failing: Map<String, Exception> = emptyMap()) =
        BarsOnly(
            bars = listOf("ZTG").associateWith { BarFixture.load(it, "2026-10-16").bars },
            failing = failing,
        )

    private companion object {
        val ZTG = DayTicker("2026-10-16", "ZTG")
        val SGRX = DayTicker("2026-10-16", "SGRX")
        val DELISTED = DayTicker("2026-10-16", "GONE")
    }
}

/** Massive, answering minute bars from memory — an unknown ticker gets an empty day. */
private class BarsOnly(
    private val bars: Map<String, List<Bar>>,
    private val failing: Map<String, Exception>,
) : MarketDataProvider {
    val asked = mutableListOf<String>()

    override suspend fun getMinuteBars(
        ticker: String,
        date: LocalDate,
    ): List<Bar> {
        asked += ticker
        failing[ticker]?.let { throw it }
        return bars[ticker].orEmpty()
    }

    override suspend fun getGroupedDaily(date: LocalDate) = error("not used by the pass")

    override suspend fun getPreviousClose(
        ticker: String,
        date: LocalDate,
    ) = error("not used by the pass")

    override suspend fun getReference(ticker: String) = error("not used by the pass")

    override suspend fun getShortInterest(tickers: List<String>) = error("not used by the pass")

    override suspend fun getReverseSplits(
        tickers: List<String>,
        from: LocalDate,
        to: LocalDate,
    ) = error("not used by the pass")

    override suspend fun getInheritedSsr(tickers: List<String>) = error("not used by the pass")
}

/** `trade`, listing [missing] and recording what it is sent. */
private class FakeTrade(
    private val missing: List<DayTicker>,
) : TradeClient {
    val limitsAsked = mutableListOf<Int>()
    val pushed = mutableListOf<DayBarsPush>()
    val notFound = mutableListOf<DayTicker>()

    override suspend fun missingBars(limit: Int): List<DayTicker> {
        limitsAsked += limit
        return missing
    }

    override suspend fun pushBars(push: DayBarsPush) {
        pushed += push
    }

    override suspend fun markBarsNotFound(day: DayTicker) {
        notFound += day
    }

    override suspend fun pushStats(request: ScannedStatsRequest) = error("not used by the pass")

    override suspend fun reportRun(report: ScannerRunReport) = error("not used by the pass")
}
