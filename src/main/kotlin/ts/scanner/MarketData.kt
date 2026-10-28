package ts.scanner

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Static/reference data for one ticker. */
data class TickerReference(
    val ticker: String,
    /**
     * Shares OUTSTANDING, not the free float — Massive publishes no float. Outstanding is an upper
     * bound on the float : a low value rules a ticker out for certain, a high one does not rule it in.
     */
    val sharesOutstanding: Long? = null,
    val marketCapUsd: Double? = null,
)

/**
 * One ticker's session, from the grouped daily bars : open, high, low and close of the **regular
 * session** only — checked on GLND 21/09, whose 3.72 PM high is not its 3.39 high here — and the
 * volume of the whole day, extended hours included.
 */
data class DailyBar(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

/**
 * FINRA short interest for one ticker, as of [settlementDate].
 *
 * Reported on a two-week cadence, so this is CONTEXT (squeeze-risk filtering and sizing),
 * never an entry signal. [daysToCover] = shortInterest / avgDailyVolume: the higher it is,
 * the more crowded the short side and the greater the squeeze risk — the main danger of
 * this strategy.
 */
data class ShortInterest(
    val ticker: String,
    val shortInterest: Long,
    val daysToCover: Double,
    val avgDailyVolume: Long,
    val settlementDate: String,
)

interface MarketDataProvider {
    suspend fun getReference(ticker: String): TickerReference

    /**
     * Latest short interest for each requested ticker, keyed by ticker.
     * Tickers with no FINRA coverage are simply absent from the map.
     *
     * Included in every Massive plan, the free Basic one too.
     */
    suspend fun getShortInterest(tickers: List<String>): Map<String, ShortInterest>

    /**
     * Tickers among [tickers] that ran a reverse split executed from [from] to [to], both included.
     * Returns only the offenders, so an empty set means "none of them did".
     */
    suspend fun getReverseSplits(
        tickers: List<String>,
        from: LocalDate,
        to: LocalDate,
    ): Set<String>

    /**
     * Tickers among [tickers] carrying a Reg SHO Rule 201 short-sale restriction INHERITED
     * from the previous session (SSR runs the day it trips plus the whole next day).
     *
     * Derived from the rule, see [ssrTriggered].
     */
    suspend fun getInheritedSsr(tickers: List<String>): Set<String>

    /** Every ticker's session on [date], unadjusted, in one request — null when [date] was not a trading day. */
    suspend fun getGroupedDaily(date: LocalDate): Map<String, DailyBar>?

    /** [ticker]'s 1-min bars on [date], extended hours included, unadjusted — empty on a day it did not trade. */
    suspend fun getMinuteBars(
        ticker: String,
        date: LocalDate,
    ): List<Bar>

    /** The close of the last session before [date], unadjusted — null when none is found in the week before. */
    suspend fun getPreviousClose(
        ticker: String,
        date: LocalDate,
    ): Double?
}

/**
 * Market data from Massive (formerly Polygon.io, rebranded 2025-10-30).
 *
 * The scanner runs on the free Basic plan : end-of-day data, REST only, 5 requests a minute, no
 * snapshots and no WebSocket — every call here is batched with that quota in mind.
 */
class MassiveProvider(
    private val apiKey: String,
) : MarketDataProvider {
    companion object {
        const val MASSIVE_REST_BASE = "https://api.massive.com"

        private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

        /** Enough calendar days to clear a weekend plus a holiday and still find 2 sessions. */
        private const val MAX_SESSION_PROBES = 7

        fun fromEnv(): MassiveProvider {
            val key = envOrNull("MASSIVE_API_KEY") ?: envOrNull("POLYGON_API_KEY")
            requireNotNull(key) { "MASSIVE_API_KEY (or legacy POLYGON_API_KEY) is missing. Set it in .env or Secret Manager." }
            return MassiveProvider(key)
        }
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    // Bearer header instead of ?apiKey= so the key never leaks into URLs/logs.
    private val client =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            defaultRequest { header(HttpHeaders.Authorization, "Bearer $apiKey") }
        }

    fun close() = client.close()

    override suspend fun getReference(ticker: String): TickerReference {
        val res =
            client
                .get("$MASSIVE_REST_BASE/v3/reference/tickers/$ticker")
                .body<JsonObject>()["results"]
                ?.jsonObject
        val shares =
            res?.get("share_class_shares_outstanding")?.jsonPrimitive?.longOrNull
                ?: res?.get("weighted_shares_outstanding")?.jsonPrimitive?.longOrNull
        return TickerReference(
            ticker = ticker,
            sharesOutstanding = shares,
            marketCapUsd = res?.get("market_cap")?.jsonPrimitive?.doubleOrNull,
        )
    }

    /**
     * Walks back through grouped daily bars to find the last two sessions, then applies
     * Rule 201 to the previous one: low ≤ 90 % of the session close before it.
     *
     * Two calls on a weekday, three or four across a weekend or a holiday — a non-trading
     * date simply comes back with no results, which is how we recognise it. Cheap enough
     * for the 5 requests/minute of the Basic plan, and it runs once, at scan time.
     */
    override suspend fun getInheritedSsr(tickers: List<String>): Set<String> {
        if (tickers.isEmpty()) return emptySet()
        val wanted = tickers.map { it.uppercase() }.toSet()
        val sessions = mutableListOf<Map<String, DailyBar>>()
        // Market days, so New York — a UTC host would roll the date over hours too early.
        var date = LocalDate.now(NEW_YORK).minusDays(1)
        var probes = 0
        while (sessions.size < 2 && probes < MAX_SESSION_PROBES) {
            groupedDaily(date, adjusted = true)?.filterKeys { it in wanted }?.let { sessions += it }
            date = date.minusDays(1)
            probes++
        }
        if (sessions.size < 2) return emptySet()
        val previous = sessions[0]
        val before = sessions[1]
        return wanted
            .filter { ticker ->
                val low = previous[ticker]?.low ?: return@filter false
                val reference = before[ticker]?.close ?: return@filter false
                ssrTriggered(low, reference)
            }.toSet()
    }

    /**
     * One request for the whole day : a session of extended hours is ~960 minutes, well under the
     * 50 000 bars of a page. Unadjusted, as the stats record the prices that printed.
     */
    override suspend fun getMinuteBars(
        ticker: String,
        date: LocalDate,
    ): List<Bar> =
        client
            .get("$MASSIVE_REST_BASE/v2/aggs/ticker/${ticker.uppercase()}/range/1/minute/$date/$date") {
                parameter("adjusted", "false")
                parameter("sort", "asc")
                parameter("limit", 50000)
            }.body<JsonObject>()["results"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { el ->
                val o = el.jsonObject
                val start = o["t"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                Bar(
                    // On the minute : `trade` refuses a bar off it (and the push with it), and a leg's
                    // length in minutes would lose one to a stray second.
                    time =
                        Instant
                            .ofEpochMilli(start)
                            .atZone(NEW_YORK)
                            .toLocalTime()
                            .truncatedTo(ChronoUnit.MINUTES),
                    open = o["o"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                    high = o["h"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                    low = o["l"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                    close = o["c"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                    volume = o["v"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L,
                )
            }

    override suspend fun getPreviousClose(
        ticker: String,
        date: LocalDate,
    ): Double? =
        client
            .get("$MASSIVE_REST_BASE/v2/aggs/ticker/${ticker.uppercase()}/range/1/day/${date.minusDays(7)}/${date.minusDays(1)}") {
                parameter("adjusted", "false")
                parameter("sort", "desc")
                parameter("limit", 1)
            }.body<JsonObject>()["results"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("c")
            ?.jsonPrimitive
            ?.doubleOrNull

    override suspend fun getGroupedDaily(date: LocalDate): Map<String, DailyBar>? = groupedDaily(date, adjusted = false)

    /** Null when [date] was not a trading day (no results published). */
    private suspend fun groupedDaily(
        date: LocalDate,
        adjusted: Boolean,
    ): Map<String, DailyBar>? {
        val results =
            client
                .get("$MASSIVE_REST_BASE/v2/aggs/grouped/locale/us/market/stocks/$date") {
                    parameter("adjusted", adjusted.toString())
                }.body<JsonObject>()["results"]
                ?.jsonArray
                .orEmpty()
        if (results.isEmpty()) return null
        return results
            .mapNotNull { el ->
                val o = el.jsonObject

                fun field(name: String) = o[name]?.jsonPrimitive?.doubleOrNull
                val ticker = o["T"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ticker to
                    DailyBar(
                        open = field("o") ?: return@mapNotNull null,
                        high = field("h") ?: return@mapNotNull null,
                        low = field("l") ?: return@mapNotNull null,
                        close = field("c") ?: return@mapNotNull null,
                        volume = field("v") ?: 0.0,
                    )
            }.toMap()
    }

    /**
     * One batched request (`ticker.any_of` + `execution_date` bounds) for the whole universe —
     * the Basic plan allows only 5 requests/minute.
     *
     * A reverse split is `split_to < split_from` (a 1-for-10 comes back as from=10, to=1).
     * That ratio IS the definition, so it decides; `adjustment_type` is only the fallback
     * for a row that omits the ratio.
     */
    override suspend fun getReverseSplits(
        tickers: List<String>,
        from: LocalDate,
        to: LocalDate,
    ): Set<String> {
        if (tickers.isEmpty()) return emptySet()
        val results =
            client
                .get("$MASSIVE_REST_BASE/stocks/v1/splits") {
                    parameter("ticker.any_of", tickers.joinToString(",") { it.uppercase() })
                    parameter("execution_date.gte", from.toString())
                    parameter("execution_date.lte", to.toString())
                    parameter("limit", 1000)
                }.body<JsonObject>()["results"]
                ?.jsonArray
                .orEmpty()
        return results
            .mapNotNull { el ->
                val o = el.jsonObject
                val t = o["ticker"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val from = o["split_from"]?.jsonPrimitive?.doubleOrNull
                val to = o["split_to"]?.jsonPrimitive?.doubleOrNull
                val reverse =
                    if (from != null && to != null) {
                        to < from
                    } else {
                        o["adjustment_type"]?.jsonPrimitive?.content == "reverse_split"
                    }
                t.takeIf { reverse }
            }.toSet()
    }

    /**
     * One request for the whole batch (`ticker.any_of`) — the Basic plan allows only
     * 5 requests/minute, so querying ticker by ticker would blow the quota on a full scan.
     *
     * The endpoint returns the FULL history, several settlement dates per ticker; sorting
     * by settlement_date desc means the first row seen for a ticker is its latest one.
     */
    override suspend fun getShortInterest(tickers: List<String>): Map<String, ShortInterest> {
        if (tickers.isEmpty()) return emptyMap()
        val results =
            client
                .get("$MASSIVE_REST_BASE/stocks/v1/short-interest") {
                    parameter("ticker.any_of", tickers.joinToString(",") { it.uppercase() })
                    parameter("sort", "settlement_date.desc")
                    // Room for several settlement dates per ticker, so none is missed.
                    parameter("limit", tickers.size * 8)
                }.body<JsonObject>()["results"]
                ?.jsonArray
                .orEmpty()
        return results
            .mapNotNull { el ->
                val o = el.jsonObject
                val t = o["ticker"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ShortInterest(
                    ticker = t,
                    shortInterest = o["short_interest"]?.jsonPrimitive?.longOrNull ?: 0L,
                    daysToCover = o["days_to_cover"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    avgDailyVolume = o["avg_daily_volume"]?.jsonPrimitive?.longOrNull ?: 0L,
                    settlementDate = o["settlement_date"]?.jsonPrimitive?.content.orEmpty(),
                )
            }.groupBy { it.ticker }
            .mapValues { (_, rows) -> rows.first() }
    }
}
