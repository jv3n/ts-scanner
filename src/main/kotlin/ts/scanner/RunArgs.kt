package ts.scanner

import java.time.LocalDate

/**
 * What a run scans (#44) : the previous trading day by default ; with `--from`, every trading day
 * from it to `--to` — both included — or to the previous trading day without one.
 */
data class RunArgs(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    companion object {
        private const val USAGE = "usage: ts-scanner [--from YYYY-MM-DD [--to YYYY-MM-DD]]"

        /** Refuses, before any request, a range that ends today or later, or that runs backwards. */
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
            require(from == null || from < today) { "$from is not a past day — the job reads end-of-day data" }
            require(to == null || to < today) { "$to is not a past day — the job reads end-of-day data" }
            require(to == null || !to.isBefore(from)) { "--to $to is before --from $from" }
            return RunArgs(from, to)
        }
    }
}
