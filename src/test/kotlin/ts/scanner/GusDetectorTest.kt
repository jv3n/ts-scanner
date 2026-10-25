package ts.scanner

import ts.scanner.fixtures.BarFixture
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The GUS detector on real days of 1-min bars, the GUS of the user's stats sheet (#34). What it
 * protects : the premarket read off the extended hours, the Push within ten minutes of the bell —
 * none on a no-push day — and the Sink as the low of the **first fall**, not of the day (ZTG). The
 * days after a GUS, with no gap left, are not one.
 *
 * Times are Massive's, the minute a bar opens : the sheet reads the one after, its end.
 */
class GusDetectorTest {
    @Test
    fun `OLOX — the push at the bell and the first low, to the cent of the sheet`() {
        val gus = detect("OLOX", "2026-10-21")

        assertEquals(1.24, gus.pmHigh)
        assertEquals(1.235, gus.open)
        assertEquals(Point(time("09:30"), 1.28), gus.push)
        // The sheet : 0.95 at 09:45, read at the bar's end.
        assertEquals(Point(time("09:44"), 0.9505), gus.sink)
    }

    @Test
    fun `YMAT — a push that tops on the first bar, then a fall to 2·04`() {
        val gus = detect("YMAT", "2026-10-19")

        assertEquals(Point(time("09:30"), 2.68), gus.push)
        assertEquals(Point(time("09:34"), 2.0401), gus.sink)
        assertEquals(1.72, gus.lod)
        assertEquals(1.83, gus.eod)
    }

    @Test
    fun `ZTG — the sink is the low of the first fall, not the day's low`() {
        val gus = detect("ZTG", "2026-10-16")

        assertEquals(Point(time("09:39"), 1.1), gus.push)
        assertEquals(0.75, gus.sink.price)
        // The day went on down to 0.58 — after a bounce that closed the first fall.
        assertEquals(0.58, gus.lod)
    }

    @Test
    fun `a no-push day — FBGL, TOPS, GLND — has no push, and its sink is read from the open`() {
        for ((ticker, date) in listOf("FBGL" to "2026-09-22", "TOPS" to "2026-09-22", "GLND" to "2026-09-21")) {
            val gus = detect(ticker, date)

            assertNull(gus.push, "$ticker pushed")
            assertTrue(gus.sink.time >= MARKET_OPEN, "$ticker's sink is before the bell")
        }
    }

    @Test
    fun `the premarket is read off the extended-hours bars — STAK's PM open and PM high`() {
        val gus = detect("STAK", "2026-10-22")

        assertEquals(1.0, gus.previousClose)
        assertEquals(1.4, gus.pmOpen)
        assertEquals(1.71, gus.pmHigh)
    }

    @Test
    fun `a pop under one percent is no push — STAK, which the sheet counts as one`() {
        // Known gap : 1.69 over a 1.68 open is +0.6 %, and GLND's +0.5 % is a no-push day on the
        // sheet. One percent keeps the line readable ; the review corrects STAK.
        val gus = detect("STAK", "2026-10-22")

        assertNull(gus.push)
        assertEquals(Point(time("09:37"), 1.3801), gus.sink)
    }

    @Test
    fun `MASK — a fifteen percent bounce settles the first fall, a step before the sheet's sink`() {
        // Known gap : the sheet goes on to 0.91 at 10:15 ; OLOX stops after a 12.6 % bounce. No
        // single threshold reads both — the review corrects MASK.
        val gus = detect("MASK", "2026-10-19")

        assertEquals(Point(time("09:35"), 1.15), gus.push)
        assertEquals(Point(time("09:49"), 0.9688), gus.sink)
    }

    @Test
    fun `the day after a GUS is not one — STAK, YMAT, ZTG with no gap left`() {
        for ((ticker, date) in listOf("STAK" to "2026-10-23", "YMAT" to "2026-10-20", "ZTG" to "2026-10-19")) {
            assertNull(run(ticker, date), "$ticker on $date read as a GUS")
        }
    }

    @Test
    fun `a gap is measured to the PM high, as the scanner of the broker reads it`() {
        // MASK : 0.8032 → 1.20 is +49 %, a GUS — measured to its PM open, it would be +2 %.
        val strict = run("MASK", "2026-10-19", GusRules(minGapPercent = 50.0))
        val sheet = run("MASK", "2026-10-19")

        assertNull(strict)
        assertNotNull(sheet)
    }

    private fun detect(
        ticker: String,
        date: String,
    ): Gus = assertNotNull(run(ticker, date), "$ticker on $date not read as a GUS")

    private fun run(
        ticker: String,
        date: String,
        rules: GusRules = GusRules(),
    ): Gus? {
        val session = BarFixture.load(ticker, date)
        return GusDetector(session.previousClose, rules).also { d -> session.bars.forEach(d::feed) }.result()
    }

    private fun time(value: String) = LocalTime.parse(value)
}
