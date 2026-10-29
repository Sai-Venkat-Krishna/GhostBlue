package com.saivenkatkrishna.ghostblue.data.account

import androidx.room.withTransaction
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.vehicle.effectiveBatteryCapacityKWh
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleRecord
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleStore
import java.util.UUID

/**
 * The test car's server state is the app's own vehicle row, as on iOS
 * (`SwiftDataFakeVehicleProvider`): a command changes the row, and the next
 * status read returns it. Saves write only the fields a server reports.
 */
class RoomFakeVehicleStore(private val db: GhostBlueDatabase) : FakeVehicleStore {
    override suspend fun records(accountId: UUID): List<FakeVehicleRecord> =
        db.vehicles().withApiAccountId(accountId).map { it.toFakeRecord() }

    override suspend fun record(vin: String, accountId: UUID): FakeVehicleRecord? =
        db.vehicles().byVinAndApiAccountId(vin, accountId)?.toFakeRecord()

    override suspend fun save(record: FakeVehicleRecord) {
        db.withTransaction {
            val row = db.vehicles().byVinAndApiAccountId(record.vin, record.accountId) ?: return@withTransaction
            row.evStatus = record.evStatus
            row.gasRange = record.gasRange
            row.location = record.location
            row.lockStatus = record.lockStatus
            row.climateStatus = record.climateStatus
            row.battery12V = record.battery12V
            row.doorOpen = record.doorOpen
            row.trunkOpen = record.trunkOpen
            row.hoodOpen = record.hoodOpen
            row.tirePressureWarning = record.tirePressureWarning
            row.syncDate = record.syncDate
            row.lastUpdated = record.lastUpdated
            db.vehicles().update(row)
        }
    }
}

private fun BBVehicle.toFakeRecord() = FakeVehicleRecord(
    vehicle = toVehicle(),
    evStatus = evStatus,
    gasRange = gasRange,
    location = location,
    lockStatus = lockStatus,
    climateStatus = climateStatus,
    battery12V = battery12V,
    doorOpen = doorOpen,
    trunkOpen = trunkOpen,
    hoodOpen = hoodOpen,
    tirePressureWarning = tirePressureWarning,
    syncDate = syncDate,
    lastUpdated = lastUpdated,
    debugConfiguration = debugConfiguration?.toFake(),
    // iOS simulates with the owner's capacity or the model default.
    batteryCapacityKWh = effectiveBatteryCapacityKWh,
    pendingChargingStopDate = pendingChargingStopDate,
)
