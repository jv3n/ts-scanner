package ts.scanner

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Massive's free plan answers a sixth request in a minute with a 429 (#35) : the pacer holds every
 * call of the job to five in any rolling minute, without slowing the first five. Run on virtual
 * time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PacerTest {
    @Test
    fun `never more than five requests in any minute`() =
        runTest {
            val pacer = Pacer(perMinute = 5, timeSource = testScheduler.timeSource)
            val at = mutableListOf<Long>()

            repeat(12) { pacer.paced { at += currentTime } }

            for (i in 0 until at.size - 5) {
                assertTrue(at[i + 5] - at[i] >= 60_000, "requests ${i + 1} and ${i + 6} within a minute : $at")
            }
        }

    @Test
    fun `the first five go at once`() =
        runTest {
            val pacer = Pacer(perMinute = 5, timeSource = testScheduler.timeSource)
            val at = mutableListOf<Long>()

            repeat(5) { pacer.paced { at += currentTime } }

            assertEquals(List(5) { 0L }, at)
        }
}
