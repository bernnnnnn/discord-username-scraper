package com.noctra.scout

import android.content.Context
import android.content.SharedPreferences

/** Small SharedPreferences wrapper. All scraper settings + resume state live here. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("noctra", Context.MODE_PRIVATE)

    /** Index into the shuffled name space; survives process death so we never re-check. */
    var cursor: Long
        get() = sp.getLong(KEY_CURSOR, 0L)
        set(v) = sp.edit().putLong(KEY_CURSOR, v).apply()

    /** Which character set the 4-char names are drawn from. */
    var charsetId: Int
        get() = sp.getInt(KEY_CHARSET, NameSpace.CHARSET_ALNUM)
        set(v) = sp.edit().putInt(KEY_CHARSET, v).apply()

    /** Delay between requests, in milliseconds. */
    var delayMs: Int
        get() = sp.getInt(KEY_DELAY, DEFAULT_DELAY_MS)
        set(v) = sp.edit().putInt(KEY_DELAY, v.coerceIn(MIN_DELAY_MS, 60_000)).apply()

    /** Stop automatically once this many available names have been found. 0 = never stop. */
    var stopAfter: Int
        get() = sp.getInt(KEY_STOP_AFTER, 0)
        set(v) = sp.edit().putInt(KEY_STOP_AFTER, v.coerceAtLeast(0)).apply()

    /** Auto-tune the delay: back off on 429s, creep back toward [delayMs] when they stop. */
    var autoPace: Boolean
        get() = sp.getBoolean(KEY_AUTO_PACE, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_PACE, v).apply()

    /** Optional Discord user token. Stored on this device only; blank = unauthenticated checks. */
    var token: String
        get() = sp.getString(KEY_TOKEN, "").orEmpty()
        set(v) = sp.edit().putString(KEY_TOKEN, sanitizeToken(v)).apply()

    /**
     * Optional proxy list (one per line, or comma-separated). Checks rotate across these so the
     * per-IP rate limit is spread over several addresses. Stored on this device only.
     */
    var proxies: String
        get() = sp.getString(KEY_PROXIES, "").orEmpty()
        set(v) = sp.edit().putString(KEY_PROXIES, v.trim()).apply()

    /** The proxy list parsed into usable specs; unparseable entries are dropped. */
    fun proxyList(): List<ProxySpec> = ProxySpec.parseList(proxies)

    /** True while the service is meant to be running (used to restore after a process restart). */
    var running: Boolean
        get() = sp.getBoolean(KEY_RUNNING, false)
        set(v) = sp.edit().putBoolean(KEY_RUNNING, v).apply()

    fun nameSpace(): NameSpace = NameSpace.of(charsetId)

    /**
     * Cleans up a pasted token. localStorage holds the value JSON-encoded, so copying it out of a
     * storage viewer brings the surrounding quotes along, and Discord rejects the header as
     * malformed. Whitespace from a wrapped copy and a stray "Bearer " prefix get the same treatment.
     */
    private fun sanitizeToken(raw: String): String {
        var t = raw.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length - 1)
        }
        if (t.startsWith("Bearer ", ignoreCase = true)) {
            t = t.substring(7)
        }
        return t.filterNot { it.isWhitespace() }
    }

    fun resetProgress() {
        cursor = 0L
    }

    companion object {
        const val DEFAULT_DELAY_MS = 1200
        const val MIN_DELAY_MS = 250

        private const val KEY_CURSOR = "cursor"
        private const val KEY_CHARSET = "charset"
        private const val KEY_DELAY = "delay_ms"
        private const val KEY_STOP_AFTER = "stop_after"
        private const val KEY_AUTO_PACE = "auto_pace"
        private const val KEY_TOKEN = "token"
        private const val KEY_PROXIES = "proxies"
        private const val KEY_RUNNING = "running"
    }
}
