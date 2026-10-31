package ts.scanner

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * What an answer from Massive means (#59). An error is never an empty answer : two runs side by side
 * took 429s for a holiday, so a scan read nothing and ended green, and 20 ticker-days were marked
 * « none served » for good. A 200 without results keeps its meaning : no session, or no bars.
 */
class MassiveProviderTest {
    private val day = LocalDate.of(2026, 10, 29)
    private val session = """{"results":[{"T":"PCLA","o":1.0,"h":1.2,"l":0.9,"c":1.1,"v":1000}]}"""
    private val rateLimited = """{"status":"ERROR","error":"You've exceeded the maximum requests per minute"}"""

    /** Answers the [answers] in order, the last one again and again ; [calls] counts them. */
    private inner class Massive(
        vararg answers: Pair<HttpStatusCode, String>,
    ) {
        var calls = 0
        val provider =
            MassiveProvider(
                "key",
                MockEngine {
                    val (status, body) = answers[minOf(calls++, answers.lastIndex)]
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                rateLimitWait = Duration.ZERO,
            )
    }

    @Test
    fun `a 429 that clears returns the session`() =
        runTest {
            val massive = Massive(HttpStatusCode.TooManyRequests to rateLimited, HttpStatusCode.OK to session)

            assertNotNull(massive.provider.getGroupedDaily(day)?.get("PCLA"))
            assertEquals(2, massive.calls)
        }

    @Test
    fun `a 429 that does not clear is an error, not a holiday`() =
        runTest {
            val massive = Massive(HttpStatusCode.TooManyRequests to rateLimited)

            val failure = assertFailsWith<IllegalStateException> { massive.provider.getGroupedDaily(day) }

            assertTrue("429" in failure.message.orEmpty())
            assertEquals(6, massive.calls)
        }

    @Test
    fun `a 429 on the minute bars is an error, not a ticker with no bars`() =
        runTest {
            val massive = Massive(HttpStatusCode.TooManyRequests to rateLimited)

            assertFailsWith<IllegalStateException> { massive.provider.getMinuteBars("PCLA", day) }
        }

    @Test
    fun `a server error fails at once, with no wait`() =
        runTest {
            val massive = Massive(HttpStatusCode.InternalServerError to "{}")

            assertFailsWith<IllegalStateException> { massive.provider.getGroupedDaily(day) }
            assertEquals(1, massive.calls)
        }

    @Test
    fun `a 200 without results is still a day with no session, and no bars`() =
        runTest {
            val massive = Massive(HttpStatusCode.OK to """{"status":"OK","resultsCount":0}""")

            assertNull(massive.provider.getGroupedDaily(day))
            assertEquals(emptyList(), massive.provider.getMinuteBars("PCLA", day))
        }
}
