package ts.scanner

import java.time.Duration

/**
 * The legs of a double top the scanner proposes (`trade/docs/pattern/DT.md`, BARES), each a [Band]
 * on its move in percent : A Base → Apex, R Apex → Rejection (down), E Rejection → Echo (up).
 */
data class DoubleTopRules(
    val extension: Band = Band(120.0, 80.0),
    val rejection: Band = Band(23.5, 8.5),
    val echo: Band = Band(27.5, 12.5),
    /**
     * How far the Echo may clear the Apex — the sheet's fake-out of the last top. Past it, the Echo
     * broke out : no double top (the sheet's took-the-top DTs cleared it by 1.5 to 5.1 %).
     */
    val maxEchoOverApexPercent: Double = 6.0,
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
 * Until the price breaks under the Rejection, the Echo is the highest test of the Apex. Above the
 * Apex — the sheet's fake-out — it counts only once the price falls back under the Rejection, and
 * only up to [DoubleTopRules.maxEchoOverApexPercent] : past that, a breakout, and the search goes on.
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

    /**
     * The double top read so far. Until the price breaks under the Rejection, a higher test of the Apex
     * — in band, in time, not past [DoubleTopRules.maxEchoOverApexPercent] — becomes the Echo (SPHL
     * 21/10 : 5.55 at 09:49, then 5.94 at 09:56) ; the first test past any of them settles it.
     */
    private var candidate: DoubleTop? = null
    private var settled = false
    private var broke = false
    private var sink: SinkTracker? = null

    /** The double top once its four first points count — before the close, without its Sink. */
    val detected: DoubleTop? get() = candidate?.takeIf { it.counts }

    fun feed(bar: Bar) {
        // Session only, Base included : the user takes no double top in the extended hours.
        if (bar.time < MARKET_OPEN || bar.time >= MARKET_CLOSE) return
        val top = candidate
        swing(bar)
        if (top == null) return
        if (!settled && !broke && bar.high > top.echo.price) {
            val higher = Point(bar.time, bar.high)
            val stillTesting =
                percentMove(top.apex.price, higher.price) <= rules.maxEchoOverApexPercent &&
                    percentMove(top.rejection.price, higher.price) in rules.echo &&
                    minutes(top.rejection, higher) <= rules.maxEchoMinutes
            when {
                stillTesting -> {
                    candidate = top.copy(echo = higher)
                    sink = SinkTracker(rules.sinkReboundPercent)
                    return
                }
                // A fake-out the price carried on past : a breakout, no double top — the search goes on.
                top.echo.price > top.apex.price -> {
                    candidate = null
                    sink = null
                    return
                }
                else -> settled = true
            }
        }
        if (bar.low < top.rejection.price) broke = true
        sink!!.feed(bar)
    }

    /**
     * The double top at the close, with its Sink — an Echo above the Apex counts only once the price
     * fell back under the Rejection : the sheet's fake-out, not a breakout.
     */
    fun result(): DoubleTop? = candidate?.takeIf { it.counts }?.copy(sink = sink?.sink)

    private val DoubleTop.counts get() = echo.price <= apex.price || broke

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
                    if (candidate == null) tryEcho(current, bar)
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
                if (percentMove(apex.price, echo.price) > rules.maxEchoOverApexPercent) continue
                if (minutes(apex, rejection) > rules.maxRejectionMinutes) continue
                for (base in lows.filter { it.time < apex.time }.asReversed()) {
                    if (lows.any { it.time > base.time && it.time < apex.time && it.price < base.price }) continue
                    // The Apex is the top of the whole push : a higher swing after the Base is the real one.
                    if (highs.any { it.time > base.time && it.time < rejection.time && it.price > apex.price }) continue
                    if (percentMove(base.price, apex.price) !in rules.extension) continue
                    if (minutes(base, apex) > rules.maxExtensionMinutes) continue
                    candidate = DoubleTop(base, apex, rejection, echo)
                    settled = false
                    broke = bar.low < rejection.price
                    sink = SinkTracker(rules.sinkReboundPercent).also { it.feed(bar) }
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
