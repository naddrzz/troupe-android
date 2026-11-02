package dev.troupe.android.core.net

/**
 * Where plain `http://` may carry a server token: loopback, RFC 1918 private ranges,
 * 100.64.0.0/10 (CGNAT, used by Tailscale), `.local` and `.ts.net` hosts. Everything else
 * needs `https://`. Android's network security config cannot express IP ranges, so this is
 * the enforcement point: at pairing, and on every request of [troupeHttpClient].
 * Only literal IPs are checked; no DNS lookups.
 */
object TransportPolicy {
    fun isCleartextAllowed(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost" || h == "::1") return true
        if (h.length > ".local".length && h.endsWith(".local")) return true
        if (h.length > ".ts.net".length && h.endsWith(".ts.net")) return true
        val ip = ipv4(h) ?: return false
        val (a, b) = ip
        return a == 127 ||
            a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }

    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            // ASCII digits only, no leading zero: `010` is octal to some resolvers.
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' } || (part.length > 1 && part[0] == '0')) return null
            part.toInt().takeIf { it <= 255 } ?: return null
        }
        return octets[0] to octets[1]
    }
}
