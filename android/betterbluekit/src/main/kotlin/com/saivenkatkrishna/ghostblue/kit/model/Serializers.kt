package com.saivenkatkrishna.ghostblue.kit.model

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Dates as ISO 8601 in UTC to the second ("2025-07-06T14:22:10Z"), the form
 * Swift's `JSONEncoder` writes with `.iso8601`. Fractional seconds are
 * accepted when decoding.
 */
object Iso8601InstantSerializer : KSerializer<Instant> {
    private val writer = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    override val descriptor = PrimitiveSerialDescriptor("Iso8601Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(writer.format(value.truncatedTo(ChronoUnit.SECONDS)))
    }

    override fun deserialize(decoder: Decoder): Instant =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(decoder.decodeString(), Instant::from)
}

/** UUIDs as uppercase strings, as Swift encodes them. */
object UppercaseUUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UppercaseUUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString().uppercase())
    }

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** JSON settings that match Swift's synthesized `Codable`: nils left out, defaults written. */
val KitJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/**
 * Swift's `Double.description`: shortest round-trip digits, always with a
 * fraction ("12345.0"), and exponent form outside 1e-4 ..< 2^53. Used where
 * Swift builds identifiers from doubles, so keys match between platforms.
 */
fun swiftDescription(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"
    val magnitude = kotlin.math.abs(value)
    val shortest = java.math.BigDecimal(value.toString())
    if (magnitude >= 9_007_199_254_740_992.0 || magnitude < 1e-4) {
        val unscaled = shortest.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
        val exponent = shortest.precision() - shortest.scale() - 1
        val mantissa = if (unscaled.length == 1) unscaled else "${unscaled[0]}.${unscaled.substring(1)}"
        val sign = if (value < 0) "-" else ""
        val expSign = if (exponent < 0) "-" else "+"
        return "$sign${mantissa}e$expSign${kotlin.math.abs(exponent).toString().padStart(2, '0')}"
    }
    val plain = shortest.stripTrailingZeros().toPlainString()
    return if (plain.contains('.')) plain else "$plain.0"
}
