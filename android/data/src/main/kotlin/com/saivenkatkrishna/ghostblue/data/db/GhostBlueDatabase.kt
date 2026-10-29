package com.saivenkatkrishna.ghostblue.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBHTTPLog
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomation

/** Everything GhostBlue stores, the Room counterpart of iOS's shared SwiftData container. */
@Database(
    entities = [
        BBAccount::class, BBVehicle::class, ClimatePreset::class, ChargeSession::class, TripRecord::class,
        RemoteCommandActivity::class, MaintenanceReminder::class, VehicleAutomation::class, BBHTTPLog::class,
    ],
    version = 1,
)
@TypeConverters(Converters::class)
abstract class GhostBlueDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun vehicles(): VehicleDao
    abstract fun climatePresets(): ClimatePresetDao
    abstract fun chargeSessions(): ChargeSessionDao
    abstract fun trips(): TripRecordDao
    abstract fun remoteCommands(): RemoteCommandDao
    abstract fun maintenanceReminders(): MaintenanceReminderDao
    abstract fun automations(): VehicleAutomationDao
    abstract fun httpLogs(): HTTPLogDao

    companion object {
        const val FILE_NAME = "ghostblue.db"

        @Volatile private var instance: GhostBlueDatabase? = null

        /** The app's database. The app, widgets and tiles share one process, so one instance. */
        fun get(context: Context): GhostBlueDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, GhostBlueDatabase::class.java, FILE_NAME)
                    .build()
                    .also { instance = it }
            }

        /** A throwaway database, for tests and previews. */
        fun inMemory(context: Context): GhostBlueDatabase =
            Room.inMemoryDatabaseBuilder(context, GhostBlueDatabase::class.java).build()
    }
}
