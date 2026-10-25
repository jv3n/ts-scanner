package ts.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cut from the whole market to the tickers worth their bars (#35). The daily bars hold neither
 * the premarket high nor its range, so the cut is wide and the detectors decide ; what it protects
 * is the opposite failure — a real GUS or DT never fetched.
 */
class UniverseTest {
    @Test
    fun `a gapper is kept on its session high over the previous close`() {
        // GLND 21/09 : closed 1.20, high 3.39 — whatever its 3.72 PM high.
        val kept = universe(mapOf("GLND" to day(open = 3.015, high = 3.39, low = 2.65)), mapOf("GLND" to close(1.20)))

        assertEquals(listOf("GLND"), kept.map { it.ticker })
        assertEquals(1.20, kept.single().previousClose)
    }

    @Test
    fun `a GUS that fades from its premarket is still kept — gapped 45 %, opened 25 % up`() {
        // Previous close 2.00, PM high 2.90 : a GUS. It opens at 2.50, tops the session at 2.55
        // (+27.5 %) and ranges 34 % : what the daily bar shows of it.
        val kept = universe(mapOf("FADE" to day(open = 2.50, high = 2.55, low = 1.90)), mapOf("FADE" to close(2.00)))

        assertEquals(listOf("FADE"), kept.map { it.ticker })
    }

    @Test
    fun `a wide session is kept for its double top, with little gap`() {
        // WFF's 14/10 session, 2.05 to 3.43, off a 2.80 close : +22 % of gap, +67 % of range.
        val kept = universe(mapOf("WFF" to day(open = 2.10, high = 3.43, low = 2.05)), mapOf("WFF" to close(2.80)))

        assertEquals(listOf("WFF"), kept.map { it.ticker })
    }

    @Test
    fun `a quiet session, a stock out of the price range or a suffixed ticker is left out`() {
        val session =
            mapOf(
                "AAPL" to day(open = 9.0, high = 9.3, low = 8.9),
                "BIGG" to day(open = 12.0, high = 20.0, low = 11.5),
                "ABC.WS" to day(open = 1.0, high = 2.0, low = 0.9),
            )
        val previous = session.mapValues { close(it.value.open * 0.9) }

        assertEquals(emptyList(), universe(session, previous))
    }

    @Test
    fun `a ticker without a previous session is left out — no gap to read`() {
        assertEquals(emptyList(), universe(mapOf("NEWCO" to day(open = 4.0, high = 8.0, low = 3.9)), emptyMap()))
    }

    @Test
    fun `past the day's ceiling, the widest moves go first`() {
        val session = mapOf("ONE" to day(1.0, high = 1.5, low = 1.0), "TWO" to day(1.0, high = 3.0, low = 1.0))
        val previous = mapOf("ONE" to close(1.0), "TWO" to close(1.0))

        assertEquals(listOf("TWO"), universe(session, previous, UniverseRules(maxTickers = 1)).map { it.ticker })
    }

    private fun day(
        open: Double,
        high: Double,
        low: Double,
    ) = DailyBar(open = open, high = high, low = low, close = open, volume = 1_000_000.0)

    private fun close(price: Double) = DailyBar(price, price, price, price, 1_000_000.0)
}
