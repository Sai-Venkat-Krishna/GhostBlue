package com.saivenkatkrishna.ghostblue.data.db

import androidx.room.TypeConverter
import com.saivenkatkrishna.ghostblue.data.charging.ChargingTariff
import com.saivenkatkrishna.ghostblue.data.model.BBDebugConfiguration
import com.saivenkatkrishna.ghostblue.data.model.ChargeSpeedSample
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLog
import com.saivenkatkrishna.ghostblue.kit.model.KitJson
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Column encodings. Instants are stored to the nanosecond so a sample read
 * back compares equal to the one that was written (replays are detected by
 * date); structured values are JSON in the shapes Swift encodes them.
 */
class Converters {
    @TypeConverter fun uuidToText(value: UUID?): String? = value?.toString()?.uppercase()

    @TypeConverter fun textToUUID(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter fun instantToNanos(value: Instant?): Long? =
        value?.let { Math.addExact(Math.multiplyExact(it.epochSecond, NANOS_PER_SECOND), it.nano.toLong()) }

    @TypeConverter fun nanosToInstant(value: Long?): Instant? =
        value?.let { Instant.ofEpochSecond(Math.floorDiv(it, NANOS_PER_SECOND), Math.floorMod(it, NANOS_PER_SECOND)) }

    @TypeConverter fun distanceToJson(value: Distance?): String? = value?.let { KitJson.encodeToString(Distance.serializer(), it) }

    @TypeConverter fun jsonToDistance(value: String?): Distance? = value?.let { KitJson.decodeFromString(Distance.serializer(), it) }

    @TypeConverter fun fuelRangeToJson(value: VehicleStatus.FuelRange?): String? =
        value?.let { KitJson.encodeToString(VehicleStatus.FuelRange.serializer(), it) }

    @TypeConverter fun jsonToFuelRange(value: String?): VehicleStatus.FuelRange? =
        value?.let { KitJson.decodeFromString(VehicleStatus.FuelRange.serializer(), it) }

    @TypeConverter fun evStatusToJson(value: VehicleStatus.EVStatus?): String? =
        value?.let { KitJson.encodeToString(VehicleStatus.EVStatus.serializer(), it) }

    @TypeConverter fun jsonToEVStatus(value: String?): VehicleStatus.EVStatus? =
        value?.let { KitJson.decodeFromString(VehicleStatus.EVStatus.serializer(), it) }

    @TypeConverter fun locationToJson(value: VehicleStatus.Location?): String? =
        value?.let { KitJson.encodeToString(VehicleStatus.Location.serializer(), it) }

    @TypeConverter fun jsonToLocation(value: String?): VehicleStatus.Location? =
        value?.let { KitJson.decodeFromString(VehicleStatus.Location.serializer(), it) }

    @TypeConverter fun lockStatusToText(value: VehicleStatus.LockStatus?): String? = value?.name?.lowercase()

    @TypeConverter fun textToLockStatus(value: String?): VehicleStatus.LockStatus? =
        value?.let { raw -> VehicleStatus.LockStatus.entries.firstOrNull { it.name.lowercase() == raw } }

    @TypeConverter fun climateStatusToJson(value: VehicleStatus.ClimateStatus?): String? =
        value?.let { KitJson.encodeToString(VehicleStatus.ClimateStatus.serializer(), it) }

    @TypeConverter fun jsonToClimateStatus(value: String?): VehicleStatus.ClimateStatus? =
        value?.let { KitJson.decodeFromString(VehicleStatus.ClimateStatus.serializer(), it) }

    @TypeConverter fun doorStatusToJson(value: VehicleStatus.DoorStatus?): String? =
        value?.let { KitJson.encodeToString(VehicleStatus.DoorStatus.serializer(), it) }

    @TypeConverter fun jsonToDoorStatus(value: String?): VehicleStatus.DoorStatus? =
        value?.let { KitJson.decodeFromString(VehicleStatus.DoorStatus.serializer(), it) }

    @TypeConverter fun tireWarningToJson(value: VehicleStatus.TirePressureWarning?): String? =
        value?.let { KitJson.encodeToString(VehicleStatus.TirePressureWarning.serializer(), it) }

    @TypeConverter fun jsonToTireWarning(value: String?): VehicleStatus.TirePressureWarning? =
        value?.let { KitJson.decodeFromString(VehicleStatus.TirePressureWarning.serializer(), it) }

    @TypeConverter fun debugConfigurationToJson(value: BBDebugConfiguration?): String? =
        value?.let { KitJson.encodeToString(BBDebugConfiguration.serializer(), it) }

    @TypeConverter fun jsonToDebugConfiguration(value: String?): BBDebugConfiguration? =
        value?.let { KitJson.decodeFromString(BBDebugConfiguration.serializer(), it) }

    @TypeConverter fun climateOptionsToJson(value: ClimateOptions?): String? =
        value?.let { KitJson.encodeToString(ClimateOptions.serializer(), it) }

    @TypeConverter fun jsonToClimateOptions(value: String?): ClimateOptions? =
        value?.let { KitJson.decodeFromString(ClimateOptions.serializer(), it) }

    @TypeConverter fun tariffToJson(value: ChargingTariff?): String? =
        value?.let { KitJson.encodeToString(ChargingTariff.serializer(), it) }

    @TypeConverter fun jsonToTariff(value: String?): ChargingTariff? =
        value?.let { KitJson.decodeFromString(ChargingTariff.serializer(), it) }

    @TypeConverter fun samplesToJson(value: List<ChargeSpeedSample>?): String? =
        value?.let { KitJson.encodeToString(sampleList, it) }

    @TypeConverter fun jsonToSamples(value: String?): List<ChargeSpeedSample>? =
        value?.let { KitJson.decodeFromString(sampleList, it) }

    @TypeConverter fun httpLogToJson(value: HTTPLog?): String? = value?.let { KitJson.encodeToString(HTTPLog.serializer(), it) }

    @TypeConverter fun jsonToHTTPLog(value: String?): HTTPLog? = value?.let { KitJson.decodeFromString(HTTPLog.serializer(), it) }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        // Typed explicitly: KSP runs before the serialization plugin generates `serializer()`.
        val sampleList: KSerializer<List<ChargeSpeedSample>> = ListSerializer(ChargeSpeedSample.serializer())
    }
}
