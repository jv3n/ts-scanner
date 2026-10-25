package ts.scanner

import java.time.LocalTime

/** What makes a session a GUS for the scanner (`trade/docs/pattern/GUS.md`) — the numbers bars can give. */
data class GusRules(
    /** Previous close → PM high, as the TradeZero scanner reads the gap (trade#499). */
    val minGapPercent: Double = 45.0,
    val minOpen: Double = 0.30,
    val maxOpen: Double = 10.0,
    /** The Push is the top of the move after the bell, within this many minutes of it. */
    val pushWindowMinutes: Long = 10,
    /** A Push no higher than the open by this much is no push. */
    val minPushPercent: Double = 1.0,
    /** The first fall is over once the price bounces this much off its low. */
    val sinkReboundPercent: Double = 10.0,
)

/**
 * A GUS as the stats sheet records it : the premarket that spotted it, then OPS — Open, Push, Sink —
 * and the day-wide HOD, LOD, EOD of the regular session.
 */
data class Gus(
    val previousClose: Double,
    val pmOpen: Double,
    val pmHigh: Double,
    val open: Double,
    /** Null on a no-push day. */
    val push: Point?,
    /** The low of the first fall after the Push — after the open on a no-push day. */
    val sink: Point,
    val hod: Double,
    val lod: Double,
    val eod: Double,
)

/**
 * Reads a GUS off a day of 1-min bars (#34), fed one bar at a time — no network, no clock — so the
 * D-1 batch replays a day and live alerts could feed it as bars arrive. [result] once the session
 * is over : null when the day was not a GUS.
 */
class GusDetector(
    private val previousClose: Double?,
    private val rules: GusRules = GusRules(),
) {
    private var pmOpen: Double? = null
    private var pmHigh: Double? = null
    private var open: Double? = null
    private var push: Point? = null
    private var sink = SinkTracker(rules.sinkReboundPercent)
    private var sinkFromOpen = SinkTracker(rules.sinkReboundPercent)
    private var hod: Double? = null
    private var lod: Point? = null
    private var eod: Double? = null
    private val pushEnds: LocalTime = MARKET_OPEN.plusMinutes(rules.pushWindowMinutes)

    fun feed(bar: Bar) {
        when {
            bar.time < MARKET_OPEN -> {
                if (pmOpen == null) pmOpen = bar.open
                pmHigh = maxOf(pmHigh ?: bar.high, bar.high)
            }
            bar.time < MARKET_CLOSE -> session(bar)
        }
    }

    private fun session(bar: Bar) {
        if (open == null) open = bar.open
        hod = maxOf(hod ?: bar.high, bar.high)
        if (lod == null || bar.low < lod!!.price) lod = Point(bar.time, bar.low)
        eod = bar.close
        sinkFromOpen.feed(bar)
        if (bar.time <= pushEnds && bar.high > (push?.price ?: Double.NEGATIVE_INFINITY)) {
            // The push is still forming : its fall starts once it tops.
            push = Point(bar.time, bar.high)
            sink = SinkTracker(rules.sinkReboundPercent)
            return
        }
        sink.feed(bar)
    }

    fun result(): Gus? {
        val previousClose = previousClose ?: return null
        val pmOpen = pmOpen ?: return null
        val pmHigh = pmHigh ?: return null
        val open = open ?: return null
        if (open !in rules.minOpen..rules.maxOpen) return null
        if (percentMove(previousClose, pmHigh) < rules.minGapPercent) return null
        val pushed = push?.takeIf { percentMove(open, it.price) >= rules.minPushPercent }
        return Gus(
            previousClose = previousClose,
            pmOpen = pmOpen,
            pmHigh = pmHigh,
            open = open,
            push = pushed,
            sink = (if (pushed == null) sinkFromOpen.sink else sink.sink) ?: lod!!,
            hod = hod!!,
            lod = lod!!.price,
            eod = eod!!,
        )
    }
}
