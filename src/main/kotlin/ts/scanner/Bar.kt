package ts.scanner

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One 1-min aggregate, its [time] the minute it opens in New York — 04:00 to 20:00 with the extended hours. */
data class Bar(
    val time: LocalTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

/** A ticker's whole day of 1-min bars, extended hours included, and the close of the session before it. */
data class SessionBars(
    val ticker: String,
    val date: LocalDate,
    val previousClose: Double?,
    val bars: List<Bar>,
)

/**
 * The bars `trade` keeps for a stat's chart (trade#784) : the extended day, 04:00 to 20:00 — a bar
 * out of it, or one whose low and high do not hold its open and close, would refuse the whole push.
 */
fun List<Bar>.forChart(
    ticker: String,
    date: LocalDate,
): TickerBars =
    TickerBars(
        ticker,
        this
            .filter { it.time >= EXTENDED_OPEN && it.time < EXTENDED_CLOSE }
            .filter { it.low > 0 && it.low <= minOf(it.open, it.close) && it.high >= maxOf(it.open, it.close) && it.volume >= 0 }
            .map {
                PushedBar(
                    date
                        .atTime(it.time)
                        .atZone(NEW_YORK)
                        .toInstant()
                        .toString(),
                    it.open,
                    it.high,
                    it.low,
                    it.close,
                    it.volume,
                )
            },
    )

private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
private val EXTENDED_OPEN: LocalTime = LocalTime.of(4, 0)
private val EXTENDED_CLOSE: LocalTime = LocalTime.of(20, 0)

/** The regular session : 09:30 to 16:00, New York. */
val MARKET_OPEN: LocalTime = LocalTime.of(9, 30)
val MARKET_CLOSE: LocalTime = LocalTime.of(16, 0)
