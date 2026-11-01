package dev.troupe.android.core.protocol

import kotlinx.serialization.json.Json

/** Protocol JSON: unknown fields from newer servers are ignored. */
val TroupeJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
