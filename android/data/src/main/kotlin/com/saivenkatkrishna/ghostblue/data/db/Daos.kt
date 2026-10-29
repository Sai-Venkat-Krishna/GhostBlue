package com.saivenkatkrishna.ghostblue.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBHTTPLog
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomation
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert suspend fun insert(account: BBAccount)

    @Update suspend fun update(account: BBAccount)

    @Delete suspend fun delete(account: BBAccount)

    @Query("SELECT * FROM accounts ORDER BY dateCreated")
    suspend fun all(): List<BBAccount>

    @Query("SELECT * FROM accounts ORDER BY dateCreated")
    fun observeAll(): Flow<List<BBAccount>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: UUID): BBAccount?
}

@Dao
interface VehicleDao {
    @Insert suspend fun insert(vehicle: BBVehicle)

    @Update suspend fun update(vehicle: BBVehicle)

    @Delete suspend fun delete(vehicle: BBVehicle)

    @Query("SELECT * FROM vehicles ORDER BY sortOrder")
    suspend fun all(): List<BBVehicle>

    @Query("SELECT * FROM vehicles ORDER BY sortOrder")
    fun observeAll(): Flow<List<BBVehicle>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun byId(id: UUID): BBVehicle?

    @Query("SELECT * FROM vehicles WHERE vin = :vin")
    suspend fun byVin(vin: String): BBVehicle?

    @Query("SELECT * FROM vehicles WHERE vin = :vin")
    fun observeByVin(vin: String): Flow<BBVehicle?>

    /** The account's cars: iOS's `account.vehicles` relationship. */
    @Query("SELECT * FROM vehicles WHERE ownerAccountId = :accountId ORDER BY sortOrder")
    suspend fun ownedBy(accountId: UUID): List<BBVehicle>

    /** By the API's account id, as the test car's server looks them up. */
    @Query("SELECT * FROM vehicles WHERE accountId = :accountId")
    suspend fun withApiAccountId(accountId: UUID): List<BBVehicle>

    @Query("SELECT * FROM vehicles WHERE vin = :vin AND accountId = :accountId")
    suspend fun byVinAndApiAccountId(vin: String, accountId: UUID): BBVehicle?
}

@Dao
interface ClimatePresetDao {
    @Insert suspend fun insert(preset: ClimatePreset)

    @Update suspend fun update(preset: ClimatePreset)

    @Delete suspend fun delete(preset: ClimatePreset)

    @Query("SELECT * FROM climate_presets")
    suspend fun all(): List<ClimatePreset>

    @Query("SELECT * FROM climate_presets WHERE vehicleId = :vehicleId ORDER BY sortOrder")
    suspend fun forVehicle(vehicleId: UUID): List<ClimatePreset>

    @Query("SELECT * FROM climate_presets WHERE vehicleId = :vehicleId ORDER BY sortOrder")
    fun observeForVehicle(vehicleId: UUID): Flow<List<ClimatePreset>>
}

@Dao
interface ChargeSessionDao {
    @Insert suspend fun insert(session: ChargeSession)

    @Update suspend fun update(session: ChargeSession)

    @Delete suspend fun delete(session: ChargeSession)

    @Query("SELECT * FROM charge_sessions ORDER BY startDate")
    suspend fun all(): List<ChargeSession>

    @Query("SELECT * FROM charge_sessions WHERE id = :id")
    suspend fun byId(id: UUID): ChargeSession?

    @Query("SELECT * FROM charge_sessions WHERE vin = :vin ORDER BY startDate")
    suspend fun forVin(vin: String): List<ChargeSession>

    @Query("SELECT * FROM charge_sessions WHERE vin = :vin ORDER BY startDate DESC")
    fun observeForVin(vin: String): Flow<List<ChargeSession>>

    /** The newest session still in progress. */
    @Query("SELECT * FROM charge_sessions WHERE vin = :vin AND endDate IS NULL ORDER BY startDate DESC LIMIT 1")
    suspend fun openSession(vin: String): ChargeSession?

    @Query("SELECT * FROM charge_sessions WHERE endDate IS NULL")
    suspend fun allOpen(): List<ChargeSession>
}

@Dao
interface TripRecordDao {
    @Insert suspend fun insert(trip: TripRecord)

    @Update suspend fun update(trip: TripRecord)

    @Delete suspend fun delete(trip: TripRecord)

    @Query("SELECT * FROM trips ORDER BY startDate")
    suspend fun all(): List<TripRecord>

    @Query("SELECT * FROM trips WHERE vin = :vin ORDER BY startDate DESC")
    suspend fun forVin(vin: String): List<TripRecord>

    @Query("SELECT * FROM trips WHERE vin = :vin ORDER BY startDate DESC")
    fun observeForVin(vin: String): Flow<List<TripRecord>>

    @Query("SELECT tripKey FROM trips WHERE vin = :vin AND tripKey IN (:keys)")
    suspend fun existingKeys(vin: String, keys: List<String>): List<String>
}

@Dao
interface RemoteCommandDao {
    @Insert suspend fun insert(activity: RemoteCommandActivity)

    @Update suspend fun update(activity: RemoteCommandActivity)

    @Query("SELECT * FROM remote_commands ORDER BY startedAt")
    suspend fun all(): List<RemoteCommandActivity>

    @Query("SELECT * FROM remote_commands ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RemoteCommandActivity>>
}

@Dao
interface MaintenanceReminderDao {
    @Insert suspend fun insert(reminder: MaintenanceReminder)

    @Update suspend fun update(reminder: MaintenanceReminder)

    @Delete suspend fun delete(reminder: MaintenanceReminder)

    @Query("SELECT * FROM maintenance_reminders ORDER BY createdAt")
    suspend fun all(): List<MaintenanceReminder>

    @Query("SELECT * FROM maintenance_reminders WHERE vin = :vin ORDER BY createdAt")
    suspend fun forVin(vin: String): List<MaintenanceReminder>

    /** Open reminders not yet announced. */
    @Query("SELECT * FROM maintenance_reminders WHERE vin = :vin AND completedAt IS NULL AND lastNotifiedAt IS NULL")
    suspend fun pendingForVin(vin: String): List<MaintenanceReminder>
}

@Dao
interface VehicleAutomationDao {
    @Insert suspend fun insert(automation: VehicleAutomation)

    @Update suspend fun update(automation: VehicleAutomation)

    @Delete suspend fun delete(automation: VehicleAutomation)

    @Query("SELECT * FROM automations ORDER BY createdAt")
    suspend fun all(): List<VehicleAutomation>

    @Query("SELECT * FROM automations WHERE vin = :vin ORDER BY createdAt")
    suspend fun forVin(vin: String): List<VehicleAutomation>

    @Query("SELECT * FROM automations WHERE vin = :vin AND isEnabled = 1")
    suspend fun enabledForVin(vin: String): List<VehicleAutomation>
}

@Dao
interface HTTPLogDao {
    @Insert suspend fun insert(log: BBHTTPLog)

    @Query("SELECT COUNT(*) FROM http_logs")
    suspend fun count(): Int

    @Query("SELECT * FROM http_logs ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<BBHTTPLog>>

    @Query("SELECT * FROM http_logs ORDER BY timestamp DESC")
    suspend fun all(): List<BBHTTPLog>

    @Query("DELETE FROM http_logs WHERE id IN (SELECT id FROM http_logs ORDER BY timestamp LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("DELETE FROM http_logs")
    suspend fun deleteAll()

    @Query("DELETE FROM http_logs WHERE timestamp < :before")
    suspend fun deleteBefore(before: Instant)
}
