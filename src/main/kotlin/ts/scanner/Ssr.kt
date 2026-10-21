package ts.scanner

/**
 * Reg SHO Rule 201 : a short-sale restriction trips once a stock trades 10 % or more below the
 * previous session's close, and holds for the rest of that day and all of the next one.
 *
 * Neither TradeZero nor Massive publishes the SSR state (checked on both API catalogues,
 * 2026-07-23), so it is derived from the rule itself.
 */
fun ssrTriggered(
    low: Double,
    referenceClose: Double,
): Boolean = referenceClose > 0.0 && low > 0.0 && low <= referenceClose * 0.90
