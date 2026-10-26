package ru.igrolov.app

/** In-memory, bounded image diagnostics; no account, profile, query or token data. */
object SteamArtworkDiagnostics {
    private val events = java.util.ArrayDeque<String>()
    @Synchronized fun record(appId: Int?, url: String, error: Throwable? = null) {
        val host = runCatching { java.net.URL(url).host }.getOrDefault("unknown")
        val detail = if (error == null) "decoded" else
            generateSequence(error) { it.cause }.take(4).joinToString(" -> ") {
                it.javaClass.simpleName + ": " + it.message.orEmpty().take(220)
            }
        events.addLast("app=" + appId + " host=" + host + " " + detail)
        while (events.size > 120) events.removeFirst()
    }
    @Synchronized fun report(): String =
        "Igrolov artwork diagnostics; Android " + android.os.Build.VERSION.SDK_INT +
            "\n" + events.joinToString("\n")
}
