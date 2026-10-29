package com.saivenkatkrishna.ghostblue.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import java.util.UUID

/** Saved climate settings for one car. Deleting the car deletes its presets. */
@Entity(
    tableName = "climate_presets",
    indices = [Index("vehicleId")],
    foreignKeys = [
        ForeignKey(
            entity = BBVehicle::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
class ClimatePreset() {
    @PrimaryKey var id: UUID = UUID.randomUUID()
    var name: String = ""
    var iconName: String = ""
    var climateOptions: ClimateOptions = ClimateOptions()
    var isSelected: Boolean = false
    var sortOrder: Int = 0

    /** The owning car: iOS's `vehicle` relationship. */
    var vehicleId: UUID? = null

    @Ignore
    constructor(
        name: String,
        iconName: String = "fan",
        climateOptions: ClimateOptions,
        isSelected: Boolean = false,
        vehicleId: UUID,
    ) : this() {
        this.name = name
        this.iconName = iconName
        this.climateOptions = climateOptions
        this.isSelected = isSelected
        this.vehicleId = vehicleId
    }

    companion object {
        /** SF Symbol names, kept so presets move between the apps; phase 3 maps them to Material icons. */
        val availableIcons = listOf(
            "fan" to "Fan",
            "thermometer" to "Thermometer",
            "snowflake" to "Snowflake",
            "sun.max" to "Sun",
            "wind" to "Wind",
            "cloud.snow" to "Snow",
        )
    }
}
