package ts.scanner

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a detection becomes in `trade` (#35) — where a wrong minute or a stray decimal would land in
 * the user's sheet : times at a chart's minute (the bar's end), prices to `trade`'s four decimals,
 * the GUS with its premarket volume, a double top with the day's.
 */
class ScannedStatsTest {
    @Test
    fun `a GUS goes over with its times at the bar's end, as the sheet reads them`() {
        val stat = gus().toScannedStat("OLOX")

        assertEquals("GUS", stat.pattern)
        assertEquals("09:31", stat.pushOpenTime)
        assertEquals("09:45", stat.gusDropTime)
        assertEquals(0.9505, stat.gusDropPrice)
    }

    @Test
    fun `a GUS carries its premarket volume, in millions`() {
        assertEquals(74.56, gus().toScannedStat("OLOX").volumeMillions)
    }

    @Test
    fun `a no-push GUS goes over with no push and the flag up`() {
        val stat = gus(push = null).toScannedStat("TOPS")

        assertTrue(stat.noPush)
        assertNull(stat.pushOpenPrice)
        assertNull(stat.pushOpenTime)
    }

    @Test
    fun `prices are cut to trade's four decimals`() {
        val stat = gus(pmHigh = 1.239999999).toScannedStat("OLOX")

        assertEquals(1.24, stat.pmHigh)
    }

    @Test
    fun `a double top goes over as its five points, and the day's volume`() {
        val stat = kndi().toScannedStat("KNDI", dayVolume = 182_400_000.0)

        assertEquals("DT", stat.pattern)
        assertEquals(listOf("10:53", "11:05", "11:07", "11:13"), listOf(stat.dtStartTime, stat.dtTopTime, stat.dtLowTime, stat.dtRetestTime))
        assertEquals(1.03, stat.dtTopPrice)
        assertEquals(182.4, stat.volumeMillions)
    }

    @Test
    fun `a double top read without its sink goes over without one`() {
        val stat = kndi().copy(sink = null).toScannedStat("KNDI", dayVolume = 0.0)

        assertNull(stat.dtDropPrice)
        assertNull(stat.dtDropTime)
    }

    /** OLOX 21/10 as the detector reads it. */
    private fun gus(
        push: Point? = Point(time("09:30"), 1.28),
        pmHigh: Double = 1.24,
    ) = Gus(
        previousClose = 0.737,
        pmOpen = 0.7488,
        pmHigh = pmHigh,
        pmVolume = 74_560_000,
        open = 1.235,
        push = push,
        sink = Point(time("09:44"), 0.9505),
        hod = 1.28,
        lod = 0.87,
        eod = 1.0088,
    )

    /** KNDI 28/09 as the detector reads it. */
    private fun kndi() =
        DoubleTop(
            base = Point(time("10:52"), 0.67),
            apex = Point(time("11:04"), 1.03),
            rejection = Point(time("11:06"), 0.81),
            echo = Point(time("11:12"), 0.98),
            sink = Point(time("11:13"), 0.92),
        )

    private fun time(value: String) = LocalTime.parse(value)
}
