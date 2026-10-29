package com.saivenkatkrishna.ghostblue.kit.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

sealed interface VehicleCommand {
    data object Lock : VehicleCommand
    data object Unlock : VehicleCommand
    data class StartClimate(val options: ClimateOptions) : VehicleCommand
    data object StopClimate : VehicleCommand
    data object StartCharge : VehicleCommand
    data object StopCharge : VehicleCommand
    data class SetTargetSOC(val acLevel: Int, val dcLevel: Int) : VehicleCommand

    /** Short name for logs and simulated failures ("lock", "startClimate", …). */
    val name: String
        get() = when (this) {
            Lock -> "lock"
            Unlock -> "unlock"
            is StartClimate -> "startClimate"
            StopClimate -> "stopClimate"
            StartCharge -> "startCharge"
            StopCharge -> "stopCharge"
            is SetTargetSOC -> "setTargetSOC"
        }
}

/**
 * Climate start settings. Seats run 0 (off) to 3; ventilation turns a seat's
 * level into cooling instead of heating.
 *
 * The ventilation and rear-defrost fields came later and are optional: as in
 * Swift, a new value has them false but a decoded one without them has null,
 * so stored presets keep their exact shape when they pass between the apps.
 */
@Serializable(with = ClimateOptionsSerializer::class)
data class ClimateOptions(
    val climate: Boolean = true,
    val temperature: Temperature = Temperature.fromApi(1, "72"),
    val defrost: Boolean = false,
    val duration: Int = 10,
    val frontLeftSeat: Int = 0,
    val frontRightSeat: Int = 0,
    val rearLeftSeat: Int = 0,
    val rearRightSeat: Int = 0,
    val steeringWheel: Int = 0,
    val frontLeftVentilation: Boolean? = false,
    val frontRightVentilation: Boolean? = false,
    val rearLeftVentilation: Boolean? = false,
    val rearRightVentilation: Boolean? = false,
    val rearDefrost: Boolean? = false,
) {
    val frontLeftVentilationEnabled: Boolean get() = frontLeftVentilation ?: false
    val frontRightVentilationEnabled: Boolean get() = frontRightVentilation ?: false
    val rearLeftVentilationEnabled: Boolean get() = rearLeftVentilation ?: false
    val rearRightVentilationEnabled: Boolean get() = rearRightVentilation ?: false
    val rearDefrostEnabled: Boolean get() = rearDefrost ?: false

    /** The API's `heating1`: 0 off, 2 rear defrost, 3 steering wheel, 4 both. */
    val heatValue: Int
        get() = when {
            !rearDefrostEnabled && steeringWheel == 0 -> 0
            rearDefrostEnabled && steeringWheel != 0 -> 4
            rearDefrostEnabled -> 2
            steeringWheel != 0 -> 3
            else -> 0
        }

    fun seatHeaterVentInfo(): Map<String, Int> = mapOf(
        "drvSeatHeatState" to convertSeatSetting(frontLeftSeat, frontLeftVentilationEnabled),
        "astSeatHeatState" to convertSeatSetting(frontRightSeat, frontRightVentilationEnabled),
        "rlSeatHeatState" to convertSeatSetting(rearLeftSeat, rearLeftVentilationEnabled),
        "rrSeatHeatState" to convertSeatSetting(rearRightSeat, rearRightVentilationEnabled),
    )
}

/** Swift's synthesized `Codable` for `ClimateOptions`: missing optionals decode as null. */
@Serializable
@SerialName("ClimateOptions")
private class ClimateOptionsCoding(
    val climate: Boolean = true,
    val temperature: Temperature = Temperature.fromApi(1, "72"),
    val defrost: Boolean = false,
    val duration: Int = 10,
    val frontLeftSeat: Int = 0,
    val frontRightSeat: Int = 0,
    val rearLeftSeat: Int = 0,
    val rearRightSeat: Int = 0,
    val steeringWheel: Int = 0,
    val frontLeftVentilation: Boolean? = null,
    val frontRightVentilation: Boolean? = null,
    val rearLeftVentilation: Boolean? = null,
    val rearRightVentilation: Boolean? = null,
    val rearDefrost: Boolean? = null,
)

internal object ClimateOptionsSerializer : KSerializer<ClimateOptions> {
    override val descriptor: SerialDescriptor = ClimateOptionsCoding.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ClimateOptions) = encoder.encodeSerializableValue(
        ClimateOptionsCoding.serializer(),
        with(value) {
            ClimateOptionsCoding(
                climate, temperature, defrost, duration, frontLeftSeat, frontRightSeat, rearLeftSeat, rearRightSeat,
                steeringWheel, frontLeftVentilation, frontRightVentilation, rearLeftVentilation, rearRightVentilation,
                rearDefrost,
            )
        },
    )

    override fun deserialize(decoder: Decoder): ClimateOptions =
        with(decoder.decodeSerializableValue(ClimateOptionsCoding.serializer())) {
            ClimateOptions(
                climate, temperature, defrost, duration, frontLeftSeat, frontRightSeat, rearLeftSeat, rearRightSeat,
                steeringWheel, frontLeftVentilation, frontRightVentilation, rearLeftVentilation, rearRightVentilation,
                rearDefrost,
            )
        }
}

/** Seat level for the API: 0 off, 3–5 cooling, 6–8 heating. */
internal fun convertSeatSetting(value: Int, cooling: Boolean): Int = when {
    value == 0 -> 0
    cooling -> value + 2
    else -> value + 5
}
