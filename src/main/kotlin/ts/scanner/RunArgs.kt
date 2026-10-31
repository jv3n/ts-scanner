package ts.scanner

import java.time.LocalDate

/**
 * What a run scans (#44) : the previous trading day by default ; with `--from`, every trading day
 * from it to `--to` — both included — or to the previous trading day without one. [note] says when
 * the run is shorter than what was typed.
 */
data class RunArgs(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val note: String? = null,
) {
    companion object {
        private const val USAGE = "usage: ts-scanner [--from YYYY-MM-DD [--to YYYY-MM-DD]]"

        /**
         * Refuses, before any request, a range that runs backwards. A day not over yet is no mistake,
         * only a limit of the end-of-day data (#57) : the run stops at the previous trading day.
         */
        fun parse(
            args: Array<String>,
            today: LocalDate,
        ): RunArgs {
            require(args.size % 2 == 0) { USAGE }
            val options = args.toList().chunked(2).associate { (key, value) -> key to value }
            require(options.keys.all { it == "--from" || it == "--to" } && options.size == args.size / 2) { USAGE }
            val from = options["--from"]?.let(LocalDate::parse)
            val to = options["--to"]?.let(LocalDate::parse)
            require(to == null || from != null) { "--to needs a --from — $USAGE" }
            require(to == null || !to.isBefore(from)) { "--to $to is before --from $from" }
            return when {
                from != null && from >= today -> RunArgs(note = "$from is not over — scanning the previous trading day")
                to != null && to >= today -> RunArgs(from, note = "$to is not over — scanning up to the previous trading day")
                else -> RunArgs(from, to)
            }
        }
    }
}
