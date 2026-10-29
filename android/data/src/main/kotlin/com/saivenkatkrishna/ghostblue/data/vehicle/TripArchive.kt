package com.saivenkatkrishna.ghostblue.data.vehicle

import androidx.room.withTransaction
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail

/**
 * Saves API trips not seen before. The API returns only its latest few, so
 * a trip missing from a response is never treated as deleted.
 */
suspend fun BBVehicle.archiveTrips(trips: List<EVTripDetail>, db: GhostBlueDatabase) {
    if (trips.isEmpty()) return
    val vin = vin
    val inserted = db.withTransaction {
        val existingKeys = db.trips().existingKeys(vin, trips.map { TripRecord.key(vin, it) }).toMutableSet()
        var inserted = 0
        for (trip in trips) {
            // Updated as we go, so a duplicate inside one response isn't inserted twice.
            if (!existingKeys.add(TripRecord.key(vin, trip))) continue
            db.trips().insert(TripRecord(vin, trip))
            inserted++
        }
        inserted
    }
    if (inserted > 0) BBLogger.info(BBLogCategory.APP, "Archived ${trips.size} fetched trips for $displayName")
}
