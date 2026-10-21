package ts.scanner

import io.github.cdimascio.dotenv.dotenv

// ignoreIfMissing: production has no .env, Secret Manager injects real environment variables.
private val dotenv by lazy { dotenv { ignoreIfMissing = true } }

/** Looks a key up in `.env` first, then in the process environment. */
fun envOrNull(key: String): String? = dotenv[key] ?: System.getenv(key)
