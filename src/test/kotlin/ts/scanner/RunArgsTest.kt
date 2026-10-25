package ts.scanner

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What a run scans, read off its arguments (#44) — refused before any request when it cannot be
 * right : a quarter of an hour of reading per day is spent before a bad range would show.
 */
class RunArgsTest {
    private val today = LocalDate.of(2026, 10, 25)

    @Test
    fun `no argument scans the previous trading day`() {
        assertEquals(RunArgs(), RunArgs.parse(emptyArray(), today))
    }

    @Test
    fun `a first day alone runs up to the previous trading day`() {
        assertEquals(RunArgs(from = day(21)), RunArgs.parse(arrayOf("--from", "2026-10-21"), today))
    }

    @Test
    fun `the same day at both ends scans it alone, whatever the order of the options`() {
        val one = RunArgs(from = day(21), to = day(21))

        assertEquals(one, RunArgs.parse(arrayOf("--from", "2026-10-21", "--to", "2026-10-21"), today))
        assertEquals(one, RunArgs.parse(arrayOf("--to", "2026-10-21", "--from", "2026-10-21"), today))
    }

    @Test
    fun `a range running backwards is refused`() {
        assertFailsWith<IllegalArgumentException> {
            RunArgs.parse(arrayOf("--from", "2026-10-22", "--to", "2026-10-21"), today)
        }
    }

    @Test
    fun `today or later is refused — the job reads end-of-day data`() {
        assertFailsWith<IllegalArgumentException> { RunArgs.parse(arrayOf("--from", "2026-10-25"), today) }
        assertFailsWith<IllegalArgumentException> {
            RunArgs.parse(arrayOf("--from", "2026-10-21", "--to", "2026-10-25"), today)
        }
    }

    @Test
    fun `a last day without a first, or an unknown option, is refused`() {
        assertFailsWith<IllegalArgumentException> { RunArgs.parse(arrayOf("--to", "2026-10-21"), today) }
        assertFailsWith<IllegalArgumentException> { RunArgs.parse(arrayOf("--day", "2026-10-21"), today) }
    }

    private fun day(dayOfMonth: Int) = LocalDate.of(2026, 10, dayOfMonth)
}
