package com.saivenkatkrishna.ghostblue.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.saivenkatkrishna.ghostblue.data.charging.ChargingTariff
import com.saivenkatkrishna.ghostblue.kit.model.Iso8601InstantSerializer
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

/** One charger power reading inside a session. */
@Serializable
data class ChargeSpeedSample(
    @Serializable(with = Iso8601InstantSerializer::class) val date: Instant,
    val speedKW: Double,
    val batteryPercentage: Double,
)

/** Where a session happened. `AUTOMATIC` is inferred from the plug type and the home geofence. */
enum class ChargingLocationType(val rawValue: String, val title: String) {
    AUTOMATIC("automatic", "Automatic"),
    HOME("home", "Home"),
    PUBLIC_AC("publicAC", "Public AC"),
    PUBLIC_DC("publicDC", "Public DC"),
    OTHER("other", "Other");

    companion object {
        fun fromRawValue(raw: String): ChargingLocationType? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * One charging session, detected from status polling (see
 * `BBVehicle.recordChargingActivity`). Port of GhostBlue's `ChargeSession`.
 */
@Entity(tableName = "charge_sessions", indices = [Index("vin"), Index("startDate")])
class ChargeSession() {
    @PrimaryKey var id: UUID = UUID.randomUUID()
    var vin: String = ""
    var startDate: Instant = Instant.now()

    /** Null while the session is in progress. */
    var endDate: Instant? = null
    var startPercentage: Double = 0.0
    var endPercentage: Double = 0.0

    /** Integrated from charger power between polls; a lower bound when polling is sparse. */
    var energyAddedKWh: Double = 0.0
    var maxChargeSpeedKW: Double = 0.0
    var isDCFast: Boolean = false

    /** The newest status sample folded in; replays at or before it are ignored. */
    var lastSampleDate: Instant = Instant.now()

    /** A single non-charging reading is only a candidate stop; BlueLink can blip false mid-charge. */
    var pendingStopDate: Instant? = null
    var pendingStopObservedAt: Instant? = null
    var pendingStopPercentage: Double? = null
    var latitude: Double? = null
    var longitude: Double? = null
    var locationTypeRaw: String = ChargingLocationType.AUTOMATIC.rawValue
    var locationName: String? = null

    /** Rate copied at the start, so later tariff edits don't rewrite history. */
    var electricityRatePerKWh: Double? = null

    /** Receipt total; wins over estimates. */
    var actualCost: Double? = null

    /** Meter or receipt reading; wins over estimates. */
    var manualEnergyKWh: Double? = null
    var tariff: ChargingTariff? = null

    /** False for sessions from before location-aware pricing. */
    var rateWasLocationAware: Boolean = false

    /** The last [MAX_SPEED_SAMPLES] power readings. */
    var speedSamples: List<ChargeSpeedSample> = emptyList()
        set(value) {
            field = value.takeLast(MAX_SPEED_SAMPLES)
        }

    @Ignore
    constructor(
        vin: String,
        startDate: Instant,
        startPercentage: Double,
        isDCFast: Boolean,
        latitude: Double? = null,
        longitude: Double? = null,
    ) : this() {
        this.vin = vin
        this.startDate = startDate
        this.startPercentage = startPercentage
        endPercentage = startPercentage
        this.isDCFast = isDCFast
        lastSampleDate = startDate
        this.latitude = latitude
        this.longitude = longitude
    }

    val isActive: Boolean get() = endDate == null

    var locationType: ChargingLocationType
        get() = ChargingLocationType.fromRawValue(locationTypeRaw) ?: ChargingLocationType.AUTOMATIC
        set(value) {
            locationTypeRaw = value.rawValue
        }

    val percentAdded: Double get() = max(0.0, endPercentage - startPercentage)

    /** Seconds. */
    val duration: Double get() = secondsBetween(startDate, endDate ?: lastSampleDate)

    /** False for blips not worth listing: one sample, no charge gained. */
    val isMeaningful: Boolean get() = isActive || percentAdded >= 1 || duration >= 300

    /** A manual reading, else capacity × percent added, else the sampled energy capped by the charge gained. */
    fun resolvedEnergyKWh(batteryCapacityKWh: Double?): Double {
        manualEnergyKWh?.let { if (it.isFinite() && it >= 0) return it }
        if (batteryCapacityKWh != null && batteryCapacityKWh > 0 && percentAdded > 0) {
            return batteryCapacityKWh * percentAdded / 100
        }
        return plausibleSampledEnergyKWh(batteryCapacityKWh)
    }

    /**
     * Sampled energy, capped by the charge actually gained (plus one percent
     * of reporting resolution): a reading held across a long gap can
     * integrate to more than the pack accepted.
     */
    fun plausibleSampledEnergyKWh(batteryCapacityKWh: Double?): Double {
        if (!energyAddedKWh.isFinite() || energyAddedKWh <= 0) return 0.0
        val capacity = batteryCapacityKWh?.takeIf { it > 0 } ?: MAX_PLAUSIBLE_CAPACITY_KWH
        return min(energyAddedKWh, capacity * (percentAdded + 1) / 100)
    }

    fun energySource(batteryCapacityKWh: Double?, capacityIsModelDefault: Boolean = false): String {
        if (manualEnergyKWh != null) return "Measured · manual"
        if (batteryCapacityKWh == null || batteryCapacityKWh <= 0 || percentAdded <= 0) {
            return "Estimated from charger samples"
        }
        val capacityText = formatUpToOneDecimal(batteryCapacityKWh)
        return if (capacityIsModelDefault) "Estimated from battery · $capacityText kWh model default"
        else "Estimated from battery · $capacityText kWh"
    }

    fun appendSpeedSample(date: Instant, speedKW: Double, batteryPercentage: Double) {
        if (speedSamples.lastOrNull()?.date == date) return
        speedSamples = speedSamples + ChargeSpeedSample(date, max(0.0, speedKW), batteryPercentage)
    }

    /** User corrections from Charging History, normalised the same way for new and edited sessions. */
    fun applyUserEdits(
        startDate: Instant,
        endDate: Instant?,
        startPercentage: Double,
        endPercentage: Double,
        energyAddedKWh: Double,
        maxChargeSpeedKW: Double,
        isDCFast: Boolean,
        locationType: ChargingLocationType,
        electricityRatePerKWh: Double?,
        actualCost: Double?,
        manualEnergyKWh: Double? = null,
        tariff: ChargingTariff? = null,
    ) {
        this.startDate = startDate
        this.endDate = endDate
        lastSampleDate = endDate ?: maxOf(lastSampleDate, startDate)
        this.startPercentage = min(100.0, max(0.0, startPercentage))
        this.endPercentage = min(100.0, max(0.0, endPercentage))
        this.energyAddedKWh = max(0.0, energyAddedKWh)
        this.maxChargeSpeedKW = max(0.0, maxChargeSpeedKW)
        this.isDCFast = isDCFast
        this.locationType = locationType
        this.electricityRatePerKWh = electricityRatePerKWh?.takeIf { it > 0 }
        this.actualCost = actualCost?.takeIf { it >= 0 }
        this.manualEnergyKWh = manualEnergyKWh?.takeIf { it.isFinite() && it >= 0 }
        this.tariff = tariff
        rateWasLocationAware = true

        // Corrected dates must not leave chart samples outside the session.
        speedSamples = speedSamples.filter { it.date >= startDate && (endDate == null || it.date <= endDate) }

        if (endDate != null) {
            pendingStopDate = null
            pendingStopObservedAt = null
            pendingStopPercentage = null
        }
    }

    override fun toString(): String = "ChargeSession(vin=$vin, start=$startDate, end=$endDate)"

    companion object {
        /** Larger than any Hyundai/Kia pack; caps sampled energy when the capacity is unknown. */
        const val MAX_PLAUSIBLE_CAPACITY_KWH = 120.0
        const val MAX_SPEED_SAMPLES = 120
    }
}

/** Seconds from [from] to [to], as Swift's `timeIntervalSince`. */
fun secondsBetween(from: Instant, to: Instant): Double = Duration.between(from, to).toNanos() / 1_000_000_000.0

/**
 * "84", "77.4": zero or one fraction digit, as `.number.precision(.fractionLength(0...1))`.
 * Like ICU, it rounds the shortest decimal form, so 0.05 gives "0".
 */
internal fun formatUpToOneDecimal(value: Double): String =
    BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
