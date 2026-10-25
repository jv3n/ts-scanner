package ts.scanner

import ts.scanner.fixtures.BarFixture
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The double top detector on real days of 1-min bars, the DTs of the user's stats sheet (#34), with
 * the bands settled with the user : A 45–60 %, R 18–22 %, E 18–22 %, each leg within its time.
 *
 * What it protects : the Apex is the top of the whole push and the Rejection the low of the whole
 * pullback (WFF read a 3.10 Apex under a 3.43 one) ; a leg out of its band is no double top, even
 * one the sheet records — the detector misses those on purpose ; an Echo above the Apex counts only
 * once the price falls back under the Rejection.
 *
 * Times are Massive's, the minute a bar opens : the sheet reads the one after, its end.
 */
class DoubleTopDetectorTest {
    @Test
    fun `KNDI — the sheet's double top, point by point`() {
        val top = detect("KNDI", "2026-09-28")

        assertEquals(Point(time("10:52"), 0.67), top.base)
        assertEquals(Point(time("11:04"), 1.03), top.apex)
        // Massive's low is 0.81 ; the chart read 0.80.
        assertEquals(Point(time("11:06"), 0.81), top.rejection)
        // The sheet's 1.02 is +26 % off the Rejection, out of band : the first test of the Apex is.
        assertEquals(Point(time("11:12"), 0.98), top.echo)
    }

    @Test
    fun `MNDR — the sheet's Apex and Rejection, to the minute of the chart`() {
        val top = detect("MNDR", "2026-10-12")

        assertEquals(Point(time("10:25"), 5.28), top.apex)
        assertEquals(Point(time("10:30"), 4.29), top.rejection)
    }

    @Test
    fun `DRMA — the push measured from its real start, inside the band the sheet's base misses`() {
        // The sheet starts the push at 0.86 : +37 %, out of band. Its swing low of 09:50 gives +51 %.
        val top = detect("DRMA", "2026-10-15")

        assertEquals(Point(time("09:50"), 0.7823), top.base)
        assertEquals(Point(time("10:31"), 1.18), top.apex)
        assertEquals(Point(time("10:39"), 0.95), top.rejection)
        assertEquals(Point(time("10:53"), 1.13), top.echo)
    }

    @Test
    fun `the sink is the low of the first fall after the Echo, read once the day is over`() {
        val top = detect("DRMA", "2026-10-15")

        assertEquals(Point(time("12:39"), 0.77), top.sink)
    }

    @Test
    fun `the Apex is the top of the whole push — WFF's 3·43, which takes the rejection out of band`() {
        // Read under the 3.10 swing, R was 21.6 % ; under the real 3.43, it is 29 % — the sheet's own.
        assertNull(run("WFF", "2026-10-14"))
    }

    @Test
    fun `a rejection under eighteen percent is a breath, not a double top — CPHI, NCT`() {
        assertNull(run("CPHI", "2026-10-08"))
        assertNull(run("NCT", "2026-10-08"))
    }

    @Test
    fun `an extension past sixty percent is out of band — ZEO, SPHL`() {
        assertNull(run("ZEO", "2026-09-22"))
        assertNull(run("SPHL", "2026-10-21"))
    }

    @Test
    fun `a push that takes all morning is no push — JYD, from 04·04 to 11·07`() {
        assertNull(run("JYD", "2026-10-13"))
        // Only the time keeps it out : with no limit, its legs are in band.
        assertNotNull(run("JYD", "2026-10-13", DoubleTopRules(maxExtensionMinutes = 24 * 60)))
    }

    @Test
    fun `under the sheet's bands an echo cannot clear the Apex — R 18 % at least, E 22 % at most`() {
        // 0.82 × 1.22 ≈ 1.0004 : a 22 % echo off an 18 % rejection lands on the Apex itself.
        val rules = DoubleTopRules()
        val highest = (1 - (rules.rejection.target - rules.rejection.acceptance) / 100) * (1 + (rules.echo.target + rules.echo.acceptance) / 100)

        assertEquals(1.0004, highest, 0.0001)
    }

    @Test
    fun `with a wider echo band, an echo above the Apex is kept once the price falls back under the Rejection`() {
        // SPHL's shape on the sheet : an Echo that takes the Apex, then the fall.
        val top =
            feed(
                bar("10:00", 2.00, 2.00),
                bar("10:20", 3.05, 2.90),
                bar("10:25", 2.95, 2.45),
                bar("10:35", 2.70, 2.55),
                bar("10:40", 3.10, 2.95),
                bar("10:45", 2.90, 2.70),
                bar("10:50", 2.60, 2.40),
            )

        assertEquals(Point(time("10:40"), 3.10), top?.echo)
    }

    @Test
    fun `with a wider echo band, an echo above the Apex that never falls back is a breakout`() {
        val top =
            feed(
                bar("10:00", 2.00, 2.00),
                bar("10:20", 3.05, 2.90),
                bar("10:25", 2.95, 2.45),
                bar("10:35", 2.70, 2.55),
                bar("10:40", 3.10, 2.95),
                bar("10:45", 2.95, 2.80),
                bar("10:50", 2.98, 2.85),
            )

        assertNull(top)
    }

    private fun detect(
        ticker: String,
        date: String,
    ): DoubleTop = assertNotNull(run(ticker, date), "$ticker on $date not read as a double top")

    private fun run(
        ticker: String,
        date: String,
        rules: DoubleTopRules = DoubleTopRules(),
    ): DoubleTop? {
        val session = BarFixture.load(ticker, date)
        return DoubleTopDetector(rules).also { d -> session.bars.forEach(d::feed) }.result()
    }

    @Test
    fun `an echo re-pointed past its time window is dropped, even back under the Rejection`() {
        // The Echo of 10:40 is first seen in band ; a higher high at 11:40, still in band, is 75 min
        // after the Rejection — past the 60-minute window : no double top, whatever falls after.
        val top =
            feed(
                bar("10:00", 2.00, 2.00),
                bar("10:20", 3.05, 2.90),
                bar("10:25", 2.95, 2.45),
                bar("10:35", 2.70, 2.55),
                bar("10:40", 3.10, 2.95),
                bar("10:45", 2.90, 2.70),
                bar("11:00", 2.95, 2.85),
                bar("11:40", 3.15, 3.00),
                bar("11:50", 3.00, 2.40),
            )

        assertNull(top)
    }

    /** E 20–30 % : room for an Echo above the Apex, which the sheet's bands leave none of. */
    private fun feed(vararg bars: Bar): DoubleTop? = DoubleTopDetector(DoubleTopRules(echo = Band(25.0, 5.0))).also { d -> bars.forEach(d::feed) }.result()

    private fun bar(
        time: String,
        high: Double,
        low: Double,
    ) = Bar(time(time), open = low, high = high, low = low, close = low, volume = 10_000)

    private fun time(value: String) = LocalTime.parse(value)
}
