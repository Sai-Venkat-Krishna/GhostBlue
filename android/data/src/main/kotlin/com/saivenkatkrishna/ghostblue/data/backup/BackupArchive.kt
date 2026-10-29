package com.saivenkatkrishna.ghostblue.data.backup

import androidx.room.withTransaction
import com.saivenkatkrishna.ghostblue.data.charging.ChargingTariff
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ChargeSpeedSample
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomation
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomationKind
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Iso8601InstantSerializer
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.UppercaseUUIDSerializer
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private typealias Date = @Serializable(with = Iso8601InstantSerializer::class) Instant
private typealias Id = @Serializable(with = UppercaseUUIDSerializer::class) UUID

/**
 * A portable snapshot of everything GhostBlue stores, in the exact JSON the
 * iOS app's `BackupArchive` writes: the same keys, ISO 8601 dates to the
 * second and uppercase UUIDs. A backup from either app imports on the
 * other. HTTP logs are left out, as on iOS.
 */
@Serializable
data class BackupArchive(
    val version: Int,
    val createdAt: Date,
    val sourceDevice: String,
    val accounts: List<AccountRow>,
    val vehicles: List<VehicleRow>,
    val climatePresets: List<ClimatePresetRow>,
    val chargeSessions: List<ChargeSessionRow>,
    val trips: List<TripRow>,
    val remoteCommands: List<RemoteCommandRow>,
    val maintenanceReminders: List<MaintenanceRow>,
    val automations: List<AutomationRow>,
) {
    @Serializable
    data class AccountRow(
        val id: Id,
        val username: String,
        val password: String,
        val refreshToken: String,
        val pin: String,
        val brand: String,
        val region: String,
        val dateCreated: Date,
        val rememberMeToken: String? = null,
        val serializedAuthToken: String? = null,
        val deviceId: String? = null,
        val lastVehiclesFetch: Date? = null,
    )

    /**
     * Carries the owning account's username and brand as well as its id: one
     * account can have a different id in each store, so the merge re-attaches
     * by the pair that is stable.
     */
    @Serializable
    data class VehicleRow(
        val id: Id,
        val vin: String,
        val regId: String,
        val model: String,
        val accountId: Id,
        val accountUsername: String? = null,
        val accountBrand: String? = null,
        val fuelTypeRaw: String,
        val generation: Int,
        val odometer: Distance,
        val lastUpdated: Date? = null,
        val syncDate: Date? = null,
        val gasRange: VehicleStatus.FuelRange? = null,
        val evStatus: VehicleStatus.EVStatus? = null,
        val location: VehicleStatus.Location? = null,
        val lockStatus: VehicleStatus.LockStatus? = null,
        val climateStatus: VehicleStatus.ClimateStatus? = null,
        val battery12V: Int? = null,
        val doorOpen: VehicleStatus.DoorStatus? = null,
        val trunkOpen: Boolean? = null,
        val hoodOpen: Boolean? = null,
        val tirePressureWarning: VehicleStatus.TirePressureWarning? = null,
        val customName: String? = null,
        val isHidden: Boolean,
        val sortOrder: Int,
        val backgroundColorName: String,
        val watchBackgroundColorName: String,
        val vehicleImageColorRaw: String,
        val chargePortTypeRaw: String,
        val batteryCapacityKWh: Double? = null,
        val electricityRatePerKWh: Double? = null,
        val offPeakElectricityRatePerKWh: Double? = null,
        val dcFastChargingRatePerKWh: Double? = null,
        val peakElectricityRatePerKWh: Double? = null,
        val offPeakStartHour: Int,
        val offPeakEndHour: Int,
        val monthlyChargingBudget: Double? = null,
        val homeLatitude: Double? = null,
        val homeLongitude: Double? = null,
        val homePrivacyRadiusMeters: Double,
        val smartChargingEnabled: Boolean,
        val smartChargingHomeOnly: Boolean,
        val smartChargingTargetSOC: Int,
        val notifyChargeComplete: Boolean,
        val notifyChargeInterrupted: Boolean,
        val notifyUnlocked: Boolean,
        val notifyOpenPanels: Boolean,
        val notifyLowEVBattery: Boolean,
        val notifyLow12VBattery: Boolean,
        val notifyTirePressure: Boolean,
        val lowEVBatteryThreshold: Int,
        val low12VBatteryThreshold: Int,
        val enableSeatHeatControls: Boolean,
        val showClimateDurationOverride: Boolean? = null,
        val fuelTypeOverrideRaw: String? = null,
        val abrpUserToken: String? = null,
        val abrpCarModel: String? = null,
        val primaryColorName: String? = null,
        val chargingColorName: String? = null,
        val gasColorName: String? = null,
        val lockColorName: String? = null,
        val unlockColorName: String? = null,
        val startClimateColorName: String? = null,
        val stopColorName: String? = null,
    )

    @Serializable
    data class ClimatePresetRow(
        val id: Id,
        val vehicleVin: String? = null,
        val name: String,
        val iconName: String,
        val climateOptions: ClimateOptions,
        val isSelected: Boolean,
        val sortOrder: Int,
    )

    @Serializable
    data class ChargeSessionRow(
        val id: Id,
        val vin: String,
        val startDate: Date,
        val endDate: Date? = null,
        val startPercentage: Double,
        val endPercentage: Double,
        val energyAddedKWh: Double,
        val maxChargeSpeedKW: Double,
        val isDCFast: Boolean,
        val lastSampleDate: Date,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val locationTypeRaw: String,
        val locationName: String? = null,
        val electricityRatePerKWh: Double? = null,
        val actualCost: Double? = null,
        val manualEnergyKWh: Double? = null,
        val rateWasLocationAware: Boolean,
        val tariff: ChargingTariff? = null,
        val speedSamples: List<ChargeSpeedSample>,
    )

    @Serializable
    data class TripRow(
        val id: Id,
        val vin: String,
        val tripKey: String,
        val distance: Double,
        val odometer: Double,
        val accessoriesEnergy: Int,
        val totalEnergyUsed: Int,
        val regenEnergy: Int,
        val climateEnergy: Int,
        val drivetrainEnergy: Int,
        val batteryCareEnergy: Int,
        val startDate: Date,
        val durationSeconds: Int,
        val avgSpeed: Double,
        val maxSpeed: Double,
        val savedDate: Date,
        val customName: String? = null,
        val isFavorite: Boolean,
        val startLocationName: String? = null,
        val endLocationName: String? = null,
        val startLatitude: Double? = null,
        val startLongitude: Double? = null,
        val endLatitude: Double? = null,
        val endLongitude: Double? = null,
    )

    @Serializable
    data class RemoteCommandRow(
        val id: Id,
        val vin: String,
        val vehicleName: String,
        val commandName: String,
        val sourceName: String,
        val stateRaw: String,
        val startedAt: Date,
        val completedAt: Date? = null,
        val errorMessage: String? = null,
    )

    @Serializable
    data class MaintenanceRow(
        val id: Id,
        val vin: String,
        val title: String,
        val notes: String,
        val dueDate: Date? = null,
        val dueOdometer: Double? = null,
        val cost: Double? = null,
        val createdAt: Date,
        val completedAt: Date? = null,
        val lastNotifiedAt: Date? = null,
    )

    @Serializable
    data class AutomationRow(
        val id: Id,
        val vin: String,
        val title: String,
        val kindRaw: String,
        val isEnabled: Boolean,
        val threshold: Int,
        val hour: Int,
        val minute: Int,
        val lastTriggeredAt: Date? = null,
        val createdAt: Date,
    )

    /** Pretty-printed with sorted keys, as iOS writes it. */
    fun encoded(): ByteArray {
        val tree = json.encodeToJsonElement(serializer(), this)
        return prettyJson.encodeToString(JsonElement.serializer(), sortedKeys(tree)).toByteArray()
    }

    companion object {
        const val CURRENT_VERSION = 1

        // Swift's synthesized Codable: nils omitted, every other field written.
        private val json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val prettyJson = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        fun decoded(data: ByteArray): BackupArchive = json.decodeFromString(serializer(), data.decodeToString())

        private fun sortedKeys(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { sortedKeys(it.value) })
            is JsonArray -> JsonArray(element.map(::sortedKeys))
            else -> element
        }

        /** Everything in [db], as iOS's `BackupArchive.export(from:deviceName:)`. */
        suspend fun export(db: GhostBlueDatabase, deviceName: String, now: Instant = Instant.now()): BackupArchive =
            db.withTransaction {
                val accounts = db.accounts().all()
                val accountsById = accounts.associateBy { it.id }
                val vehicles = db.vehicles().all()
                val vinsById = vehicles.associate { it.id to it.vin }
                BackupArchive(
                    version = CURRENT_VERSION,
                    createdAt = now,
                    sourceDevice = deviceName,
                    accounts = accounts.map { it.toRow() },
                    vehicles = vehicles.map { it.toRow(it.ownerAccountId?.let(accountsById::get)) },
                    climatePresets = db.climatePresets().all().map { it.toRow(it.vehicleId?.let(vinsById::get)) },
                    chargeSessions = db.chargeSessions().all().map { it.toRow() },
                    trips = db.trips().all().map { it.toRow() },
                    remoteCommands = db.remoteCommands().all().map { it.toRow() },
                    maintenanceReminders = db.maintenanceReminders().all().map { it.toRow() },
                    automations = db.automations().all().map { it.toRow() },
                )
            }

        /**
         * Folds [archive] into [db]. Add-only: a row whose natural key is
         * already here is left exactly as it is, so importing the same file
         * twice changes nothing and importing in both directions converges on
         * the union of both stores.
         */
        suspend fun merge(archive: BackupArchive, db: GhostBlueDatabase): BackupMergeSummary =
            db.withTransaction { mergeInTransaction(archive, db) }

        private suspend fun mergeInTransaction(archive: BackupArchive, db: GhostBlueDatabase): BackupMergeSummary {
            val summary = BackupMergeSummary()

            // Accounts, by username and brand, which survive differing ids.
            fun accountKey(username: String, brand: String) = "${username.lowercase()}|${brand.lowercase()}"
            val allAccounts = db.accounts().all()
            val accountsByKey = allAccounts.associateBy { accountKey(it.username, it.brand) }.toMutableMap()
            for (row in archive.accounts) {
                val key = accountKey(row.username, row.brand)
                if (accountsByKey.containsKey(key)) {
                    summary.accountsSkipped++
                    continue
                }
                val brand = Brand.fromRawValue(row.brand)
                val region = Region.fromRawValue(row.region)
                if (brand == null || region == null) {
                    summary.accountsSkipped++
                    continue
                }
                val account = BBAccount(row.username, row.password, row.pin, brand, region, row.refreshToken).apply {
                    dateCreated = row.dateCreated
                    rememberMeToken = row.rememberMeToken
                    serializedAuthToken = row.serializedAuthToken
                    deviceId = row.deviceId
                    lastVehiclesFetch = row.lastVehiclesFetch
                }
                db.accounts().insert(account)
                accountsByKey[key] = account
                summary.accountsAdded++
            }

            // Vehicles, by VIN.
            val vehiclesByVin = db.vehicles().all().associateBy { it.vin }.toMutableMap()
            val usedVehicleIds = vehiclesByVin.values.map { it.id }.toMutableSet()
            for (row in archive.vehicles) {
                val existing = vehiclesByVin[row.vin]
                if (existing != null) {
                    if (fillBlanks(existing, row)) {
                        db.vehicles().update(existing)
                        summary.vehiclesUpdated++
                    } else {
                        summary.vehiclesSkipped++
                    }
                    continue
                }
                val owner = if (row.accountUsername != null && row.accountBrand != null) {
                    accountsByKey[accountKey(row.accountUsername, row.accountBrand)]
                } else {
                    db.accounts().byId(row.accountId)
                }
                if (owner == null) {
                    summary.orphanedVehicles += row.vin
                    continue
                }
                val vehicle = BBVehicle(
                    Vehicle(
                        vin = row.vin,
                        regId = row.regId,
                        model = row.model,
                        accountId = owner.id,
                        fuelType = FuelType.entries.firstOrNull { it.rawValue == row.fuelTypeRaw } ?: FuelType.GAS,
                        generation = row.generation,
                        odometer = row.odometer,
                    ),
                )
                apply(row, vehicle)
                // Room needs a unique key where SwiftData didn't; a clash gets a fresh one.
                if (!usedVehicleIds.add(vehicle.id)) vehicle.id = UUID.randomUUID().also(usedVehicleIds::add)
                vehicle.ownerAccountId = owner.id
                db.vehicles().insert(vehicle)
                vehiclesByVin[row.vin] = vehicle
                summary.vehiclesAdded++
            }

            // Climate presets, by the owning VIN and name.
            val vinsById = vehiclesByVin.values.associate { it.id to it.vin }
            val presetKeys = db.climatePresets().all()
                .map { "${it.vehicleId?.let(vinsById::get) ?: ""}|${it.name.lowercase()}" }.toMutableSet()
            for (row in archive.climatePresets) {
                val key = "${row.vehicleVin ?: ""}|${row.name.lowercase()}"
                val vehicle = row.vehicleVin?.let(vehiclesByVin::get)
                if (key in presetKeys || vehicle == null) {
                    summary.presetsSkipped++
                    continue
                }
                db.climatePresets().insert(
                    ClimatePreset(row.name, row.iconName, row.climateOptions, row.isSelected, vehicle.id).apply {
                        sortOrder = row.sortOrder
                    },
                )
                presetKeys += key
                summary.presetsAdded++
            }

            // Charge sessions, by VIN and start second; the same record id is the same session whatever its dates.
            val existingSessions = db.chargeSessions().all()
            val sessionKeys = existingSessions.map { "${it.vin}|${stamp(it.startDate)}" }.toMutableSet()
            val sessionIds = existingSessions.map { it.id }.toMutableSet()
            for (row in archive.chargeSessions) {
                val key = "${row.vin}|${stamp(row.startDate)}"
                if (key in sessionKeys || row.id in sessionIds) {
                    summary.chargeSessionsSkipped++
                    continue
                }
                db.chargeSessions().insert(
                    ChargeSession(row.vin, row.startDate, row.startPercentage, row.isDCFast, row.latitude, row.longitude)
                        .apply {
                            id = row.id
                            endDate = row.endDate
                            endPercentage = row.endPercentage
                            energyAddedKWh = row.energyAddedKWh
                            maxChargeSpeedKW = row.maxChargeSpeedKW
                            lastSampleDate = row.lastSampleDate
                            locationTypeRaw = row.locationTypeRaw
                            locationName = row.locationName
                            electricityRatePerKWh = row.electricityRatePerKWh
                            actualCost = row.actualCost
                            manualEnergyKWh = row.manualEnergyKWh
                            rateWasLocationAware = row.rateWasLocationAware
                            tariff = row.tariff
                            speedSamples = row.speedSamples
                        },
                )
                sessionKeys += key
                sessionIds += row.id
                summary.chargeSessionsAdded++
            }

            // Trips, by the trip key the app derives.
            val existingTrips = db.trips().all()
            val tripKeys = existingTrips.map { "${it.vin}|${it.tripKey}" }.toMutableSet()
            val tripIds = existingTrips.map { it.id }.toMutableSet()
            for (row in archive.trips) {
                val key = "${row.vin}|${row.tripKey}"
                if (key in tripKeys) {
                    summary.tripsSkipped++
                    continue
                }
                val detail = EVTripDetail(
                    distance = row.distance,
                    odometer = row.odometer,
                    accessoriesEnergy = row.accessoriesEnergy,
                    totalEnergyUsed = row.totalEnergyUsed,
                    regenEnergy = row.regenEnergy,
                    climateEnergy = row.climateEnergy,
                    drivetrainEnergy = row.drivetrainEnergy,
                    batteryCareEnergy = row.batteryCareEnergy,
                    startDate = row.startDate,
                    durationSeconds = row.durationSeconds,
                    avgSpeed = row.avgSpeed,
                    maxSpeed = row.maxSpeed,
                )
                db.trips().insert(
                    TripRecord(row.vin, detail).apply {
                        id = if (tripIds.add(row.id)) row.id else UUID.randomUUID().also(tripIds::add)
                        tripKey = row.tripKey
                        savedDate = row.savedDate
                        customName = row.customName
                        isFavorite = row.isFavorite
                        startLocationName = row.startLocationName
                        endLocationName = row.endLocationName
                        startLatitude = row.startLatitude
                        startLongitude = row.startLongitude
                        endLatitude = row.endLatitude
                        endLongitude = row.endLongitude
                    },
                )
                tripKeys += key
                summary.tripsAdded++
            }

            // Remote command history.
            val existingCommands = db.remoteCommands().all()
            val commandKeys = existingCommands.map { "${it.vin}|${it.commandName}|${stamp(it.startedAt)}" }.toMutableSet()
            val commandIds = existingCommands.map { it.id }.toMutableSet()
            for (row in archive.remoteCommands) {
                val key = "${row.vin}|${row.commandName}|${stamp(row.startedAt)}"
                if (key in commandKeys || row.id in commandIds) {
                    summary.remoteCommandsSkipped++
                    continue
                }
                db.remoteCommands().insert(
                    RemoteCommandActivity(row.vin, row.vehicleName, row.commandName, row.sourceName).apply {
                        id = row.id
                        stateRaw = row.stateRaw
                        startedAt = row.startedAt
                        completedAt = row.completedAt
                        errorMessage = row.errorMessage
                    },
                )
                commandKeys += key
                commandIds += row.id
                summary.remoteCommandsAdded++
            }

            // Maintenance reminders.
            val existingReminders = db.maintenanceReminders().all()
            val reminderKeys = existingReminders
                .map { "${it.vin}|${it.title.lowercase()}|${stamp(it.createdAt)}" }.toMutableSet()
            val reminderIds = existingReminders.map { it.id }.toMutableSet()
            for (row in archive.maintenanceReminders) {
                val key = "${row.vin}|${row.title.lowercase()}|${stamp(row.createdAt)}"
                if (key in reminderKeys || row.id in reminderIds) {
                    summary.remindersSkipped++
                    continue
                }
                db.maintenanceReminders().insert(
                    MaintenanceReminder(row.vin, row.title, row.notes, row.dueDate, row.dueOdometer, row.cost).apply {
                        id = row.id
                        createdAt = row.createdAt
                        completedAt = row.completedAt
                        lastNotifiedAt = row.lastNotifiedAt
                    },
                )
                reminderKeys += key
                reminderIds += row.id
                summary.remindersAdded++
            }

            // Automations.
            val existingAutomations = db.automations().all()
            val automationKeys = existingAutomations
                .map { "${it.vin}|${it.kindRaw}|${it.title.lowercase()}" }.toMutableSet()
            val automationIds = existingAutomations.map { it.id }.toMutableSet()
            for (row in archive.automations) {
                val key = "${row.vin}|${row.kindRaw}|${row.title.lowercase()}"
                val kind = VehicleAutomationKind.fromRawValue(row.kindRaw)
                if (key in automationKeys || kind == null) {
                    summary.automationsSkipped++
                    continue
                }
                db.automations().insert(
                    VehicleAutomation(row.vin, row.title, kind, row.threshold, row.hour, row.minute).apply {
                        id = if (automationIds.add(row.id)) row.id else UUID.randomUUID().also(automationIds::add)
                        isEnabled = row.isEnabled
                        lastTriggeredAt = row.lastTriggeredAt
                        createdAt = row.createdAt
                    },
                )
                automationKeys += key
                summary.automationsAdded++
            }

            return summary
        }

        /**
         * Whole seconds, truncated as the backup's dates are. Rounding made
         * 12:00:00.6 in the store (→ :01) miss the same event in a file (:00).
         */
        private fun stamp(date: Instant): Long = date.epochSecond

        /** Copies every field onto a vehicle new to this store. */
        private fun apply(row: VehicleRow, vehicle: BBVehicle) {
            vehicle.id = row.id
            vehicle.lastUpdated = row.lastUpdated
            vehicle.syncDate = row.syncDate
            vehicle.gasRange = row.gasRange
            vehicle.evStatus = row.evStatus
            vehicle.location = row.location
            vehicle.lockStatus = row.lockStatus
            vehicle.climateStatus = row.climateStatus
            vehicle.battery12V = row.battery12V
            vehicle.doorOpen = row.doorOpen
            vehicle.trunkOpen = row.trunkOpen
            vehicle.hoodOpen = row.hoodOpen
            vehicle.tirePressureWarning = row.tirePressureWarning
            vehicle.customName = row.customName
            vehicle.isHidden = row.isHidden
            vehicle.sortOrder = row.sortOrder
            vehicle.backgroundColorName = row.backgroundColorName
            vehicle.watchBackgroundColorName = row.watchBackgroundColorName
            vehicle.vehicleImageColorRaw = row.vehicleImageColorRaw
            vehicle.chargePortTypeRaw = row.chargePortTypeRaw
            vehicle.batteryCapacityKWh = row.batteryCapacityKWh
            vehicle.electricityRatePerKWh = row.electricityRatePerKWh
            vehicle.offPeakElectricityRatePerKWh = row.offPeakElectricityRatePerKWh
            vehicle.dcFastChargingRatePerKWh = row.dcFastChargingRatePerKWh
            vehicle.peakElectricityRatePerKWh = row.peakElectricityRatePerKWh
            vehicle.offPeakStartHour = row.offPeakStartHour
            vehicle.offPeakEndHour = row.offPeakEndHour
            vehicle.monthlyChargingBudget = row.monthlyChargingBudget
            vehicle.homeLatitude = row.homeLatitude
            vehicle.homeLongitude = row.homeLongitude
            vehicle.homePrivacyRadiusMeters = row.homePrivacyRadiusMeters
            vehicle.smartChargingEnabled = row.smartChargingEnabled
            vehicle.smartChargingHomeOnly = row.smartChargingHomeOnly
            vehicle.smartChargingTargetSOC = row.smartChargingTargetSOC
            vehicle.notifyChargeComplete = row.notifyChargeComplete
            vehicle.notifyChargeInterrupted = row.notifyChargeInterrupted
            vehicle.notifyUnlocked = row.notifyUnlocked
            vehicle.notifyOpenPanels = row.notifyOpenPanels
            vehicle.notifyLowEVBattery = row.notifyLowEVBattery
            vehicle.notifyLow12VBattery = row.notifyLow12VBattery
            vehicle.notifyTirePressure = row.notifyTirePressure
            vehicle.lowEVBatteryThreshold = row.lowEVBatteryThreshold
            vehicle.low12VBatteryThreshold = row.low12VBatteryThreshold
            vehicle.enableSeatHeatControls = row.enableSeatHeatControls
            vehicle.showClimateDurationOverride = row.showClimateDurationOverride
            vehicle.fuelTypeOverrideRaw = row.fuelTypeOverrideRaw
            vehicle.abrpUserToken = row.abrpUserToken
            vehicle.abrpCarModel = row.abrpCarModel
            vehicle.primaryColorName = row.primaryColorName
            vehicle.chargingColorName = row.chargingColorName
            vehicle.gasColorName = row.gasColorName
            vehicle.lockColorName = row.lockColorName
            vehicle.unlockColorName = row.unlockColorName
            vehicle.startClimateColorName = row.startClimateColorName
            vehicle.stopColorName = row.stopColorName
        }

        /**
         * On a vehicle already here, copies only the owner's settings this
         * store has no value for. Live status stays (the local copy is
         * fresher) and nothing already set is replaced. True when anything
         * changed.
         */
        private fun fillBlanks(vehicle: BBVehicle, row: VehicleRow): Boolean {
            var changed = false
            fun <T> fill(current: T?, value: T?, set: (T) -> Unit) {
                if (current == null && value != null) {
                    set(value)
                    changed = true
                }
            }
            fill(vehicle.customName, row.customName) { vehicle.customName = it }
            fill(vehicle.batteryCapacityKWh, row.batteryCapacityKWh) { vehicle.batteryCapacityKWh = it }
            fill(vehicle.electricityRatePerKWh, row.electricityRatePerKWh) { vehicle.electricityRatePerKWh = it }
            fill(vehicle.offPeakElectricityRatePerKWh, row.offPeakElectricityRatePerKWh) {
                vehicle.offPeakElectricityRatePerKWh = it
            }
            fill(vehicle.dcFastChargingRatePerKWh, row.dcFastChargingRatePerKWh) { vehicle.dcFastChargingRatePerKWh = it }
            fill(vehicle.peakElectricityRatePerKWh, row.peakElectricityRatePerKWh) { vehicle.peakElectricityRatePerKWh = it }
            fill(vehicle.monthlyChargingBudget, row.monthlyChargingBudget) { vehicle.monthlyChargingBudget = it }
            fill(vehicle.homeLatitude, row.homeLatitude) { vehicle.homeLatitude = it }
            fill(vehicle.homeLongitude, row.homeLongitude) { vehicle.homeLongitude = it }
            fill(vehicle.abrpUserToken, row.abrpUserToken) { vehicle.abrpUserToken = it }
            fill(vehicle.abrpCarModel, row.abrpCarModel) { vehicle.abrpCarModel = it }
            fill(vehicle.fuelTypeOverrideRaw, row.fuelTypeOverrideRaw) { vehicle.fuelTypeOverrideRaw = it }
            fill(vehicle.showClimateDurationOverride, row.showClimateDurationOverride) {
                vehicle.showClimateDurationOverride = it
            }
            fill(vehicle.primaryColorName, row.primaryColorName) { vehicle.primaryColorName = it }
            fill(vehicle.chargingColorName, row.chargingColorName) { vehicle.chargingColorName = it }
            fill(vehicle.gasColorName, row.gasColorName) { vehicle.gasColorName = it }
            fill(vehicle.lockColorName, row.lockColorName) { vehicle.lockColorName = it }
            fill(vehicle.unlockColorName, row.unlockColorName) { vehicle.unlockColorName = it }
            fill(vehicle.startClimateColorName, row.startClimateColorName) { vehicle.startClimateColorName = it }
            fill(vehicle.stopColorName, row.stopColorName) { vehicle.stopColorName = it }
            return changed
        }
    }
}

