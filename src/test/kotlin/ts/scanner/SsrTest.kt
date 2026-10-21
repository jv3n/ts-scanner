package ts.scanner

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rule 201 derived from prices, since no data source publishes it. A stat carries the `ssr` flag,
 * so a wrong threshold would mislabel every DT the scanner records.
 */
class SsrTest {
    @Test
    fun `trips when the low is exactly ten percent under the reference close`() {
        assertTrue(ssrTriggered(low = 1.80, referenceClose = 2.00))
    }

    @Test
    fun `stays off on a low just above the ten percent line`() {
        assertFalse(ssrTriggered(low = 1.81, referenceClose = 2.00))
    }

    @Test
    fun `never trips on missing prices`() {
        // Massive returns 0 for a field it lacks : an absent close must not read as a crash.
        assertFalse(ssrTriggered(low = 1.0, referenceClose = 0.0))
        assertFalse(ssrTriggered(low = 0.0, referenceClose = 2.0))
    }
}
