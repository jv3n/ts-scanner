package ts.scanner

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A stat as `trade` takes it on `POST /api/scanner/stats` (jv3n/trade#763) : a GUS fills the
 * premarket and OPS fields, a DT the five BARES points. Times are `HH:mm`, a chart's minute — the
 * bar's end.
 */
@Serializable
data class ScannedStat(
    val pattern: String,
    val ticker: String,
    val volumeMillions: Double? = null,
    val previousClose: Double? = null,
    val pmOpen: Double? = null,
    val pmHigh: Double? = null,
    val openPrice: Double? = null,
    val pushOpenPrice: Double? = null,
    val pushOpenTime: String? = null,
    val noPush: Boolean = false,
    val hodPrice: Double? = null,
    val lodPrice: Double? = null,
    val eodPrice: Double? = null,
    val gusDropPrice: Double? = null,
    val gusDropTime: String? = null,
    val dtStartPrice: Double? = null,
    val dtStartTime: String? = null,
    val dtTopPrice: Double? = null,
    val dtTopTime: String? = null,
    val dtLowPrice: Double? = null,
    val dtLowTime: String? = null,
    val dtRetestPrice: Double? = null,
    val dtRetestTime: String? = null,
    val dtDropPrice: Double? = null,
    val dtDropTime: String? = null,
)

@Serializable
data class ScannedStatsRequest(
    val tradingDate: String,
    val stats: List<ScannedStat>,
)

/** What `trade` did with a push : a stat that exists — typed, to review, rejected — is skipped. */
@Serializable
data class ScannedStatsResult(
    val created: List<ScannedStatCreated>,
    val skipped: List<ScannedStatSkipped>,
)

@Serializable
data class ScannedStatCreated(
    val ticker: String,
    val pattern: String,
)

@Serializable
data class ScannedStatSkipped(
    val ticker: String,
    val pattern: String,
    val reason: String,
    val detail: String? = null,
)

/** `POST /api/scanner/runs` — every run, a failed one included, so Today can say it. */
@Serializable
data class ScannerRunReport(
    val tradingDate: String,
    val startedAt: String,
    val finishedAt: String?,
    val tickersScanned: Int,
    val gusFound: Int,
    val dtFound: Int,
    val error: String? = null,
)

/** Port — where the scanner pushes what it found : `trade`'s scanner endpoints. */
interface TradeClient {
    suspend fun pushStats(request: ScannedStatsRequest): ScannedStatsResult

    suspend fun reportRun(report: ScannerRunReport)

    fun close() {}
}

/**
 * `trade` over HTTP, with the bearer token its scanner door takes. A refusal (401, 400, 503) is an
 * exception : the run fails, and says why.
 */
class HttpTradeClient(
    private val baseUrl: String,
    token: String,
) : TradeClient {
    private val client =
        HttpClient(CIO) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            defaultRequest { header(HttpHeaders.Authorization, "Bearer $token") }
        }

    override suspend fun pushStats(request: ScannedStatsRequest): ScannedStatsResult =
        client
            .post("${baseUrl.trimEnd('/')}/api/scanner/stats") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

    override suspend fun reportRun(report: ScannerRunReport) {
        client.post("${baseUrl.trimEnd('/')}/api/scanner/runs") {
            contentType(ContentType.Application.Json)
            setBody(report)
        }
    }

    override fun close() = client.close()
}
