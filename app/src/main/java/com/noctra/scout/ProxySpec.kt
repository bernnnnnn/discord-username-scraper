package com.noctra.scout

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * One proxy the availability checks can be routed through. The point is Discord's public check is
 * throttled per source IP, so sending consecutive checks out through different proxies spreads the
 * load across IPs and lets the scan sustain a faster overall pace before any single IP gets a 429.
 *
 * Accepted spelling of a single entry (one per line, or comma-separated):
 *   - `host:port`
 *   - `host:port:user:pass`                (the format most proxy vendors hand out)
 *   - `scheme://host:port`
 *   - `scheme://user:pass@host:port`
 * where `scheme` is `http`/`https` (an HTTP CONNECT proxy) or `socks`/`socks4`/`socks5`.
 * A missing scheme is treated as HTTP. Blank lines and lines beginning with `#` are ignored.
 */
data class ProxySpec(
    val type: Proxy.Type,
    val host: String,
    val port: Int,
    val user: String?,
    val pass: String?
) {
    /** True when the entry carries credentials the proxy will demand. */
    val hasAuth: Boolean get() = !user.isNullOrEmpty()

    /** host:port, for the status footer and logs — never the credentials. */
    val label: String get() = "$host:$port"

    /**
     * Left unresolved on purpose: DNS for the target then happens at the proxy, not on this
     * device, which is what you want when the whole reason for the proxy is to not originate the
     * request yourself.
     */
    fun toProxy(): Proxy = Proxy(type, InetSocketAddress.createUnresolved(host, port))

    companion object {
        /** Parse a whole textarea of entries, skipping anything unparseable rather than failing. */
        fun parseList(raw: String): List<ProxySpec> =
            raw.split('\n', ',')
                .mapNotNull { parse(it) }

        /** Parse one entry, or null if it is blank, a comment, or malformed. */
        fun parse(raw: String): ProxySpec? {
            var s = raw.trim()
            if (s.isEmpty() || s.startsWith("#")) return null

            var type = Proxy.Type.HTTP
            val schemeSep = s.indexOf("://")
            if (schemeSep >= 0) {
                val scheme = s.substring(0, schemeSep).lowercase()
                s = s.substring(schemeSep + 3)
                type = if (scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
            }

            var user: String? = null
            var pass: String? = null
            val at = s.lastIndexOf('@')
            if (at >= 0) {
                val creds = s.substring(0, at)
                s = s.substring(at + 1)
                val colon = creds.indexOf(':')
                if (colon >= 0) {
                    user = creds.substring(0, colon)
                    pass = creds.substring(colon + 1)
                } else {
                    user = creds
                    pass = ""
                }
            }

            val parts = s.split(":")
            val host: String
            val port: Int
            when {
                // host:port:user:pass — the flat vendor format, only when no @-credentials seen.
                parts.size >= 4 && user == null -> {
                    host = parts[0]
                    port = parts[1].toIntOrNull() ?: return null
                    user = parts[2]
                    pass = parts.subList(3, parts.size).joinToString(":")
                }
                parts.size == 2 -> {
                    host = parts[0]
                    port = parts[1].toIntOrNull() ?: return null
                }
                else -> return null
            }

            if (host.isBlank() || port !in 1..65535) return null
            return ProxySpec(type, host, port, user, pass)
        }
    }
}