/**
 * What a merge did, per model. Nothing is deleted and no existing row is
 * rewritten, so "skipped" always means "already here", never "dropped".
 */
data class BackupMergeSummary(
    var accountsAdded: Int = 0,
    var accountsSkipped: Int = 0,
    var vehiclesAdded: Int = 0,
    var vehiclesUpdated: Int = 0,
    var vehiclesSkipped: Int = 0,
    var presetsAdded: Int = 0,
    var presetsSkipped: Int = 0,
    var chargeSessionsAdded: Int = 0,
    var chargeSessionsSkipped: Int = 0,
    var tripsAdded: Int = 0,
    var tripsSkipped: Int = 0,
    var remoteCommandsAdded: Int = 0,
    var remoteCommandsSkipped: Int = 0,
    var remindersAdded: Int = 0,
    var remindersSkipped: Int = 0,
    var automationsAdded: Int = 0,
    var automationsSkipped: Int = 0,
    val orphanedVehicles: MutableList<String> = mutableListOf(),
) {
    val totalAdded: Int
        get() = accountsAdded + vehiclesAdded + presetsAdded + chargeSessionsAdded +
            tripsAdded + remoteCommandsAdded + remindersAdded + automationsAdded

    val rows: List<Pair<String, String>>
        get() = listOf(
            "Accounts" to "$accountsAdded added, $accountsSkipped already here",
            "Vehicles" to "$vehiclesAdded added, $vehiclesUpdated filled in, $vehiclesSkipped unchanged",
            "Climate Presets" to "$presetsAdded added, $presetsSkipped already here",
            "Charge Sessions" to "$chargeSessionsAdded added, $chargeSessionsSkipped already here",
            "Trips" to "$tripsAdded added, $tripsSkipped already here",
            "Remote Commands" to "$remoteCommandsAdded added, $remoteCommandsSkipped already here",
            "Maintenance" to "$remindersAdded added, $remindersSkipped already here",
            "Automations" to "$automationsAdded added, $automationsSkipped already here",
        )
}

