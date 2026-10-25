package ts.scanner

import ts.scanner.fixtures.BarFixture
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The double top detector on real days of 1-min bars, the DTs of the user's stats sheet (#34), with
 * the loose bands settled with the user (#45) : A 40–200 %, R 15–32 %, E 15–40 %, each leg within
 * its time, the session only — loose on purpose, the review rejects with a reason.
 *
 * What it protects : the Apex is the top of the whole push and the Rejection the low of the whole
 * pullback ; until the price breaks under the Rejection, the Echo is the highest test of the Apex ;
 * an Echo above the Apex — the sheet's fake-out — counts once the price falls back under the
 * Rejection, and only up to 6 % past the Apex : further, a breakout. The first double top of the day
 * wins — on MNDR and WFF an earlier one than the sheet's.
 *
 * Times are Massive's, the minute a bar opens : the sheet reads the one after, its end.
 */
class DoubleTopDetectorTest {
    @Test
    fun `SPHL — the Echo that took the Apex by 4 %, then the fall — the sheet's double top, point by point`() {
        val top = detect("SPHL", "2026-10-21")

        assertEquals(Point(time("09:30"), 2.87), top.base)
        assertEquals(Point(time("09:41"), 5.6999), top.apex)
        assertEquals(Point(time("09:46"), 4.56), top.rejection)
        // 5.55 at 09:49 tested the Apex first ; the price had not broken 4.56 yet, so 5.94 is the Echo.
        assertEquals(Point(time("09:56"), 5.94), top.echo)
        assertEquals(Point(time("10:13"), 3.66), top.sink)
    }

    @Test
    fun `KNDI — the sheet's double top, point by point`() {
        val top = detect("KNDI", "2026-09-28")

        assertEquals(Point(time("10:52"), 0.67), top.base)
        assertEquals(Point(time("11:04"), 1.03), top.apex)
        // Massive's low is 0.81 ; the chart read 0.80.
        assertEquals(Point(time("11:06"), 0.81), top.rejection)
        assertEquals(Point(time("11:15"), 1.02), top.echo)
    }

    @Test
    fun `DRMA — the sheet's Apex, Rejection and Echo, and the sink read once the day is over`() {
        val top = detect("DRMA", "2026-10-15")

        assertEquals(Point(time("10:31"), 1.18), top.apex)
        assertEquals(Point(time("10:39"), 0.95), top.rejection)
        assertEquals(Point(time("10:53"), 1.13), top.echo)
        assertEquals(Point(time("12:39"), 0.77), top.sink)
    }

    @Test
    fun `the first double top of the day wins — MNDR reads the 09·36 one, before the sheet's 10·25`() {
        val top = detect("MNDR", "2026-10-12")

        assertEquals(Point(time("09:36"), 3.55), top.apex)
        assertEquals(Point(time("09:38"), 3.0), top.rejection)
    }

    @Test
    fun `the extended hours are left out, Base included — MNDR's 07·12 double top is not read`() {
        val top = detect("MNDR", "2026-10-12")

        assertEquals(Point(time("09:30"), 2.3), top.base)
    }

    @Test
    fun `a rejection under fifteen percent is a breath, not a double top — CPHI`() {
        assertNull(run("CPHI", "2026-10-08"))
    }

    @Test
    fun `an echo above the Apex is kept once the price falls back under the Rejection`() {
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
    fun `an echo above the Apex that never falls back is a breakout`() {
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

    @Test
    fun `an echo more than six percent past the Apex is a breakout, even back under the Rejection`() {
        // 3.05 → 3.10 is the fake-out ; 3.30 clears the Apex by 8 % : the move broke out.
        val top =
            feed(
                bar("10:00", 2.00, 2.00),
                bar("10:20", 3.05, 2.90),
                bar("10:25", 2.95, 2.45),
                bar("10:35", 2.70, 2.55),
                bar("10:40", 3.10, 2.95),
                bar("10:42", 3.30, 3.05),
                bar("10:50", 2.60, 2.40),
            )

        assertNull(top)
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

    @Test
    fun `a push that takes too long is no push — the time window alone keeps it out`() {
        val bars =
            arrayOf(
                bar("10:00", 2.00, 2.00),
                bar("11:30", 3.05, 2.90),
                bar("11:35", 2.95, 2.45),
                bar("11:45", 2.70, 2.55),
                bar("11:50", 2.95, 2.80),
                bar("11:55", 2.60, 2.30),
            )

        assertNull(feed(*bars))
        assertNotNull(feed(*bars, rules = DoubleTopRules(maxExtensionMinutes = 24 * 60)))
    }

    private fun detect(
        ticker: String,
        date: String,
    ): DoubleTop = assertNotNull(run(ticker, date), "$ticker on $date not read as a double top")

    private fun run(
        ticker: String,
        date: String,
    ): DoubleTop? {
        val session = BarFixture.load(ticker, date)
        return DoubleTopDetector().also { d -> session.bars.forEach(d::feed) }.result()
    }

    private fun feed(
        vararg bars: Bar,
        rules: DoubleTopRules = DoubleTopRules(),
    ): DoubleTop? = DoubleTopDetector(rules).also { d -> bars.forEach(d::feed) }.result()

    private fun bar(
        time: String,
        high: Double,
        low: Double,
    ) = Bar(time(time), open = low, high = high, low = low, close = low, volume = 10_000)

    private fun time(value: String) = LocalTime.parse(value)
}
