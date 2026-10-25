package ts.scanner

import java.math.RoundingMode

// What a detection becomes in `trade` : prices to its four decimals, times at a chart's minute.

/** A GUS, with its premarket volume — what the sheet's criterion reads. */
fun Gus.toScannedStat(ticker: String) =
    ScannedStat(
        pattern = "GUS",
        ticker = ticker,
        volumeMillions = millions(pmVolume.toDouble()),
        previousClose = price(previousClose),
        pmOpen = price(pmOpen),
        pmHigh = price(pmHigh),
        openPrice = price(open),
        pushOpenPrice = push?.let { price(it.price) },
        pushOpenTime = push?.let { minute(it) },
        noPush = push == null,
        hodPrice = price(hod),
        lodPrice = price(lod),
        eodPrice = price(eod),
        gusDropPrice = price(sink.price),
        gusDropTime = minute(sink),
    )

/** A double top, with the day's volume. */
fun DoubleTop.toScannedStat(
    ticker: String,
    dayVolume: Double,
) = ScannedStat(
    pattern = "DT",
    ticker = ticker,
    volumeMillions = millions(dayVolume),
    dtStartPrice = price(base.price),
    dtStartTime = minute(base),
    dtTopPrice = price(apex.price),
    dtTopTime = minute(apex),
    dtLowPrice = price(rejection.price),
    dtLowTime = minute(rejection),
    dtRetestPrice = price(echo.price),
    dtRetestTime = minute(echo),
    dtDropPrice = sink?.let { price(it.price) },
    dtDropTime = sink?.let { minute(it) },
)

private fun price(value: Double) = value.toBigDecimal().setScale(4, RoundingMode.HALF_UP).toDouble()

private fun millions(volume: Double) = (volume / 1_000_000).toBigDecimal().setScale(2, RoundingMode.HALF_UP).toDouble()

private fun minute(point: Point) = point.chartTime.toString()
