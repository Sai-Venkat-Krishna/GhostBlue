package com.saivenkatkrishna.ghostblue.kit

import com.saivenkatkrishna.ghostblue.kit.api.APIClientConfiguration
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogSink
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLogSink
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import java.util.UUID

/** Reads a file from src/test/resources. */
fun resource(path: String): ByteArray =
    requireNotNull(object {}.javaClass.getResourceAsStream("/$path")) { "Missing test resource $path" }.readBytes()

fun fixture(name: String): ByteArray = resource("fixtures/hyundai-usa/$name")

val testAccountId: UUID = UUID.fromString("6F1C2E1A-0B6B-4C61-9E39-5F0D2A4B7C11")

fun configuration(
    username: String = "someone@example.com",
    password: String = "hunter2",
    pin: String = "1234",
    brand: Brand = Brand.HYUNDAI,
    logSink: HTTPLogSink? = null,
    redactPII: Boolean = true,
) = APIClientConfiguration(
    region = Region.USA,
    brand = brand,
    username = username,
    password = password,
    pin = pin,
    accountId = testAccountId,
    logSink = logSink,
    redactPII = redactPII,
)

fun vehicle(fuelType: FuelType, generation: Int = 3) = Vehicle(
    vin = "KM8KRDAF5PU000001",
    regId = "H00000000000000001",
    model = "IONIQ 5",
    accountId = testAccountId,
    fuelType = fuelType,
    generation = generation,
    odometer = Distance(8500.0, Distance.Units.MILES),
)

/** Keeps the kit's console logging out of test output. */
fun silenceKitLogging() {
    BBLogger.sink = BBLogSink { _, _, _ -> }
}
