package com.saivenkatkrishna.ghostblue.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.util.UUID

/** Charge port, for the DC plug icon. */
enum class ChargePortType(val rawValue: String) {
    CCS1("CCS1"),
    CCS2("CCS2"),
    NACS("NACS");

    val displayName: String get() = rawValue

    companion object {
        fun fromRawValue(raw: String): ChargePortType? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Paint colours the vehicle image host renders. */
enum class VehicleImageColor(val rawValue: String, val displayName: String) {
    WHITE("white", "White"),
    BLACK("black", "Black"),
    GREY("grey", "Gray"),
    BLUE("blue", "Blue"),
    GREEN("green", "Green"),
    RED("red", "Red"),
    SILVER("silver", "Silver"),
    MATTE_GREY("matte-grey", "Matte Gray"),
    METALLIC_GREY("metallic-grey", "Metallic Gray"),
    ORANGE("orange", "Orange");

    companion object {
        fun fromRawValue(raw: String): VehicleImageColor? = entries.firstOrNull { it.rawValue == raw }

        /** Only the variants the image host has for this model, so a choice never falls back to white. */
        fun availableColors(modelName: String): List<VehicleImageColor> {
            val normalized = modelName.replace(Regex("""^\d{4}\s+"""), "")
                .lowercase().replace(" ", "").replace("-", "")
            return when {
                "ioniq5n" in normalized -> listOf(WHITE, BLACK, GREY, BLUE, MATTE_GREY, ORANGE)
                "ioniq5" in normalized -> listOf(WHITE, BLACK, GREY, BLUE, GREEN, MATTE_GREY)
                "ioniq6" in normalized -> listOf(WHITE, BLACK, GREY, BLUE, RED, MATTE_GREY, METALLIC_GREY)
                "ioniq9" in normalized -> listOf(WHITE, BLACK, GREY, BLUE, GREEN, RED, MATTE_GREY, METALLIC_GREY)
                "ioniq" in normalized -> listOf(WHITE, BLACK, GREY, BLUE, GREEN, RED, SILVER)
                "ev6" in normalized -> listOf(WHITE, BLACK, GREY, BLUE, RED, MATTE_GREY, METALLIC_GREY)
                "ev9" in normalized -> listOf(WHITE, BLACK, GREY, BLUE, GREEN, RED, SILVER, METALLIC_GREY)
                "kona" in normalized -> listOf(WHITE, BLACK, GREY, RED, METALLIC_GREY)
                "niro" in normalized -> listOf(WHITE, BLACK, GREY, BLUE, GREEN, RED, METALLIC_GREY)
                else -> listOf(WHITE)
            }
        }
    }
}

/**
 * A car, its last status and everything the owner set for it. Port of
 * GhostBlue's SwiftData `BBVehicle`; the field names match so backups and
 * the logic read the same on both platforms.
 */
@Entity(
    tableName = "vehicles",
    indices = [Index(value = ["vin"], unique = true), Index("ownerAccountId"), Index("accountId")],
    foreignKeys = [
        ForeignKey(
            entity = BBAccount::class,
            parentColumns = ["id"],
            childColumns = ["ownerAccountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
class BBVehicle() {
    @PrimaryKey var id: UUID = UUID.randomUUID()
    var vin: String = ""

    var regId: String = ""
    var model: String = ""

    /** The API's account id for this car (the owning account's id). */
    var accountId: UUID = UUID.randomUUID()

    /** The owning account row: iOS's `account` relationship. Deleting the account deletes the car. */
    var ownerAccountId: UUID? = null
    var fuelTypeRaw: String = FuelType.GAS.rawValue
    var generation: Int = 0
    var odometer: Distance = Distance(0.0, Distance.Units.MILES)

    // Status; null until a status has been fetched.
    var lastUpdated: Instant? = null
    var syncDate: Instant? = null
    var gasRange: VehicleStatus.FuelRange? = null
    var evStatus: VehicleStatus.EVStatus? = null

    /** An unconfirmed charging stop, shared by the app, widgets and automations. */
    var pendingChargingStopDate: Instant? = null

    /**
     * When the pending stop was first fetched; a stop nothing contradicts
     * for `ChargeStopPolicy.CONFIRMATION_DELAY` confirms even when BlueLink
     * keeps serving the same snapshot.
     */
    var pendingChargingStopObservedAt: Instant? = null

    /**
     * Set when GhostBlue sends a stop-charge command, so the "not charging"
     * reading that follows confirms without a second sample.
     */
    var chargeStopRequestedAt: Instant? = null
    var location: VehicleStatus.Location? = null
    var lockStatus: VehicleStatus.LockStatus? = null
    var climateStatus: VehicleStatus.ClimateStatus? = null

    var battery12V: Int? = null
    var doorOpen: VehicleStatus.DoorStatus? = null
    var trunkOpen: Boolean? = null
    var hoodOpen: Boolean? = null
    var tirePressureWarning: VehicleStatus.TirePressureWarning? = null

    // Owner's settings.
    var customName: String? = null
    var isHidden: Boolean = false
    var sortOrder: Int = 0
    var backgroundColorName: String = "default"
    var watchBackgroundColorName: String = "charcoal"
    var vehicleImageColorRaw: String = VehicleImageColor.WHITE.rawValue
    var chargePortTypeRaw: String = ChargePortType.CCS1.rawValue

    /** Usable capacity for turning charge percentage into energy; null uses the model default. */
    var batteryCapacityKWh: Double? = null

    /** Electricity price per kWh; null hides cost estimates. */
    var electricityRatePerKWh: Double? = null
    var offPeakElectricityRatePerKWh: Double? = null

    /** Default public DC price; a session can store its own receipt total. */
    var dcFastChargingRatePerKWh: Double? = null
    var peakElectricityRatePerKWh: Double? = null
    var offPeakStartHour: Int = 0
    var offPeakEndHour: Int = 6
    var monthlyChargingBudget: Double? = null

    /** Private home geofence, used on the device only. */
    var homeLatitude: Double? = null
    var homeLongitude: Double? = null
    var homePrivacyRadiusMeters: Double = 300.0

    // SDG&E smart charging, on top of the car's own 12 AM–2 PM schedule.
    var smartChargingEnabled: Boolean = false
    var smartChargingHomeOnly: Boolean = true
    var smartChargingTargetSOC: Int = 80
    var smartChargingLastRunAt: Instant? = null
    var smartChargingLastResult: String? = null
    var smartChargingLastConfirmedAt: Instant? = null
    var smartChargingOverrideRaw: String? = null
    var smartChargingOverrideExpiresAt: Instant? = null

    // Alert rules, per car.
    var notifyChargeComplete: Boolean = true
    var notifyChargeInterrupted: Boolean = true
    var notifyUnlocked: Boolean = false
    var notifyOpenPanels: Boolean = true
    var notifyLowEVBattery: Boolean = true
    var notifyLow12VBattery: Boolean = true
    var notifyTirePressure: Boolean = true
    var lowEVBatteryThreshold: Int = 20
    var low12VBatteryThreshold: Int = 35

    /** The previous status's alert snapshot (JSON), to alert on changes only. */
    var alertSnapshotData: String? = null

    /** Last background attempt to archive the API's rolling trip window. */
    var lastTripArchiveAttemptDate: Instant? = null
    var debugConfiguration: BBDebugConfiguration? = null
    var debugLiveActivity: Boolean = false

    /** Shows seat heat controls on generation < 3 cars. */
    var enableSeatHeatControls: Boolean = false

    /** Pins the climate duration picker on or off; null uses the region default. */
    var showClimateDurationOverride: Boolean? = null

    /** Owner's powertrain override; null uses the API's. */
    var fuelTypeOverrideRaw: String? = null

    /** ABRP Live Data token. */
    var abrpUserToken: String? = null
    var abrpCarModel: String? = null

    // Per-action accent colours by palette name; null uses the default.
    var primaryColorName: String? = null
    var chargingColorName: String? = null
    var gasColorName: String? = null
    var lockColorName: String? = null
    var unlockColorName: String? = null
    var startClimateColorName: String? = null
    var stopColorName: String? = null

    /** Kia's vehicle key; not stored, refetched with the vehicle list. */
    @Ignore var vehicleKey: String? = null

    @Ignore
    constructor(vehicle: Vehicle, backgroundColorName: String? = null) : this() {
        vin = vehicle.vin
        regId = vehicle.regId
        model = vehicle.model
        accountId = vehicle.accountId
        fuelTypeRaw = vehicle.fuelType.rawValue
        generation = vehicle.generation
        odometer = vehicle.odometer
        vehicleKey = vehicle.vehicleKey
        if (backgroundColorName != null) this.backgroundColorName = backgroundColorName
    }

    var chargePortType: ChargePortType
        get() = ChargePortType.fromRawValue(chargePortTypeRaw) ?: ChargePortType.CCS1
        set(value) {
            chargePortTypeRaw = value.rawValue
        }

    var vehicleImageColor: VehicleImageColor
        get() = if (vehicleImageColorRaw == "gray") VehicleImageColor.GREY
        else VehicleImageColor.fromRawValue(vehicleImageColorRaw) ?: VehicleImageColor.WHITE
        set(value) {
            vehicleImageColorRaw = value.rawValue
        }

    var fuelTypeOverride: FuelType?
        get() = fuelTypeOverrideRaw?.let { raw -> FuelType.entries.firstOrNull { it.rawValue == raw } }
        set(value) {
            fuelTypeOverrideRaw = value?.rawValue
        }

    /** The override when set, else the API's powertrain. */
    var fuelType: FuelType
        get() = fuelTypeOverride ?: FuelType.entries.firstOrNull { it.rawValue == fuelTypeRaw } ?: FuelType.GAS
        set(value) {
            fuelTypeRaw = value.rawValue
        }

    val displayName: String
        get() = customName?.takeIf { it.isNotBlank() } ?: model

    /**
     * Whether to show the climate duration picker: the override when pinned,
     * otherwise generation 3+ and every region outside the USA.
     */
    fun showClimateDuration(accountRegion: Region?): Boolean {
        showClimateDurationOverride?.let { return it }
        if (generation >= 3) return true
        return accountRegion != Region.USA
    }

    fun toVehicle() = Vehicle(
        vin = vin,
        regId = regId,
        model = model,
        accountId = accountId,
        fuelType = fuelType,
        generation = generation,
        odometer = odometer,
        vehicleKey = vehicleKey,
    )

    override fun toString(): String = "BBVehicle(vin=$vin, model=$model)"
}
