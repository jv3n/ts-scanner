package ts.scanner

/**
 * The Sink of a pattern : the low of its **first fall**, not of the day — the fall is over once the
 * price bounces [reboundPercent] off its low. Without a bounce before the close, the lowest print
 * up to it.
 */
class SinkTracker(
    private val reboundPercent: Double,
) {
    private var low: Point? = null
    private var settled = false

    val sink: Point? get() = low

    fun feed(bar: Bar) {
        if (settled) return
        val current = low
        if (current == null || bar.low < current.price) {
            low = Point(bar.time, bar.low)
        } else if (percentMove(current.price, bar.high) >= reboundPercent) {
            settled = true
        }
    }
}
