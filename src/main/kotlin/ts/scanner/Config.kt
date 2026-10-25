package ts.scanner

import io.github.cdimascio.dotenv.dotenv

// ignoreIfMissing: production has no .env, Secret Manager injects real environment variables.
private val dotenv by lazy { dotenv { ignoreIfMissing = true } }

/**
 * Looks a key up in `.env` first, then in the process environment. A blank value counts as none :
 * an empty `TRADE_URL=` in `.env` would otherwise hide the one Tilt passes.
 */
fun envOrNull(key: String): String? = dotenv[key]?.takeIf { it.isNotBlank() } ?: System.getenv(key)?.takeIf { it.isNotBlank() }
