package ts.scanner

/**
 * The cut from the whole market to the tickers worth their 1-min bars (#35) — wide on purpose : the
 * daily bars hold neither the premarket high nor the premarket range, so the detectors decide.
 */
data class UniverseRules(
    /**
     * Session high over the previous close. A GUS gaps 45 % to its **PM high**, which the daily bar
     * does not hold, and can fade hard from there : LXEH 23/09, PM high +146 %, opened at +22 %. The
     * sheet's 19 GUS clear 42 % on their typed HOD — the session's high where checked against
     * Massive (GLND, CLRO), not on all 19. 25 % leaves room for a fade that never recovers.
     */
    val minGapPercent: Double = 25.0,
    /** Session high over session low — room for a 45 % extension. */
    val minRangePercent: Double = 40.0,
    val minOpen: Double = 0.30,
    val maxOpen: Double = 10.0,
    /** A day's ceiling : each ticker costs a request, at 5 a minute. The widest moves go first. */
    val maxTickers: Int = 60,
)

/** A ticker kept for its bars, with the close its gap is read from. */
data class Candidate(
    val ticker: String,
    val previousClose: Double,
    val dayVolume: Double,
)

/** Common stocks only : warrants, units and preferreds carry a suffix. */
private val PLAIN_TICKER = Regex("^[A-Z]{1,5}$")

fun universe(
    session: Map<String, DailyBar>,
    previous: Map<String, DailyBar>,
    rules: UniverseRules = UniverseRules(),
): List<Candidate> =
    session
        .mapNotNull { (ticker, day) ->
            if (!PLAIN_TICKER.matches(ticker)) return@mapNotNull null
            if (day.open !in rules.minOpen..rules.maxOpen) return@mapNotNull null
            val close = previous[ticker]?.close?.takeIf { it > 0 } ?: return@mapNotNull null
            val gap = percentMove(close, day.high)
            val range = if (day.low > 0) percentMove(day.low, day.high) else 0.0
            if (gap < rules.minGapPercent && range < rules.minRangePercent) return@mapNotNull null
            Candidate(ticker, close, day.volume) to maxOf(gap, range)
        }.sortedByDescending { it.second }
        .take(rules.maxTickers)
        .map { it.first }