private fun BBAccount.toRow() = BackupArchive.AccountRow(
    id = id,
    username = username,
    password = password,
    refreshToken = refreshToken,
    pin = pin,
    brand = brand,
    region = region,
    dateCreated = dateCreated,
    rememberMeToken = rememberMeToken,
    serializedAuthToken = serializedAuthToken,
    deviceId = deviceId,
    lastVehiclesFetch = lastVehiclesFetch,
)

private fun BBVehicle.toRow(owner: BBAccount?) = BackupArchive.VehicleRow(
    id = id,
    vin = vin,
    regId = regId,
    model = model,
    accountId = accountId,
    accountUsername = owner?.username,
    accountBrand = owner?.brand,
    fuelTypeRaw = fuelTypeRaw,
    generation = generation,
    odometer = odometer,
    lastUpdated = lastUpdated,
    syncDate = syncDate,
    gasRange = gasRange,
    evStatus = evStatus,
    location = location,
    lockStatus = lockStatus,
    climateStatus = climateStatus,
    battery12V = battery12V,
    doorOpen = doorOpen,
    trunkOpen = trunkOpen,
    hoodOpen = hoodOpen,
    tirePressureWarning = tirePressureWarning,
    customName = customName,
    isHidden = isHidden,
    sortOrder = sortOrder,
    backgroundColorName = backgroundColorName,
    watchBackgroundColorName = watchBackgroundColorName,
    vehicleImageColorRaw = vehicleImageColorRaw,
    chargePortTypeRaw = chargePortTypeRaw,
    batteryCapacityKWh = batteryCapacityKWh,
    electricityRatePerKWh = electricityRatePerKWh,
    offPeakElectricityRatePerKWh = offPeakElectricityRatePerKWh,
    dcFastChargingRatePerKWh = dcFastChargingRatePerKWh,
    peakElectricityRatePerKWh = peakElectricityRatePerKWh,
    offPeakStartHour = offPeakStartHour,
    offPeakEndHour = offPeakEndHour,
    monthlyChargingBudget = monthlyChargingBudget,
    homeLatitude = homeLatitude,
    homeLongitude = homeLongitude,
    homePrivacyRadiusMeters = homePrivacyRadiusMeters,
    smartChargingEnabled = smartChargingEnabled,
    smartChargingHomeOnly = smartChargingHomeOnly,
    smartChargingTargetSOC = smartChargingTargetSOC,
    notifyChargeComplete = notifyChargeComplete,
    notifyChargeInterrupted = notifyChargeInterrupted,
    notifyUnlocked = notifyUnlocked,
    notifyOpenPanels = notifyOpenPanels,
    notifyLowEVBattery = notifyLowEVBattery,
    notifyLow12VBattery = notifyLow12VBattery,
    notifyTirePressure = notifyTirePressure,
    lowEVBatteryThreshold = lowEVBatteryThreshold,
    low12VBatteryThreshold = low12VBatteryThreshold,
    enableSeatHeatControls = enableSeatHeatControls,
    showClimateDurationOverride = showClimateDurationOverride,
    fuelTypeOverrideRaw = fuelTypeOverrideRaw,
    abrpUserToken = abrpUserToken,
    abrpCarModel = abrpCarModel,
    primaryColorName = primaryColorName,
    chargingColorName = chargingColorName,
    gasColorName = gasColorName,
    lockColorName = lockColorName,
    unlockColorName = unlockColorName,
    startClimateColorName = startClimateColorName,
    stopColorName = stopColorName,
)

