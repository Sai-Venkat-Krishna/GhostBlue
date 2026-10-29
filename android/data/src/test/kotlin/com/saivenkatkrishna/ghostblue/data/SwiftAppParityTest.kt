package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.account.SwiftAuthToken
import com.saivenkatkrishna.ghostblue.data.backup.BackupArchive
import com.saivenkatkrishna.ghostblue.data.backup.BackupTransfer
import com.saivenkatkrishna.ghostblue.data.charging.BatteryCapacityCatalog
import com.saivenkatkrishna.ghostblue.data.charging.ChargingCSVExporter
import com.saivenkatkrishna.ghostblue.data.charging.ChargingTariff
import com.saivenkatkrishna.ghostblue.data.charging.SDGESmartChargingSchedule
import com.saivenkatkrishna.ghostblue.data.charging.SmartChargingPreference
import com.saivenkatkrishna.ghostblue.data.charging.chargingDisplayState
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.vehicle.chargingCost
import com.saivenkatkrishna.ghostblue.data.vehicle.energyDelivered
import com.saivenkatkrishna.ghostblue.data.vehicle.energySource
import com.saivenkatkrishna.ghostblue.data.vehicle.updateStatus
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.KitJson
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Checks against golden files written by the iOS app's own code
 * (`tools/parity/app-parity.sh` runs `tools/parity/ios/AndroidParityGoldens.swift`
 * on a simulator): backups in both directions, charge-session detection,
 * the SDG&E schedule, tariff costs, battery defaults and the CSV export.
 */
@RunWith(RobolectricTestRunner::class)
class SwiftAppParityTest {
    private val databases = mutableListOf<GhostBlueDatabase>()

    private fun store() = testDatabase().also(databases::add)

    @After fun tearDown() = databases.forEach { it.close() }

    private fun golden(name: String): ByteArray =
        requireNotNull(javaClass.getResource("/golden/$name")) { "Missing golden $name; run tools/parity/app-parity.sh" }.readBytes()

    private fun goldenJson(name: String): JsonObject = Json.parseToJsonElement(golden(name).decodeToString()).jsonObject

    private fun JsonElement.doubleOrNullValue(): Double? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull

    private fun JsonElement.instantOrNull(): Instant? = doubleOrNullValue()?.let(::epoch)

    // MARK: - Backups

    @Test fun iPhoneBackupImportsCompletelyAndExportsUnchanged() = runTest {
        val bytes = golden("ios-backup.ghostbluebackup")
        val archive = BackupTransfer.readArchive(bytes.inputStream())
        val db = store()

        val summary = BackupTransfer.importArchive(archive, db)
        assertEquals(archive.accounts.size, summary.accountsAdded)
        assertEquals(archive.vehicles.size, summary.vehiclesAdded)
        assertEquals(archive.climatePresets.size, summary.presetsAdded)
        assertEquals(archive.chargeSessions.size, summary.chargeSessionsAdded)
        assertEquals(archive.trips.size, summary.tripsAdded)
        assertEquals(archive.remoteCommands.size, summary.remoteCommandsAdded)
        assertEquals(archive.maintenanceReminders.size, summary.remindersAdded)
        assertEquals(archive.automations.size, summary.automationsAdded)
        assertTrue(summary.orphanedVehicles.isEmpty())
        assertEquals(0, BackupTransfer.importArchive(archive, db).totalAdded)

        // The Energy tab shows what the iPhone showed.
        val expectations = goldenJson("ios-backup-expectations.json")
        val car = db.vehicles().byVin("KMHIOS00000000001")!!
        val sessions = db.chargeSessions().all()
        val expected = expectations.getValue("sessions").jsonArray.map { it.jsonObject }
        assertEquals(expected.map { it.getValue("id").jsonPrimitive.content }, sessions.map { it.id.toString().uppercase() })
        for ((session, values) in sessions.zip(expected)) {
            assertEquals(values.getValue("energyDelivered").jsonPrimitive.double, car.energyDelivered(session), 1e-9)
            assertNullableEquals(values.getValue("chargingCost").doubleOrNullValue(), car.chargingCost(session))
            assertEquals(values.getValue("energySource").jsonPrimitive.content, car.energySource(session))
            assertEquals(values.getValue("isMeaningful").jsonPrimitive.boolean, session.isMeaningful)
        }
        assertEquals(expectations.getValue("csv").jsonPrimitive.content, ChargingCSVExporter.csv(sessions, car))

        // The session the iPhone app logged in, still usable here.
        val account = db.accounts().all().first { it.username == "Driver@Example.com" }
        assertEquals(epoch(1_900_000_000.5), SwiftAuthToken.decode(account.serializedAuthToken!!)?.expiresAt)

        // Exporting again gives back the iPhone's file, apart from the new account ids.
        assertSameBackup(bytes, BackupArchive.export(db, "Pixel").encoded())
    }

