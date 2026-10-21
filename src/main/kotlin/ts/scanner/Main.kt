package ts.scanner

import kotlinx.coroutines.runBlocking

fun main() =
    runBlocking {
        val notifier = notifierFromEnv()
        try {
            MassiveProvider.fromEnv().close()
            notifier.send("ts-scanner: configuration loaded, no scan wired yet")
        } finally {
            notifier.close()
        }
    }
