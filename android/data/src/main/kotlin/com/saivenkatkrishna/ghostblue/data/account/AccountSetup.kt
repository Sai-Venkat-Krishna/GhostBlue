package com.saivenkatkrishna.ghostblue.data.account

import androidx.room.withTransaction
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleRecord
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleSeeds
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.isTestAccount
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Adding an account, shared by the phone and the watch, as iOS's Add
 * Account: the row first (the controller reads it), the test cars for the
 * test account (or the Fake brand), then sign-in and the vehicle list. A
 * failure removes the account again and rethrows.
 */
object AccountSetup {
    suspend fun addAccount(
        db: GhostBlueDatabase,
        controllers: AccountControllers,
        username: String,
        password: String,
        pin: String,
        brand: Brand,
        region: Region,
        testCars: List<FakeVehicleRecord> = emptyList(),
    ): BBAccount {
        val account = BBAccount(username.trim(), password, pin, effectiveBrand(username, password, brand), region)
        db.accounts().insert(account)
        try {
            if (account.brandEnum == Brand.FAKE) {
                addTestCars(db, account.id, testCars.ifEmpty { FakeVehicleSeeds.defaultRecords(account.id) })
            }
            val controller = controllers.controller(account.id)
            controller.initialize()
            controller.loadVehicles()
            return account
        } catch (error: CancellationException) {
            withContext(NonCancellable) { forget(db, controllers, account) }
            throw error
        } catch (error: Exception) {
            forget(db, controllers, account)
            throw error
        }
    }

    /** The test account always uses the test cars, whatever brand was picked. */
    fun effectiveBrand(username: String, password: String, brand: Brand): Brand =
        if (isTestAccount(username, password)) Brand.FAKE else brand

    /** The test cars as the account's vehicles: the test server reads them from the database. */
    suspend fun addTestCars(db: GhostBlueDatabase, accountId: UUID, cars: List<FakeVehicleRecord>) {
        db.withTransaction {
            for ((index, record) in cars.withIndex()) {
                val vehicle = BBVehicle(record.vehicle.copy(accountId = accountId)).apply {
                    ownerAccountId = accountId
                    sortOrder = index
                    evStatus = record.evStatus
                    gasRange = record.gasRange
                    location = record.location
                    lockStatus = record.lockStatus
                    climateStatus = record.climateStatus
                    battery12V = record.battery12V ?: 82
                    doorOpen = record.doorOpen
                    trunkOpen = record.trunkOpen
                    hoodOpen = record.hoodOpen
                    tirePressureWarning = record.tirePressureWarning
                    syncDate = record.syncDate
                    lastUpdated = record.lastUpdated
                    backgroundColorName = if (index % 2 == 0) "darkBlue" else "darkGreen"
                }
                // A VIN is unique; a test car already stored elsewhere stays there.
                if (db.vehicles().byVin(vehicle.vin) == null) db.vehicles().insert(vehicle)
            }
        }
    }

    /** Removes the account (and with it its cars and presets) and its run-time state. */
    suspend fun forget(db: GhostBlueDatabase, controllers: AccountControllers, account: BBAccount) {
        AccountController.removeAccount(account, db)
        controllers.forget(account.id)
    }
}
