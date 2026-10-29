package com.saivenkatkrishna.ghostblue.kit

import com.saivenkatkrishna.ghostblue.kit.api.SensitiveDataRedactor
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.KitJson
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.kit.model.formatWholeNumber
import com.saivenkatkrishna.ghostblue.kit.model.swiftDescription
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Checks against output recorded from the Swift code itself
 * (src/test/resources/golden, produced by compiling BetterBlueKit's sources).
 */
class SwiftParityTest {
    private fun golden(name: String): JsonObject =
        Json.parseToJsonElement(resource("golden/$name").decodeToString()).jsonObject

    @Test
    fun `log redaction matches Swift`() {
        val golden = golden("redaction.json")
        for (case in golden["strings"]!!.jsonArray.map { it.jsonObject }) {
            val input = case["input"]!!.jsonPrimitive.content
            assertEquals(case["expected"]!!.jsonPrimitive.content, SensitiveDataRedactor.redact(input), input)
        }
        val headers = golden["headers"]!!.jsonObject
        val input = headers["input"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val expected = headers["expected"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(expected, SensitiveDataRedactor.redactHeaders(input))
    }

    @Test
    fun `double descriptions match Swift`() {
        for (case in golden("swift-formatting.json")["description"]!!.jsonArray.map { it.jsonObject }) {
            val value = case["value"]!!.jsonPrimitive.double
            assertEquals(case["text"]!!.jsonPrimitive.content, swiftDescription(value), "$value")
        }
    }

    @Test
    fun `trip durations match Swift`() {
        for (case in golden("swift-formatting.json")["duration"]!!.jsonArray.map { it.jsonObject }) {
            val seconds = case["seconds"]!!.jsonPrimitive.int
            assertEquals(case["text"]!!.jsonPrimitive.content, trip(seconds).formattedDuration, "$seconds s")
        }
    }

    @Test
    fun `whole-number formatting matches Swift`() {
        for (case in golden("swift-formatting.json")["whole"]!!.jsonArray.map { it.jsonObject }) {
            val value = case["value"]!!.jsonPrimitive.double
            assertEquals(case["text"]!!.jsonPrimitive.content, formatWholeNumber(value), "$value")
        }
    }

    @Test
    fun `statuses encoded by Swift decode and re-encode the same`() {
        val swiftJson = resource("golden/swift-vehicle-status.json").decodeToString()
        val status = KitJson.decodeFromString<VehicleStatus>(swiftJson)
        assertEquals(63.0, status.evStatus?.evRange?.percentage)
        assertEquals(VehicleStatus.PlugType.AC_CHARGER, status.evStatus?.plugType)
        assertEquals(5700L, status.evStatus?.chargeTimeSeconds)
        assertEquals(Instant.parse("2025-07-06T14:22:10Z"), status.syncDate)

        val reencoded = Json.parseToJsonElement(KitJson.encodeToString(status))
        assertEquals(normalize(Json.parseToJsonElement(swiftJson)), normalize(reencoded))
    }

    /** Numbers compared by value (Swift writes 72, Kotlin 72.0). */
    private fun normalize(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { normalize(it.value) }
        is kotlinx.serialization.json.JsonArray -> element.map(::normalize)
        is kotlinx.serialization.json.JsonPrimitive ->
            if (element.isString) element.content else element.content.toDoubleOrNull() ?: element.content
    }

    private fun trip(seconds: Int) = EVTripDetail(
        distance = 1.0, odometer = 1.0, accessoriesEnergy = 0, totalEnergyUsed = 0, regenEnergy = 0,
        climateEnergy = 0, drivetrainEnergy = 0, batteryCareEnergy = 0, startDate = Instant.EPOCH,
        durationSeconds = seconds, avgSpeed = 0.0, maxSpeed = 0.0,
    )
}
