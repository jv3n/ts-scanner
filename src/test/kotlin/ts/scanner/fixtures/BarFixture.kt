package ts.scanner.fixtures

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ts.scanner.Bar
import ts.scanner.SessionBars
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * A real day of 1-min bars, as Massive served it, kept under `src/test/resources/bars/` — the
 * detectors are tested on real charts, never on hand-drawn series.
 */
@Serializable
data class BarFixture(
    val ticker: String,
    val date: String,
    val previousClose: Double?,
    val bars: List<FixtureBar>,
) {
    fun toSession() =
        SessionBars(
            ticker = ticker,
            date = LocalDate.parse(date),
            previousClose = previousClose,
            bars = bars.map { Bar(LocalTime.parse(it.t), it.o, it.h, it.l, it.c, it.v) },
        )

    companion object {
        val json =
            Json {
                prettyPrint = true
                prettyPrintIndent = " "
            }

        fun of(session: SessionBars) =
            BarFixture(
                ticker = session.ticker,
                date = session.date.toString(),
                previousClose = session.previousClose,
                bars = session.bars.map { FixtureBar(it.time.toString(), it.open, it.high, it.low, it.close, it.volume) },
            )

        fun fileOf(
            ticker: String,
            date: String,
        ) = "$date-$ticker.json"

        /** The fixture of [ticker] on [date], read off the test classpath. */
        fun load(
            ticker: String,
            date: String,
        ): SessionBars {
            val resource =
                BarFixture::class.java.getResource("/bars/${fileOf(ticker, date)}")
                    ?: error("no fixture for $ticker on $date — ./gradlew fetchFixtures -Pcases=…")
            return json.decodeFromString<BarFixture>(resource.readText()).toSession()
        }

        fun write(
            dir: File,
            session: SessionBars,
        ) = File(dir, fileOf(session.ticker, session.date.toString())).writeText(json.encodeToString(of(session)) + "\n")
    }
}

@Serializable
data class FixtureBar(
    val t: String,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Long,
)