private fun ClimatePreset.toRow(vehicleVin: String?) = BackupArchive.ClimatePresetRow(
    id = id,
    vehicleVin = vehicleVin,
    name = name,
    iconName = iconName,
    climateOptions = climateOptions,
    isSelected = isSelected,
    sortOrder = sortOrder,
)

private fun ChargeSession.toRow() = BackupArchive.ChargeSessionRow(
    id = id,
    vin = vin,
    startDate = startDate,
    endDate = endDate,
    startPercentage = startPercentage,
    endPercentage = endPercentage,
    energyAddedKWh = energyAddedKWh,
    maxChargeSpeedKW = maxChargeSpeedKW,
    isDCFast = isDCFast,
    lastSampleDate = lastSampleDate,
    latitude = latitude,
    longitude = longitude,
    locationTypeRaw = locationTypeRaw,
    locationName = locationName,
    electricityRatePerKWh = electricityRatePerKWh,
    actualCost = actualCost,
    manualEnergyKWh = manualEnergyKWh,
    rateWasLocationAware = rateWasLocationAware,
    tariff = tariff,
    speedSamples = speedSamples,
)

private fun TripRecord.toRow() = BackupArchive.TripRow(
    id = id,
    vin = vin,
    tripKey = tripKey,
    distance = distance,
    odometer = odometer,
    accessoriesEnergy = accessoriesEnergy,
    totalEnergyUsed = totalEnergyUsed,
    regenEnergy = regenEnergy,
    climateEnergy = climateEnergy,
    drivetrainEnergy = drivetrainEnergy,
    batteryCareEnergy = batteryCareEnergy,
    startDate = startDate,
    durationSeconds = durationSeconds,
    avgSpeed = avgSpeed,
    maxSpeed = maxSpeed,
    savedDate = savedDate,
    customName = customName,
    isFavorite = isFavorite,
    startLocationName = startLocationName,
    endLocationName = endLocationName,
    startLatitude = startLatitude,
    startLongitude = startLongitude,
    endLatitude = endLatitude,
    endLongitude = endLongitude,
)

private fun RemoteCommandActivity.toRow() = BackupArchive.RemoteCommandRow(
    id = id,
    vin = vin,
    vehicleName = vehicleName,
    commandName = commandName,
    sourceName = sourceName,
    stateRaw = stateRaw,
    startedAt = startedAt,
    completedAt = completedAt,
    errorMessage = errorMessage,
)

private fun MaintenanceReminder.toRow() = BackupArchive.MaintenanceRow(
    id = id,
    vin = vin,
    title = title,
    notes = notes,
    dueDate = dueDate,
    dueOdometer = dueOdometer,
    cost = cost,
    createdAt = createdAt,
    completedAt = completedAt,
    lastNotifiedAt = lastNotifiedAt,
)

private fun VehicleAutomation.toRow() = BackupArchive.AutomationRow(
    id = id,
    vin = vin,
    title = title,
    kindRaw = kindRaw,
    isEnabled = isEnabled,
    threshold = threshold,
    hour = hour,
    minute = minute,
    lastTriggeredAt = lastTriggeredAt,
    createdAt = createdAt,
)
