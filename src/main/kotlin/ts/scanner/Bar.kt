package ts.scanner

import java.time.LocalDate
import java.time.LocalTime

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

/** The regular session : 09:30 to 16:00, New York. */
val MARKET_OPEN: LocalTime = LocalTime.of(9, 30)
val MARKET_CLOSE: LocalTime = LocalTime.of(16, 0)
