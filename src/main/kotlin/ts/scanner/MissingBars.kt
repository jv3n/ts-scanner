package ts.scanner

import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** What a [MissingBarsPass] did : days filled, days Massive had nothing for, tickers that failed. */
data class MissingBarsResult(
    val filled: Int,
    val notFound: Int,
    val failed: List<Missed>,
)

/**
 * The bars of the stats that miss them (trade#784) — typed by hand, or older than the charts : after
 * the day's scan, `trade` lists its ticker-days without bars and each one is fetched from Massive,
 * one request a day. At most [limit] a run, so a first run over the whole history drains it over a
 * few mornings instead of holding one for an hour.
 *
 * An empty answer is a day Massive has nothing for — marked, never asked again ; a failed request
 * marks nothing, so the next run tries it again.
 */
class MissingBarsPass(
    private val market: MarketDataProvider,
    private val trade: TradeClient,
    private val limit: Int = DEFAULT_LIMIT,
    private val progress: (String) -> Unit = {},
) {
    suspend fun run(): MissingBarsResult {
        val days = trade.missingBars(limit)
        if (days.isEmpty()) return MissingBarsResult(0, 0, emptyList())
        progress("missing bars — ${days.size} ticker-days to fetch, ~${minutesFor(days.size)} min")
        var filled = 0
        var notFound = 0
        val failed = mutableListOf<Missed>()
        for ((index, day) in days.withIndex()) {
            val step = "[${index + 1}/${days.size}] ${day.ticker} ${day.tradingDate}"
            val bars =
                try {
                    market.getMinuteBars(day.ticker, LocalDate.parse(day.tradingDate))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val reason = failure.message ?: failure::class.simpleName.orEmpty()
                    failed += Missed(day.ticker, reason)
                    progress("$step — not read ($reason)")
                    continue
                }
            val chart = bars.forChart(day.ticker, LocalDate.parse(day.tradingDate))
            if (chart.bars.isEmpty()) {
                trade.markBarsNotFound(day)
                notFound++
                progress("$step — none served")
            } else {
                trade.pushBars(DayBarsPush(day.tradingDate, day.ticker, chart.bars))
                filled++
                progress("$step — ${chart.bars.size} bars")
            }
        }
        return MissingBarsResult(filled, notFound, failed)
    }

    private fun minutesFor(requests: Int) = (requests + REQUESTS_A_MINUTE - 1) / REQUESTS_A_MINUTE

    companion object {
        /** ~4 minutes at Massive's 5 requests a minute. */
        const val DEFAULT_LIMIT = 20
        private const val REQUESTS_A_MINUTE = 5
    }
}
