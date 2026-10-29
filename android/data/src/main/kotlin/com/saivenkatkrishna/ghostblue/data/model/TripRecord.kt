package com.saivenkatkrishna.ghostblue.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.swiftDescription
import java.time.Instant
import java.util.UUID

/**
 * A saved copy of an API trip. The API shows only a short recent window,
 * so the archive builds the full history over time.
 */
@Entity(tableName = "trips", indices = [Index(value = ["vin", "tripKey"]), Index("startDate")])
class TripRecord() {
    @PrimaryKey var id: UUID = UUID.randomUUID()
    var vin: String = ""

    /** Deduplication key: VIN, start time and ending odometer. */
    var tripKey: String = ""
    var distance: Double = 0.0
    var odometer: Double = 0.0
    var accessoriesEnergy: Int = 0
    var totalEnergyUsed: Int = 0
    var regenEnergy: Int = 0
    var climateEnergy: Int = 0
    var drivetrainEnergy: Int = 0
    var batteryCareEnergy: Int = 0
    var startDate: Instant = Instant.now()
    var durationSeconds: Int = 0
    var avgSpeed: Double = 0.0
    var maxSpeed: Double = 0.0
    var savedDate: Instant = Instant.now()

    // The owner's notes; kept when the API window is archived again.
    var customName: String? = null
    var isFavorite: Boolean = false
    var startLocationName: String? = null
    var endLocationName: String? = null
    var startLatitude: Double? = null
    var startLongitude: Double? = null
    var endLatitude: Double? = null
    var endLongitude: Double? = null

    @Ignore
    constructor(vin: String, trip: EVTripDetail) : this() {
        this.vin = vin
        tripKey = key(vin, trip)
        distance = trip.distance
        odometer = trip.odometer
        accessoriesEnergy = trip.accessoriesEnergy
        totalEnergyUsed = trip.totalEnergyUsed
        regenEnergy = trip.regenEnergy
        climateEnergy = trip.climateEnergy
        drivetrainEnergy = trip.drivetrainEnergy
        batteryCareEnergy = trip.batteryCareEnergy
        startDate = trip.startDate
        durationSeconds = trip.durationSeconds
        avgSpeed = trip.avgSpeed
        maxSpeed = trip.maxSpeed
    }

    val tripDetail: EVTripDetail
        get() = EVTripDetail(
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
        )

    companion object {
        /** Same text as Swift's `"\(vin)-\(Int(startDate.timeIntervalSince1970))-\(odometer)"`. */
        fun key(vin: String, trip: EVTripDetail): String =
            "$vin-${swiftTruncatedSeconds(trip.startDate)}-${swiftDescription(trip.odometer)}"

        /** Swift's `Int(Double)`: truncates toward zero. */
        private fun swiftTruncatedSeconds(date: Instant): Long =
            if (date.epochSecond < 0 && date.nano > 0) date.epochSecond + 1 else date.epochSecond
    }
}
