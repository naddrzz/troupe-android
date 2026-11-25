package dev.troupe.android.pairing

import dev.troupe.android.core.net.TransportPolicy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/** A Troupe Server's base URL, always ending in `/` and keeping any reverse-proxy path prefix. */
data class ServerAddress(val baseUrl: HttpUrl) {
    val host: String get() = baseUrl.host

    fun httpUrl(path: String): HttpUrl = baseUrl.resolve(path) ?: error("Bad path: $path")

    /** `http` becomes `ws`, `https` becomes `wss`. */
    fun wsUrl(path: String): String = "ws" + httpUrl(path).toString().removePrefix("http")

    companion object {
        /** Restores an address saved by [toString] of its [baseUrl]. */
        fun of(baseUrl: String): ServerAddress = ServerAddress(baseUrl.toHttpUrlOrNull() ?: error("Bad base URL"))
    }
}

/**
 * A parsed Troupe Server link or console paste. Supports:
 * - Gated links (`https://host/#token=<token>`, Cloudflare remote access, LAN/Tailscale)
 * - Ungated links (`http://127.0.0.1:8080`, `http://192.168.1.5:8080`)
 * - First-run setup links (`http://0.0.0.0:8080/?setup=<setupToken>`)
 * - Multi-line terminal output containing a Troupe URL
 * - Deep links to a chat session (`#/c/<sessionId>`)
 * - Wildcard bind hosts (`0.0.0.0`, `::`) detected for user host substitution
 */
data class ParsedServerLink(
    val address: ServerAddress,
    val token: String? = null,
    val setupToken: String? = null,
    val chatSessionId: String? = null,
    val isWildcardHost: Boolean = false,
) {
    override fun toString(): String =
        "ParsedServerLink(address=$address, hasToken=${!token.isNullOrEmpty()}, hasSetupToken=${!setupToken.isNullOrEmpty()}, chatSessionId=$chatSessionId, isWildcardHost=$isWildcardHost)"
}

/** What pairing needs: where the server is and its bearer token (blank for ungated local servers). */
data class PairingInput(val address: ServerAddress, val token: String) {
    override fun toString(): String = "PairingInput(address=$address, token=<redacted>)"
}

sealed class PairingError : Exception() {
    data object InvalidUrl : PairingError() {
        private fun readResolve(): Any = InvalidUrl
    }

    data object MissingToken : PairingError() {
        private fun readResolve(): Any = MissingToken
    }

    data class CleartextNotAllowed(val host: String) : PairingError()
}

object TroupeServerLink {
    private val URL_REGEX = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val TRAILING_NOISE = charArrayOf(')', ']', '}', ',', '.', ';', '\'', '"', '`')

    /**
     * Parses any pasted Troupe link, bare `host:port`, or multi-line terminal banner.
     * When the URL uses a wildcard bind (`0.0.0.0` or `::`), pass [replacementHost] (e.g. LAN/Tailscale IP)
     * to rewrite the host while preserving scheme, port, path, `?setup=`, and `#token=`.
     */
    fun parse(raw: String, replacementHost: String? = null): Result<ParsedServerLink> = runCatching {
        val candidate = extractUrlCandidate(raw) ?: throw PairingError.InvalidUrl
        val withScheme = if ("://" in candidate) candidate else "http://$candidate"
        val initialUrl = withScheme.toHttpUrlOrNull() ?: throw PairingError.InvalidUrl

        val cleanReplacement = replacementHost?.trim()?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() }
        val url = if (isWildcard(initialUrl.host) && cleanReplacement != null) {
            runCatching { initialUrl.newBuilder().host(cleanReplacement).build() }
                .getOrElse { throw PairingError.InvalidUrl }
        } else {
            initialUrl
        }

        val wildcard = isWildcard(url.host)
        if (!wildcard && url.scheme == "http" && !TransportPolicy.isCleartextAllowed(url.host)) {
            throw PairingError.CleartextNotAllowed(url.host)
        }

        val setupToken = url.queryParameter("setup")?.trim()?.takeIf { it.isNotEmpty() }
        val (token, chatSessionId) = parseFragment(url.encodedFragment)
        val address = normalizeAddress(url)
        ParsedServerLink(
            address = address,
            token = token,
            setupToken = setupToken,
            chatSessionId = chatSessionId,
            isWildcardHost = wildcard,
        )
    }

    /** Returns true for wildcard server bind addresses that a phone cannot dial directly. */
    fun isWildcard(host: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return normalized == "0.0.0.0" || normalized == "::" || normalized == "0:0:0:0:0:0:0:0"
    }

    private fun extractUrlCandidate(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val match = URL_REGEX.find(trimmed)
        if (match != null) {
            return match.value.trimEnd(*TRAILING_NOISE)
        }
        // Single-line bare host[:port][/path] without scheme
        if ('\n' !in trimmed && '\r' !in trimmed && !trimmed.any(Char::isWhitespace)) {
            return trimmed.trimEnd(*TRAILING_NOISE)
        }
        return null
    }

    private fun parseFragment(encodedFragment: String?): Pair<String?, String?> {
        if (encodedFragment.isNullOrEmpty()) return null to null
        var token: String? = null
        var chatSessionId: String? = null
        for (part in encodedFragment.split('&')) {
            when {
                part.startsWith("token=") -> {
                    val rawVal = part.removePrefix("token=")
                    token = URLDecoder.decode(rawVal.replace("+", "%2B"), "UTF-8")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                }
                part.startsWith("/c/") -> {
                    val id = part.removePrefix("/c/").substringBefore('?').substringBefore('/').trim()
                    if (id.isNotEmpty()) {
                        chatSessionId = URLDecoder.decode(id.replace("+", "%2B"), "UTF-8")
                    }
                }
            }
        }
        return token to chatSessionId
    }

    private fun normalizeAddress(url: HttpUrl): ServerAddress {
        val builder = url.newBuilder().query(null).fragment(null)
        if (!url.encodedPath.endsWith("/")) builder.addPathSegment("")
        return ServerAddress(builder.build())
    }
}

object PairingLink {
    /** Parses a desktop pairing link such as `https://host/prefix/#token=…`, tolerating copy-paste noise. */
    fun parse(raw: String): Result<PairingInput> = runCatching {
        val url = raw.trim().toHttpUrlOrNull() ?: throw PairingError.InvalidUrl
        val address = address(url)
        val token = url.encodedFragment
            ?.split('&')
            ?.firstOrNull { it.startsWith("token=") }
            ?.removePrefix("token=")
            ?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8").trim() }
        if (token.isNullOrEmpty()) throw PairingError.MissingToken
        PairingInput(address, token)
    }

    /** Typed address and token. An address without a scheme gets `http://`. */
    fun fromManual(url: String, token: String): Result<PairingInput> = runCatching {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) throw PairingError.InvalidUrl
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val parsed = withScheme.toHttpUrlOrNull() ?: throw PairingError.InvalidUrl
        val address = address(parsed)
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw PairingError.MissingToken
        PairingInput(address, cleanToken)
    }

    private fun address(url: HttpUrl): ServerAddress {
        if (url.scheme == "http" && !TransportPolicy.isCleartextAllowed(url.host)) {
            throw PairingError.CleartextNotAllowed(url.host)
        }
        val builder = url.newBuilder().query(null).fragment(null)
        if (!url.encodedPath.endsWith("/")) builder.addPathSegment("")
        return ServerAddress(builder.build())
    }
}
