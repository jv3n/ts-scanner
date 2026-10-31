package ts.scanner

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a run scans, read off its arguments (#44) — refused before any request when it cannot be
 * right : a quarter of an hour of reading per day is spent before a bad range would show. A day not
 * over yet is not such a mistake : the run scans what it can, and says so (#57).
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

    // Tilt, 30/10 : a range ending on a day not over stopped on a stack trace, its past days unread.
    @Test
    fun `a last day not over yet stops the run at the previous trading day, and says so`() {
        val run = RunArgs.parse(arrayOf("--from", "2026-10-21", "--to", "2026-10-25"), today)

        assertEquals(day(21), run.from)
        assertNull(run.to)
        assertContains(assertNotNull(run.note), "2026-10-25")
    }

    @Test
    fun `a first day not over yet scans the previous trading day, and says so`() {
        val alone = RunArgs.parse(arrayOf("--from", "2026-10-25"), today)
        val later = RunArgs.parse(arrayOf("--from", "2026-10-26", "--to", "2026-10-27"), today)

        assertEquals(RunArgs(note = alone.note), alone)
        assertEquals(RunArgs(note = later.note), later)
        assertContains(assertNotNull(alone.note), "2026-10-25")
    }

    @Test
    fun `a range wholly in the past carries no note`() {
        assertNull(RunArgs.parse(arrayOf("--from", "2026-10-21", "--to", "2026-10-24"), today).note)
    }

    @Test
    fun `a range running backwards is refused, even past today`() {
        assertFailsWith<IllegalArgumentException> {
            RunArgs.parse(arrayOf("--from", "2026-10-27", "--to", "2026-10-26"), today)
        }
    }

    @Test
    fun `a last day without a first, or an unknown option, is refused`() {
        assertFailsWith<IllegalArgumentException> { RunArgs.parse(arrayOf("--to", "2026-10-21"), today) }
        assertFailsWith<IllegalArgumentException> { RunArgs.parse(arrayOf("--day", "2026-10-21"), today) }
    }

    private fun day(dayOfMonth: Int) = LocalDate.of(2026, 10, dayOfMonth)
}
