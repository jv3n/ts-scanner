package ts.scanner

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two rulers every detector measures with : a [Band] — a leg's target ± its acceptance, the
 * user's « 18–22 % » — and the [SinkTracker], which reads the Sink as the low of the first fall,
 * not of the day. A bound read one way here would move every pattern the scanner proposes.
 */
class DetectionTest {
    @Test
    fun `a band accepts its target and both of its edges`() {
        val rejection = Band(20.0, 2.0)

        assertTrue(20.0 in rejection)
        assertTrue(18.0 in rejection)
        assertTrue(22.0 in rejection)
    }

    @Test
    fun `a band refuses a measure past its acceptance on either side`() {
        val rejection = Band(20.0, 2.0)

        assertFalse(17.9 in rejection)
        assertFalse(22.1 in rejection)
    }

    @Test
    fun `the chart reads a point at the end of its bar, one minute later`() {
        assertEquals(LocalTime.of(9, 31), Point(LocalTime.of(9, 30), 1.28).chartTime)
    }

    @Test
    fun `the sink is the low of the first fall, settled by a bounce off it`() {
        // OLOX 21/10 : down to 0.9505, a 12 % bounce, then lower — the sink is the first low.
        val sink = SinkTracker(reboundPercent = 10.0)
        listOf(bar("09:44", high = 0.97, low = 0.9505), bar("09:56", high = 1.07, low = 1.0), bar("10:29", high = 0.92, low = 0.89))
            .forEach(sink::feed)

        assertEquals(Point(LocalTime.of(9, 44), 0.9505), sink.sink)
    }

    @Test
    fun `a bounce under the threshold keeps the fall going`() {
        val sink = SinkTracker(reboundPercent = 10.0)
        listOf(bar("09:40", high = 1.0, low = 0.95), bar("09:45", high = 1.02, low = 0.97), bar("09:50", high = 0.93, low = 0.9))
            .forEach(sink::feed)

        assertEquals(Point(LocalTime.of(9, 50), 0.9), sink.sink)
    }

    @Test
    fun `without a bounce the sink is the lowest print fed`() {
        val sink = SinkTracker(reboundPercent = 10.0)
        listOf(bar("15:00", high = 1.0, low = 0.98), bar("15:59", high = 0.97, low = 0.95)).forEach(sink::feed)

        assertEquals(Point(LocalTime.of(15, 59), 0.95), sink.sink)
    }

    private fun bar(
        time: String,
        high: Double,
        low: Double,
    ) = Bar(LocalTime.parse(time), open = low, high = high, low = low, close = low, volume = 1_000)
}
