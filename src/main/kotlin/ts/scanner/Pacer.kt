package ts.scanner

import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Holds calls to [perMinute] in any rolling minute — Massive's free plan answers a sixth one with a
 * 429. The time source is injected so a test runs on virtual time. Not for concurrent callers : its
 * window is unsynchronised, right only while each call is awaited before the next.
 */
class Pacer(
    private val perMinute: Int = 5,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val recent = ArrayDeque<ComparableTimeMark>()

    suspend fun <T> paced(call: suspend () -> T): T {
        if (recent.size >= perMinute) {
            val wait = 1.minutes - recent.removeFirst().elapsedNow()
            if (wait.isPositive()) delay(wait)
        }
        recent.addLast(timeSource.markNow())
        return call()
    }
}

/** [delegate] with every request through the [pacer] — what the job talks to. */
class PacedMarketData(
    private val delegate: MarketDataProvider,
    private val pacer: Pacer,
) : MarketDataProvider {
    override suspend fun getGroupedDaily(date: LocalDate) = pacer.paced { delegate.getGroupedDaily(date) }

    override suspend fun getMinuteBars(
        ticker: String,
        date: LocalDate,
    ) = pacer.paced { delegate.getMinuteBars(ticker, date) }

    override suspend fun getPreviousClose(
        ticker: String,
        date: LocalDate,
    ) = pacer.paced { delegate.getPreviousClose(ticker, date) }

    override suspend fun getReference(ticker: String) = pacer.paced { delegate.getReference(ticker) }

    override suspend fun getShortInterest(tickers: List<String>) = pacer.paced { delegate.getShortInterest(tickers) }

    override suspend fun getReverseSplits(
        tickers: List<String>,
        from: LocalDate,
        to: LocalDate,
    ) = pacer.paced { delegate.getReverseSplits(tickers, from, to) }

    // Several requests behind one call (it walks back through the sessions) : paced as one, so the
    // job does not call it.
    override suspend fun getInheritedSsr(tickers: List<String>) = pacer.paced { delegate.getInheritedSsr(tickers) }
}
