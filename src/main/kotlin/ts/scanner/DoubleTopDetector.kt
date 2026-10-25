package ts.scanner

import java.time.Duration

/**
 * The legs of a double top the scanner proposes (`trade/docs/pattern/DT.md`, BARES), each a [Band]
 * on its move in percent : A Base → Apex, R Apex → Rejection (down), E Rejection → Echo (up).
 */
data class DoubleTopRules(
    val extension: Band = Band(52.5, 7.5),
    val rejection: Band = Band(20.0, 2.0),
    val echo: Band = Band(20.0, 2.0),
    /**
     * How long each leg may take, in minutes — a push that takes all morning is no push. Set from the
     * sheet's own double tops : A under the hour, R a quarter of an hour at most.
     */
    val maxExtensionMinutes: Long = 60,
    val maxRejectionMinutes: Long = 30,
    val maxEchoMinutes: Long = 60,
    /**
     * A swing low or high is confirmed once the price turns this much off it — on price alone, from
     * 04:00 : a premarket double top is real. Thin-print noise is left to the review (« bruit ») ; a
     * volume floor on the swings is the knob if it shows.
     */
    val pivotPercent: Double = 5.0,
    /** The first fall after the Echo is over once the price bounces this much off its low. */
    val sinkReboundPercent: Double = 10.0,
)

/** The five BARES points. [sink] is the low of the first fall after the Echo — null until the close. */
data class DoubleTop(
    val base: Point,
    val apex: Point,
    val rejection: Point,
    val echo: Point,
    val sink: Point? = null,
)

/**
 * Reads a double top off a day of 1-min bars (#34), fed one bar at a time — no network, no clock.
 * Swing lows and highs are confirmed as the price turns ; each new swing high is tried as the Echo
 * of the swings before it. The first double top of the day wins : `trade` holds one per day.
 *
 * An Echo above the Apex is the fake-out the sheet allows, kept only once the price falls back
 * under the Rejection ; if it never does, it was a breakout and nothing is emitted.
 */
class DoubleTopDetector(
    private val rules: DoubleTopRules = DoubleTopRules(),
) {
    private enum class Swing { LOW, HIGH }

    private data class Pivot(
        val kind: Swing,
        val point: Point,
    )

    private val pivots = mutableListOf<Pivot>()
    private var heading: Swing = Swing.LOW
    private var extreme: Point? = null

    /** An Echo above the Apex, waiting for the fall under the Rejection that proves it. */
    private var pending: DoubleTop? = null
    private var found: DoubleTop? = null
    private var sink: SinkTracker? = null

    /** The double top once its four first points are in — before the close, without its Sink. */
    val detected: DoubleTop? get() = found

    fun feed(bar: Bar) {
        if (bar.time >= MARKET_CLOSE) return
        if (found != null) {
            sink!!.feed(bar)
            return
        }
        pending?.let { candidate ->
            if (bar.low < candidate.rejection.price) {
                found = candidate
                pending = null
                sink = SinkTracker(rules.sinkReboundPercent).also { it.feed(bar) }
                return
            }
            if (bar.high > candidate.echo.price) {
                // Past its band or its window, the Echo is no longer testing the Apex : a breakout, dropped.
                val higher = Point(bar.time, bar.high)
                pending =
                    candidate.copy(echo = higher).takeIf {
                        percentMove(candidate.rejection.price, higher.price) in rules.echo &&
                            minutes(candidate.rejection, higher) <= rules.maxEchoMinutes
                    }
            }
        }
        swing(bar)
    }

    fun result(): DoubleTop? = found?.copy(sink = sink?.sink)

    private fun swing(bar: Bar) {
        val current = extreme
        if (current == null) {
            extreme = Point(bar.time, bar.low)
            return
        }
        when (heading) {
            Swing.LOW ->
                if (bar.low < current.price) {
                    extreme = Point(bar.time, bar.low)
                } else if (percentMove(current.price, bar.high) >= rules.pivotPercent) {
                    pivots += Pivot(Swing.LOW, current)
                    heading = Swing.HIGH
                    extreme = Point(bar.time, bar.high)
                }
            Swing.HIGH ->
                if (bar.high > current.price) {
                    extreme = Point(bar.time, bar.high)
                } else if (-percentMove(current.price, bar.low) >= rules.pivotPercent) {
                    pivots += Pivot(Swing.HIGH, current)
                    heading = Swing.LOW
                    extreme = Point(bar.time, bar.low)
                    if (pending == null) tryEcho(current, bar)
                }
        }
    }

    /**
     * [echo] is a swing high just confirmed : look back for Base, Apex and Rejection among the
     * swings before it — each the extreme of its stretch, each leg within its band.
     */
    private fun tryEcho(
        echo: Point,
        bar: Bar,
    ) {
        val lows = pivots.filter { it.kind == Swing.LOW }.map { it.point }
        val highs = pivots.filter { it.kind == Swing.HIGH }.map { it.point }.dropLast(1)
        for (rejection in lows.filter { it.time < echo.time }.asReversed()) {
            if (lows.any { it.time > rejection.time && it.time < echo.time && it.price < rejection.price }) continue
            if (percentMove(rejection.price, echo.price) !in rules.echo) continue
            if (minutes(rejection, echo) > rules.maxEchoMinutes) continue
            for (apex in highs.filter { it.time < rejection.time }.asReversed()) {
                if (highs.any { it.time > apex.time && it.time < rejection.time && it.price > apex.price }) continue
                // The Rejection is the low of the whole pullback : a lower swing after the Apex is the real one.
                if (lows.any { it.time > apex.time && it.time < echo.time && it.price < rejection.price }) continue
                if (-percentMove(apex.price, rejection.price) !in rules.rejection) continue
                if (minutes(apex, rejection) > rules.maxRejectionMinutes) continue
                for (base in lows.filter { it.time < apex.time }.asReversed()) {
                    if (lows.any { it.time > base.time && it.time < apex.time && it.price < base.price }) continue
                    // The Apex is the top of the whole push : a higher swing after the Base is the real one.
                    if (highs.any { it.time > base.time && it.time < rejection.time && it.price > apex.price }) continue
                    if (percentMove(base.price, apex.price) !in rules.extension) continue
                    if (minutes(base, apex) > rules.maxExtensionMinutes) continue
                    val top = DoubleTop(base, apex, rejection, echo)
                    if (echo.price > apex.price) {
                        pending = top
                    } else {
                        found = top
                        sink = SinkTracker(rules.sinkReboundPercent).also { it.feed(bar) }
                    }
                    return
                }
            }
        }
    }

    private fun minutes(
        from: Point,
        to: Point,
    ) = Duration.between(from.time, to.time).toMinutes()
}
