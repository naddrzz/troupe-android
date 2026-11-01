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

/** What pairing needs: where the server is and its bearer token. */
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

object PairingLink {
    /** Parses a desktop pairing link such as `https://host/prefix/#token=…`, tolerating copy-paste noise. */
    fun parse(raw: String): Result<PairingInput> = runCatching {
        val url = raw.trim().toHttpUrlOrNull() ?: throw PairingError.InvalidUrl
        val address = address(url)
        val token = url.encodedFragment
            ?.split('&')
            ?.firstOrNull { it.startsWith("token=") }
            ?.removePrefix("token=")
            ?.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8).trim() }
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
