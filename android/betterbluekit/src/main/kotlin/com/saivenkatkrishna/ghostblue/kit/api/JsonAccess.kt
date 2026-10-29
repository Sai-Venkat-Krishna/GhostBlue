package com.saivenkatkrishna.ghostblue.kit.api

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Reads values out of API JSON the way the Swift parsers do with
 * JSONSerialization casts, so both platforms accept and reject the same
 * responses:
 *  - `string`  is `as? String`: JSON strings only.
 *  - `int`     is `as? Int`: JSON numbers with a whole value (true/false read as 1/0).
 *  - `double`  is `as? Double`: any JSON number (true/false read as 1/0).
 *  - `bool`    is `as? Bool`: true/false, or the numbers 0 and 1.
 *  - `numberInt` / `numberDouble` are `extractNumber`: a number, or a string that parses as one.
 */

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.objects(key: String): List<JsonObject>? =
    array(key)?.let { array -> if (array.all { it is JsonObject }) array.map { it as JsonObject } else null }

internal fun JsonObject.string(key: String): String? = this[key].asString()
internal fun JsonObject.int(key: String): Int? = this[key].asInt()
internal fun JsonObject.double(key: String): Double? = this[key].asDouble()
internal fun JsonObject.bool(key: String): Boolean? = this[key].asBool()
internal fun JsonObject.numberInt(key: String): Int? = this[key].extractInt()
internal fun JsonObject.numberDouble(key: String): Double? = this[key].extractDouble()

private fun JsonElement?.primitive(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

internal fun JsonElement?.asString(): String? = primitive()?.takeIf { it.isString }?.content

internal fun JsonElement?.asDouble(): Double? {
    val p = primitive()?.takeUnless { it.isString } ?: return null
    p.booleanOrNull?.let { return if (it) 1.0 else 0.0 }
    return p.content.toDoubleOrNull()
}

internal fun JsonElement?.asInt(): Int? {
    val p = primitive()?.takeUnless { it.isString } ?: return null
    p.booleanOrNull?.let { return if (it) 1 else 0 }
    return wholeNumber(p.content)
}

internal fun JsonElement?.asBool(): Boolean? {
    val p = primitive()?.takeUnless { it.isString } ?: return null
    p.booleanOrNull?.let { return it }
    return when (p.content.toDoubleOrNull()) {
        0.0 -> false
        1.0 -> true
        else -> null
    }
}

internal fun JsonElement?.extractInt(): Int? = asInt() ?: asString()?.toIntOrNull()

internal fun JsonElement?.extractDouble(): Double? = asDouble() ?: asString()?.let(::parseSwiftDouble)

/** A JSON number's value as an Int when it is whole and in range, like NSNumber's `as? Int`. */
private fun wholeNumber(content: String): Int? {
    content.toIntOrNull()?.let { return it }
    val decimal = content.toBigDecimalOrNull() ?: return null
    return try {
        decimal.stripTrailingZeros().intValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}

private val decimalPattern = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

/** `Double(String)`: decimal or exponent notation only (Java would also take "5d" or hex). */
private fun parseSwiftDouble(text: String): Double? =
    if (decimalPattern.matches(text)) text.toDoubleOrNull() else null

private fun String.toBigDecimalOrNull(): BigDecimal? = try {
    BigDecimal(this)
} catch (_: NumberFormatException) {
    null
}
