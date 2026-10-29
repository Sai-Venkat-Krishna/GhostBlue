package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.account.SwiftAuthToken
import com.saivenkatkrishna.ghostblue.data.charging.ChargingTariff
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBDebugConfiguration
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ChargeSpeedSample
import com.saivenkatkrishna.ghostblue.data.model.ChargingLocationType
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.data.model.CommandActivityState
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomation
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomationKind
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.util.UUID

/**
 * A store with one of everything, as the Android app writes it. Its backup
 * is what `tools/parity/app-parity.sh` hands the iOS code to import.
 */
object AndroidParitySeed {
    private fun id(n: Int) = UUID.fromString("ADD201D0-0000-4000-8000-%012d".format(n))

    suspend fun seed(db: GhostBlueDatabase) {
        val account = BBAccount("pixel.owner@example.com", "pässwörd", "4321", Brand.HYUNDAI, Region.USA, "android-refresh")
            .apply {
                id = id(1)
                dateCreated = epoch(1_781_000_000.25)
                deviceId = "ANDROID-DEVICE"
                serializedAuthToken = SwiftAuthToken.encode(AuthToken("android-access", "android-refresh", epoch(1_901_000_000.75)))
            }
        db.accounts().insert(account)

        val car = BBVehicle(
            Vehicle("KMHAND00000000001", "reg-and", "IONIQ 6", account.id, FuelType.ELECTRIC, 3, Distance(8_001.5, Distance.Units.KILOMETERS)),
        ).apply {
            id = id(11)
            ownerAccountId = account.id
            lastUpdated = epoch(1_791_000_100.9)
            syncDate = epoch(1_791_000_050)
            evStatus = evStatus(
                charging = false, percentage = 81.0, chargeSpeed = 0.0, rangeMiles = 250.5,
                plugType = VehicleStatus.PlugType.DC_CHARGER, chargeTimeSeconds = 0, targetSocAC = 90.0,
            )
            location = VehicleStatus.Location(47.6062, -122.3321)
            lockStatus = VehicleStatus.LockStatus.UNKNOWN
            climateStatus = VehicleStatus.ClimateStatus(false, true, true, Temperature(21.5, Temperature.Units.CELSIUS))
            battery12V = 77
            hoodOpen = true
            customName = "Ghost 👻 Ëv"
            isHidden = true
            sortOrder = 1
            vehicleImageColorRaw = "gray"
            chargePortTypeRaw = "CCS2"
            electricityRatePerKWh = 0.1
            offPeakStartHour = 21
            offPeakEndHour = 5
            homeLatitude = 47.6
            homeLongitude = -122.33
            homePrivacyRadiusMeters = 1_000.0
            smartChargingTargetSOC = 70
            notifyChargeComplete = false
            notifyUnlocked = true
            lowEVBatteryThreshold = 10
            showClimateDurationOverride = true
            fuelTypeOverrideRaw = "electric"
            unlockColorName = "mint"
            startClimateColorName = "orange"
            // Not part of a backup.
            debugConfiguration = BBDebugConfiguration(shouldFailLock = true)
            smartChargingLastResult = "Not exported"
        }
        db.vehicles().insert(car)

        // A car without an account: iOS and Android both report it rather than drop it silently.
        db.vehicles().insert(
            BBVehicle(Vehicle("KMHAND0RPHAN00002", "reg-orphan", "Kona Electric", id(99), FuelType.ELECTRIC, 2, Distance(10.0, Distance.Units.MILES)))
                .apply { id = id(12) },
        )

        db.climatePresets().insert(
            ClimatePreset(
                "Defrost",
                "wind",
                ClimateOptions(
                    temperature = Temperature(82.0, Temperature.Units.FAHRENHEIT), defrost = true, duration = 30,
                    rearLeftSeat = 2, rearRightSeat = 2, rearRightVentilation = null, rearDefrost = true,
                ),
                isSelected = true,
                vehicleId = car.id,
            ).apply {
                id = id(21)
                sortOrder = 2
            },
        )

        db.chargeSessions().insert(
            ChargeSession(car.vin, epoch(1_791_100_000.6), 22.5, isDCFast = false, latitude = 47.6, longitude = -122.33).apply {
                id = id(31)
                endDate = epoch(1_791_128_800)
                lastSampleDate = epoch(1_791_128_800)
                endPercentage = 90.0
                energyAddedKWh = 52.25
                maxChargeSpeedKW = 11.5
                locationType = ChargingLocationType.HOME
                electricityRatePerKWh = 0.1
                rateWasLocationAware = true
                tariff = ChargingTariff(0.1, null, null, false, 21, 5, "America/Los_Angeles")
                speedSamples = listOf(
                    ChargeSpeedSample(epoch(1_791_100_000), 11.5, 22.5),
                    ChargeSpeedSample(epoch(1_791_110_000), 11.25, 50.0),
                )
            },
        )
        db.chargeSessions().insert(
            ChargeSession(car.vin, epoch(1_791_300_000), 10.0, isDCFast = true).apply {
                id = id(32)
                applyUserEdits(
                    startDate = epoch(1_791_300_000), endDate = epoch(1_791_302_400), startPercentage = 10.0, endPercentage = 81.0,
                    energyAddedKWh = 55.5, maxChargeSpeedKW = 233.0, isDCFast = true, locationType = ChargingLocationType.PUBLIC_DC,
                    electricityRatePerKWh = 0.56, actualCost = null, manualEnergyKWh = 0.0,
                )
                locationName = "Supercharger, \"Bay 4\""
            },
        )

        db.trips().insert(
            TripRecord(
                car.vin,
                EVTripDetail(112.5, 7_990.25, 1_359, 33_980, 4_120, 2_140, 26_540, 679, epoch(1_790_900_000), 9_900, 40.909, 58.909),
            ).apply {
                id = id(41)
                savedDate = epoch(1_790_910_000.5)
                endLocationName = "Mt. Rainier"
            },
        )

        db.remoteCommands().insert(
            RemoteCommandActivity(car.vin, car.displayName, "Stop charging", "Phone").apply {
                id = id(51)
                startedAt = epoch(1_791_200_000.4)
                finish(CommandActivityState.SUCCEEDED, at = epoch(1_791_200_009))
            },
        )
        db.maintenanceReminders().insert(
            MaintenanceReminder(car.vin, "Cabin filter", dueOdometer = 12_000.0).apply {
                id = id(61)
                createdAt = epoch(1_786_000_000)
            },
        )
        db.automations().insert(
            VehicleAutomation(car.vin, "Unplugged", VehicleAutomationKind.UNPLUGGED).apply {
                id = id(71)
                createdAt = epoch(1_786_000_100)
            },
        )
    }
}
