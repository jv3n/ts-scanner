package ts.scanner

import java.time.LocalTime
import kotlin.math.abs

/** A point of a pattern : the minute its bar opens, New York, as Massive dates it, and its price. */
data class Point(
    val time: LocalTime,
    val price: Double,
) {
    /** The minute a chart reads for it — the bar's end, one later : what `trade` records. */
    val chartTime: LocalTime get() = time.plusMinutes(1)
}

/**
 * A leg's measure, accepted when it lands within [acceptance] of its [target], both in percent —
 * the sheet's « 18–22 % » reads `Band(20.0, 2.0)`.
 */
data class Band(
    val target: Double,
    val acceptance: Double,
) {
    operator fun contains(percent: Double) = abs(percent - target) <= acceptance

    override fun toString() = "$target ± $acceptance %"
}

/** How far [to] moved from [from], in percent of [from] — positive up. */
fun percentMove(
    from: Double,
    to: Double,
) = (to / from - 1) * 100
