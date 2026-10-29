package com.saivenkatkrishna.ghostblue.data.account

import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.swiftDescription
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `BBAccount.serializedAuthToken` in the form iOS stores it: a default
 * `JSONEncoder`, which writes `expiresAt` as seconds since 2001-01-01. Keeping
 * that form lets a backup carry a live session between the two apps.
 */
object SwiftAuthToken {
    private val referenceDate: Instant = Instant.parse("2001-01-01T00:00:00Z")

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(token: AuthToken): String = buildJsonObject {
        put("accessToken", JsonPrimitive(token.accessToken))
        put("refreshToken", JsonPrimitive(token.refreshToken))
        put("expiresAt", JsonUnquotedLiteral(swiftDescription(secondsSinceReference(token.expiresAt))))
    }.toString()

    /** Null for anything that isn't a token. An ISO 8601 `expiresAt` is accepted too. */
    fun decode(text: String): AuthToken? = runCatching {
        val json = Json.parseToJsonElement(text) as JsonObject
        val expires = json.getValue("expiresAt").jsonPrimitive
        val expiresAt = expires.doubleOrNull?.let(::fromSecondsSinceReference)
            ?: DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(expires.content, Instant::from)
        AuthToken(
            accessToken = json.getValue("accessToken").jsonPrimitive.content,
            refreshToken = json.getValue("refreshToken").jsonPrimitive.content,
            expiresAt = expiresAt,
        )
    }.getOrNull()

    private fun secondsSinceReference(instant: Instant): Double =
        (instant.epochSecond - referenceDate.epochSecond) + instant.nano / 1_000_000_000.0

    private fun fromSecondsSinceReference(seconds: Double): Instant {
        val exact = BigDecimal(seconds)
        val whole = exact.setScale(0, RoundingMode.FLOOR)
        val nanos = exact.subtract(whole).movePointRight(9).setScale(0, RoundingMode.HALF_EVEN).toLong()
        return referenceDate.plusSeconds(whole.toLong()).plusNanos(nanos)
    }
}