    @Test fun androidBackupGoldenIsCurrent() = runTest {
        val db = store()
        AndroidParitySeed.seed(db)
        assertSameBackup(golden("android-backup.ghostbluebackup"), BackupArchive.export(db, "Pixel").encoded(), keepIds = true)
    }

    @Test fun androidBackupImportsOnTheIPhoneAsItDoesHere() = runTest {
        val archive = BackupTransfer.readArchive(golden("android-backup.ghostbluebackup").inputStream())
        val db = store()
        val summary = BackupTransfer.importArchive(archive, db)
        val onIPhone = goldenJson("android-backup-import.json")

        assertEquals(
            onIPhone.getValue("summary").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } },
            summary.rows.map { listOf(it.first, it.second) },
        )
        assertEquals(
            onIPhone.getValue("orphanedVehicles").jsonArray.map { it.jsonPrimitive.content },
            summary.orphanedVehicles,
        )
        assertEquals(0, onIPhone.getValue("addedOnSecondImport").jsonPrimitive.int)
        assertEquals(0, BackupTransfer.importArchive(archive, db).totalAdded)

        val vehicles = db.vehicles().all().associateBy { it.vin }
        val sessions = db.chargeSessions().all()
        val expected = onIPhone.getValue("sessions").jsonArray.map { it.jsonObject }
        assertEquals(expected.map { it.getValue("id").jsonPrimitive.content }, sessions.map { it.id.toString().uppercase() })
        for ((session, values) in sessions.zip(expected)) {
            val car = vehicles.getValue(session.vin)
            assertNullableEquals(values.getValue("energyDelivered").doubleOrNullValue(), car.energyDelivered(session))
            assertNullableEquals(values.getValue("chargingCost").doubleOrNullValue(), car.chargingCost(session))
        }
        assertEquals(
            onIPhone.getValue("tokensDecoded").jsonArray.mapNotNull { it.instantOrNull() }.sorted(),
            db.accounts().all().mapNotNull { it.serializedAuthToken?.let(SwiftAuthToken::decode)?.expiresAt }.sorted(),
        )

        // What the iPhone holds after importing is what Android holds after importing.
        assertSameBackup(golden("android-backup-via-ios.ghostbluebackup"), BackupArchive.export(db, "Pixel").encoded())
    }

    /** Writes the backup the iOS code imports; only when the parity script asks for it. */
    @Test fun writeAndroidBackupForTheIPhone() = runTest {
        val directory = System.getProperty("ghostblue.parityOut").orEmpty()
        assumeTrue(directory.isNotEmpty())
        val db = store()
        AndroidParitySeed.seed(db)
        File(directory, "android-backup.ghostbluebackup").writeBytes(BackupArchive.export(db, "Pixel").encoded())
    }

    /**
     * Same backup content, ignoring when and where it was written and, unless
     * [keepIds], the ids an import makes new (iOS and Android both give
     * imported accounts and climate presets fresh ones).
     */
    private fun assertSameBackup(expected: ByteArray, actual: ByteArray, keepIds: Boolean = false) {
        fun normalized(bytes: ByteArray): JsonElement {
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val sortKeys = mapOf(
                "accounts" to listOf("username"), "vehicles" to listOf("vin"), "climatePresets" to listOf("vehicleVin", "name"),
                "chargeSessions" to listOf("id"), "trips" to listOf("id"), "remoteCommands" to listOf("id"),
                "maintenanceReminders" to listOf("id"), "automations" to listOf("id"),
            )
            val newOnImport = setOf("accounts.id", "vehicles.accountId", "climatePresets.id")
            return JsonObject(
                root.filterKeys { it != "createdAt" && it != "sourceDevice" }.mapValues { (table, rows) ->
                    val sortKey = sortKeys[table] ?: return@mapValues rows
                    JsonArray(
                        rows.jsonArray.map { row ->
                            JsonObject(row.jsonObject.filterKeys { key -> keepIds || "$table.$key" !in newOnImport })
                        }.sortedBy { row -> sortKey.joinToString("|") { row[it]?.jsonPrimitive?.content.orEmpty() } },
                    )
                },
            )
        }
        assertJsonEquals("", normalized(expected), normalized(actual))
    }

    /** Structural JSON equality; numbers compare by value (Swift writes 84, Kotlin 84.0). */
    private fun assertJsonEquals(path: String, expected: JsonElement, actual: JsonElement) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                assertEquals("keys at $path", expected.keys.sorted(), actual.keys.sorted())
                for (key in expected.keys) assertJsonEquals("$path.$key", expected.getValue(key), actual.getValue(key))
            }
            expected is JsonArray && actual is JsonArray -> {
                assertEquals("length at $path", expected.size, actual.size)
                expected.zip(actual).forEachIndexed { index, (e, a) -> assertJsonEquals("$path[$index]", e, a) }
            }
            expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString &&
                expected.doubleOrNull != null && actual.doubleOrNull != null ->
                assertEquals(path, expected.double, actual.double, 0.0)
            else -> assertEquals(path, expected, actual)
        }
    }

    private fun assertNullableEquals(expected: Double?, actual: Double?) {
        if (expected == null) assertNull(actual) else assertEquals(expected, actual!!, 1e-9)
    }

    // MARK: - Charge sessions

    @Test fun chargeSessionDetectionMatchesTheIPhoneStepForStep() = runTest {
        val db = store()
        val scenarios = goldenJson("charge-scenarios.json").getValue("scenarios").jsonArray.map { it.jsonObject }
        assertEquals(60, scenarios.size)
        for (scenario in scenarios) {
            val vin = scenario.getValue("vin").jsonPrimitive.content
            val config = scenario.getValue("config").jsonPrimitive.int
            val car = quietVehicle(vin, scenario.getValue("model").jsonPrimitive.content, odometer = 1_000.0)
                .also { configure(it, config) }
            db.vehicles().insert(car)
            val steps = scenario.getValue("steps").jsonArray.map { it.jsonObject }
            val results = scenario.getValue("results").jsonArray.map { it.jsonObject }
            for ((index, step) in steps.withIndex()) {
                val where = "$vin step $index"
                if (step.getValue("kind").jsonPrimitive.content == "stopRequested") {
                    car.chargeStopRequestedAt = step.getValue("at").instantOrNull()
                    continue
                }
                val accepted = car.updateStatus(status(vin, step), db)
                val expected = results[index]
                assertEquals(where, expected.getValue("acceptedCharging").jsonPrimitive.booleanOrNull, accepted.evStatus?.charging)
                assertNullableEquals(expected.getValue("acceptedSpeed").doubleOrNullValue(), accepted.evStatus?.chargeSpeed)
                assertEquals(
                    where,
                    expected.getValue("acceptedPlugType").doubleOrNullValue()?.toInt(),
                    accepted.evStatus?.plugType?.rawValue,
                )
                assertEquals(where, expected.getValue("acceptedSyncDate").instantOrNull(), accepted.syncDate)
                assertEquals(where, expected.getValue("charging").jsonPrimitive.booleanOrNull, car.evStatus?.charging)
                assertEquals(where, expected.getValue("pendingChargingStopDate").instantOrNull(), car.pendingChargingStopDate)
                assertEquals(
                    where,
                    expected.getValue("pendingChargingStopObservedAt").instantOrNull(),
                    car.pendingChargingStopObservedAt,
                )
                assertEquals(where, expected.getValue("chargeStopRequestedAt").instantOrNull(), car.chargeStopRequestedAt)
                assertEquals(
                    where,
                    expected.getValue("displayState").jsonPrimitive.content,
                    car.chargingDisplayState(step.getValue("syncDate").instantOrNull()!!).rawValue,
                )
            }
            val sessions = db.chargeSessions().forVin(vin)
            val expectedSessions = scenario.getValue("sessions").jsonArray.map { it.jsonObject }
            assertEquals("$vin sessions", expectedSessions.size, sessions.size)
            for ((index, pair) in expectedSessions.zip(sessions).withIndex()) {
                assertSession("$vin session $index", pair.first, pair.second, car)
            }
        }
    }

    private fun assertSession(where: String, expected: JsonObject, session: ChargeSession, car: BBVehicle) {
        assertEquals(where, expected.getValue("startDate").instantOrNull(), session.startDate)
        assertEquals(where, expected.getValue("endDate").instantOrNull(), session.endDate)
        assertEquals(where, expected.getValue("startPercentage").jsonPrimitive.double, session.startPercentage, 0.0)
        assertEquals(where, expected.getValue("endPercentage").jsonPrimitive.double, session.endPercentage, 0.0)
        assertEquals(where, expected.getValue("energyAddedKWh").jsonPrimitive.double, session.energyAddedKWh, 1e-9)
        assertEquals(where, expected.getValue("maxChargeSpeedKW").jsonPrimitive.double, session.maxChargeSpeedKW, 0.0)
        assertEquals(where, expected.getValue("isDCFast").jsonPrimitive.boolean, session.isDCFast)
        assertEquals(where, expected.getValue("lastSampleDate").instantOrNull(), session.lastSampleDate)
        assertEquals(where, expected.getValue("pendingStopDate").instantOrNull(), session.pendingStopDate)
        assertEquals(where, expected.getValue("pendingStopObservedAt").instantOrNull(), session.pendingStopObservedAt)
        assertNullableEquals(expected.getValue("pendingStopPercentage").doubleOrNullValue(), session.pendingStopPercentage)
        assertEquals(where, expected.getValue("locationTypeRaw").jsonPrimitive.content, session.locationTypeRaw)
        assertNullableEquals(expected.getValue("electricityRatePerKWh").doubleOrNullValue(), session.electricityRatePerKWh)
        assertEquals(where, expected.getValue("hasTariff").jsonPrimitive.boolean, session.tariff != null)
        assertEquals(
            where,
            expected.getValue("samples").jsonArray.map { sample -> sample.jsonArray.map { it.jsonPrimitive.double } },
            session.speedSamples.map { listOf(it.date.epochSecond + it.date.nano / 1e9, it.speedKW, it.batteryPercentage) },
        )
        assertEquals(where, expected.getValue("energyDelivered").jsonPrimitive.double, car.energyDelivered(session), 1e-9)
        assertNullableEquals(expected.getValue("chargingCost").doubleOrNullValue(), car.chargingCost(session))
        assertEquals(where, expected.getValue("energySource").jsonPrimitive.content, car.energySource(session))
    }

    /** The same vehicle set-ups as `AndroidParityGoldens.configure`. */
    private fun configure(car: BBVehicle, config: Int) {
        when (config) {
            1 -> {
                car.smartChargingEnabled = true
                car.smartChargingHomeOnly = false
                car.electricityRatePerKWh = 0.3
                car.offPeakElectricityRatePerKWh = 0.1
                car.peakElectricityRatePerKWh = 0.6
                car.dcFastChargingRatePerKWh = 0.5
                car.homeLatitude = 32.7157
                car.homeLongitude = -117.1611
            }
            2 -> {
                car.electricityRatePerKWh = 0.25
                car.dcFastChargingRatePerKWh = 0.45
            }
        }
    }

    private fun status(vin: String, step: JsonObject): VehicleStatus {
        val percentage = step.getValue("percentage").jsonPrimitive.double
        val targetAC = step.getValue("targetSocAC").jsonPrimitive.double
        val targetDC = step.getValue("targetSocDC").jsonPrimitive.double
        val ev = VehicleStatus.EVStatus(
            charging = step.getValue("charging").jsonPrimitive.boolean,
            chargeSpeed = step.getValue("speed").jsonPrimitive.double,
            evRange = VehicleStatus.FuelRange(Distance(percentage * 3, Distance.Units.MILES), percentage),
            plugType = VehicleStatus.PlugType.entries.first { it.rawValue == step.getValue("plugType").jsonPrimitive.int },
            chargeTimeSeconds = 1_800,
            targetSocAC = targetAC.takeIf { it >= 0 },
            targetSocDC = targetDC.takeIf { it >= 0 },
        )
        return VehicleStatus(
            vin = vin,
            lastUpdated = step.getValue("fetchedAt").instantOrNull()!!,
            syncDate = step.getValue("syncDate").instantOrNull(),
            evStatus = ev.takeIf { step.getValue("hasEV").jsonPrimitive.boolean },
            location = if (step.getValue("atHome").jsonPrimitive.boolean) VehicleStatus.Location(32.7158, -117.1612)
            else VehicleStatus.Location(32.80, -117.25),
            lockStatus = VehicleStatus.LockStatus.LOCKED,
            climateStatus = VehicleStatus.ClimateStatus(false, false, false, Temperature(70.0, Temperature.Units.FAHRENHEIT)),
            odometer = Distance(step.getValue("odometer").jsonPrimitive.double, Distance.Units.MILES),
        )
    }

    // MARK: - Schedule, tariffs, battery defaults

    @Test fun sdgeScheduleMatchesEveryHalfHourFrom2025To2028() {
        val golden = goldenJson("sdge-schedule.json")
        val zone = SDGESmartChargingSchedule.timeZone
        val days = golden.getValue("days").jsonObject
        assertEquals(1_461, days.size)
        for ((key, slots) in days) {
            val day = LocalDate.parse(key)
            val actual = (0 until 48).joinToString("") { slot ->
                val at = ZonedDateTime.of(day.atTime(slot / 2, (slot % 2) * 30), zone).toInstant()
                if (SDGESmartChargingSchedule.preference(at) == SmartChargingPreference.CHARGE) "C" else "P"
            }
            assertEquals(key, slots.jsonPrimitive.content, actual)
        }
        val holidays = golden.getValue("holidays").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            holidays,
            days.keys.sorted().filter { SDGESmartChargingSchedule.isHoliday(ZonedDateTime.of(LocalDate.parse(it).atTime(12, 0), zone).toInstant()) },
        )
        for (probe in golden.getValue("transitions").jsonArray.map { it.jsonObject }) {
            val after = probe.getValue("after").instantOrNull()!!
            assertEquals(
                "$after",
                probe.getValue("next").jsonArray.map { it.jsonArray.let { pair -> pair[0].instantOrNull() to pair[1].jsonPrimitive.content } },
                SDGESmartChargingSchedule.transitions(after, count = 6).map { it.date to it.preference.rawValue },
            )
            assertEquals("$after", probe.getValue("mostRecent").instantOrNull(), SDGESmartChargingSchedule.mostRecentTransition(after))
        }
    }

    @Test fun tariffCostsMatchAcrossTimeZonesAndDaylightSaving() {
        val golden = goldenJson("tariff-costs.json")
        val tariffs = golden.getValue("tariffs").jsonArray.map { KitJson.decodeFromJsonElement(ChargingTariff.serializer(), it) }
        val energy = golden.getValue("energyKWh").jsonPrimitive.double
        val cases = golden.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 400)
        for (case in cases) {
            val tariff = tariffs[case.getValue("tariff").jsonPrimitive.int]
            val from = case.getValue("start").instantOrNull()!!
            val to = case.getValue("end").instantOrNull()!!
            assertEquals("$tariff $from–$to", case.getValue("cost").jsonPrimitive.double, tariff.cost(energy, from, to), 1e-9)
            assertEquals("$tariff $from", case.getValue("rate").jsonPrimitive.double, tariff.rate(from), 0.0)
        }
    }

    @Test fun batteryDefaultsAndEnergyWordingMatch() {
        val golden = goldenJson("battery-catalog.json")
        for (entry in golden.getValue("capacities").jsonArray.map { it.jsonObject }) {
            val model = entry.getValue("model").jsonPrimitive.content
            val fuel = FuelType.entries.first { it.rawValue == entry.getValue("fuelType").jsonPrimitive.content }
            assertEquals("$model $fuel", entry.getValue("kWh").doubleOrNullValue(), BatteryCapacityCatalog.defaultCapacityKWh(model, fuel))
        }
        for (entry in golden.getValue("energySources").jsonArray.map { it.jsonObject }) {
            val capacity = entry.getValue("capacity").doubleOrNullValue()
            val session = ChargeSession("V", Instant.ofEpochSecond(1_790_000_000), 40.0, isDCFast = false).apply {
                endPercentage = 40 + entry.getValue("added").jsonPrimitive.double
                manualEnergyKWh = entry.getValue("manual").doubleOrNullValue()
                energyAddedKWh = 31.7
            }
            val modelDefault = entry.getValue("modelDefault").jsonPrimitive.boolean
            assertEquals("$entry", entry.getValue("text").jsonPrimitive.content, session.energySource(capacity, modelDefault))
            assertEquals("$entry", entry.getValue("energy").jsonPrimitive.double, session.resolvedEnergyKWh(capacity), 1e-12)
        }
    }
}
