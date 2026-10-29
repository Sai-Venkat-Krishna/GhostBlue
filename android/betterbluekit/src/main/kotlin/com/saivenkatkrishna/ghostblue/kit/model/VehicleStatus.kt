package com.saivenkatkrishna.ghostblue.kit.model

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class VehicleStatus(
    val vin: String,
    @Serializable(with = Iso8601InstantSerializer::class) val lastUpdated: Instant = Instant.now(),
    @Serializable(with = Iso8601InstantSerializer::class) val syncDate: Instant? = null,
    val gasRange: FuelRange? = null,
    val evStatus: EVStatus? = null,
    val location: Location,
    val lockStatus: LockStatus,
    val climateStatus: ClimateStatus,
    val odometer: Distance? = null,
    val battery12V: Int? = null,
    val doorOpen: DoorStatus? = null,
    val trunkOpen: Boolean? = null,
    val hoodOpen: Boolean? = null,
    val tirePressureWarning: TirePressureWarning? = null,
    val engineOn: Boolean? = null,
    val accessoryOn: Boolean? = null,
    val remoteIgnition: Boolean? = null,
    val transmissionCondition: Boolean? = null,
    val sleepMode: Boolean? = null,
    val washerFluidLow: Boolean? = null,
) {
    @Serializable
    data class FuelRange(val range: Distance, val percentage: Double)

    /** The API's `batteryPlugin`: 0 unplugged, 2 AC, anything else DC. */
    @Serializable(with = PlugTypeSerializer::class)
    enum class PlugType(val rawValue: Int) {
        UNPLUGGED(0),
        AC_CHARGER(2),
        DC_CHARGER(1);

        companion object {
            fun fromBatteryPlugin(value: Int): PlugType = when (value) {
                0 -> UNPLUGGED
                2 -> AC_CHARGER
                else -> DC_CHARGER
            }
        }
    }

    /**
     * Serial names match Swift's stored properties (`maybePlugType`,
     * `maybeChargeTimeSeconds`) so encoded statuses read on both platforms.
     */
    @Serializable
    data class EVStatus(
        val charging: Boolean,
        val chargeSpeed: Double,
        val evRange: FuelRange,
        @SerialName("maybePlugType") val plugType: PlugType = PlugType.UNPLUGGED,
        @SerialName("maybeChargeTimeSeconds") val chargeTimeSeconds: Long = 0,
        val targetSocAC: Double? = null,
        val targetSocDC: Double? = null,
    ) {
        val pluggedIn: Boolean get() = plugType != PlugType.UNPLUGGED
        val chargeTime: Duration get() = chargeTimeSeconds.seconds

        /** The charge limit for the plug in use; nil when unplugged. */
        val currentTargetSOC: Double?
            get() = when (plugType) {
                PlugType.AC_CHARGER -> targetSocAC
                PlugType.DC_CHARGER -> targetSocDC
                PlugType.UNPLUGGED -> null
            }
    }

    @Serializable
    data class Location(val latitude: Double, val longitude: Double) {
        val debug: String get() = "$latitude°, $longitude°"
    }

    @Serializable
    enum class LockStatus {
        @SerialName("locked") LOCKED,
        @SerialName("unlocked") UNLOCKED,
        @SerialName("unknown") UNKNOWN;

        fun toggled(): LockStatus = when (this) {
            LOCKED -> UNLOCKED
            UNLOCKED -> LOCKED
            UNKNOWN -> UNKNOWN
        }

        companion object {
            fun fromLocked(locked: Boolean?): LockStatus = when (locked) {
                null -> UNKNOWN
                true -> LOCKED
                false -> UNLOCKED
            }
        }
    }

    @Serializable
    data class ClimateStatus(
        val defrostOn: Boolean,
        val airControlOn: Boolean,
        val steeringWheelHeatingOn: Boolean,
        val temperature: Temperature,
    )

    @Serializable
    data class DoorStatus(
        val frontLeft: Boolean,
        val frontRight: Boolean,
        val backLeft: Boolean,
        val backRight: Boolean,
    ) {
        val anyOpen: Boolean get() = frontLeft || frontRight || backLeft || backRight

        val openDoorsDescription: String
            get() = listOfNotNull(
                "FL".takeIf { frontLeft },
                "FR".takeIf { frontRight },
                "BL".takeIf { backLeft },
                "BR".takeIf { backRight },
            ).ifEmpty { listOf("None") }.joinToString(", ")
    }

    @Serializable
    data class TirePressureWarning(
        val frontLeft: Boolean,
        val frontRight: Boolean,
        val rearLeft: Boolean,
        val rearRight: Boolean,
        val all: Boolean,
    ) {
        val hasWarning: Boolean get() = all || frontLeft || frontRight || rearLeft || rearRight

        val warningDescription: String
            get() {
                if (all) return "All tires"
                return listOfNotNull(
                    "FL".takeIf { frontLeft },
                    "FR".takeIf { frontRight },
                    "RL".takeIf { rearLeft },
                    "RR".takeIf { rearRight },
                ).ifEmpty { listOf("OK") }.joinToString(", ")
            }
    }
}

/** Plug types travel as their integer raw values, as in Swift. */
object PlugTypeSerializer : KSerializer<VehicleStatus.PlugType> {
    override val descriptor = PrimitiveSerialDescriptor("PlugType", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: VehicleStatus.PlugType) {
        encoder.encodeInt(value.rawValue)
    }

    override fun deserialize(decoder: Decoder): VehicleStatus.PlugType {
        val raw = decoder.decodeInt()
        return VehicleStatus.PlugType.entries.firstOrNull { it.rawValue == raw }
            ?: throw SerializationException("Unknown plug type $raw")
    }
}
