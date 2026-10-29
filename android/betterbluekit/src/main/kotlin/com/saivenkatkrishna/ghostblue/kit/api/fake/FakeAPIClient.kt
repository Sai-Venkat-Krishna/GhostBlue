package com.saivenkatkrishna.ghostblue.kit.api.fake

import com.saivenkatkrishna.ghostblue.kit.api.APIClient
import com.saivenkatkrishna.ghostblue.kit.api.APIClientConfiguration
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.util.UUID

/** The test client: vehicles come from a [FakeVehicleProvider], with switchable failures. */
class FakeAPIClient(
    configuration: APIClientConfiguration,
    private val vehicleProvider: FakeVehicleProvider,
    private val clock: () -> Instant = Instant::now,
) : APIClient {
    private val username = configuration.username
    private val accountId = configuration.accountId

    init {
        BBLogger.info(BBLogCategory.FAKE_API, "Initialized for user '$username' with custom vehicle provider")
    }

    override suspend fun login(): AuthToken {
        if (vehicleProvider.shouldFailCredentialValidation(accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating credential validation failure")
            val message = vehicleProvider.customCredentialErrorMessage(accountId)
            throw APIError.invalidCredentials(message, apiName = API_NAME)
        }
        if (vehicleProvider.shouldFailLogin(accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating login failure")
            throw APIError.logError("Debug: Simulated login failure", code = 500, apiName = API_NAME)
        }
        BBLogger.info(BBLogCategory.FAKE_API, "Login successful for user '$username'")
        return AuthToken(
            accessToken = "fake_access_token_${UUID.randomUUID().toString().uppercase()}",
            refreshToken = "fake_refresh_token_${UUID.randomUUID().toString().uppercase()}",
            expiresAt = clock().plusSeconds(3600),
        )
    }

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> {
        if (vehicleProvider.shouldFailVehicleFetch(accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating vehicle fetch failure")
            throw APIError.logError("Debug: Simulated vehicle fetch failure", code = 500, apiName = API_NAME)
        }
        val vehicles = vehicleProvider.fakeVehicles(username, accountId)
        BBLogger.info(
            BBLogCategory.FAKE_API,
            "Fetched ${vehicles.size} fake vehicles for user '$username': [${vehicles.joinToString(", ") { it.vin }}]",
        )
        return vehicles
    }

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        if (vehicleProvider.shouldFailStatusFetch(vehicle.vin, accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating status fetch failure")
            throw APIError.logError("Debug: Simulated status fetch failure", code = 500, apiName = API_NAME)
        }
        val status = vehicleProvider.vehicleStatus(vehicle.vin, accountId)
        BBLogger.info(BBLogCategory.FAKE_API, "Fetched vehicle status for fake vehicle '${vehicle.vin}' (cached: $cached)")
        return status
    }

    /**
     * Seven days of synthetic trips (a daily commute and a weekend road trip)
     * for electric test cars, so the Trips screen has something to show.
     */
    override suspend fun fetchEVTripDetails(vehicle: Vehicle, authToken: AuthToken): List<EVTripDetail>? {
        if (!vehicle.fuelType.hasElectricCapability) return null
        val now = clock()
        val day = 24L * 60 * 60
        return tripTemplate.mapIndexed { offset, t ->
            val remainder = tripTemplate.drop(offset + 1).sumOf { it.distance }
            val avg = if (t.durationMin > 0) t.distance / (t.durationMin / 60.0) else 0.0
            EVTripDetail(
                distance = t.distance,
                odometer = 12_345.0 + remainder,
                accessoriesEnergy = (t.totalWh * 0.04).toInt(),
                totalEnergyUsed = t.totalWh,
                regenEnergy = t.regenWh,
                climateEnergy = t.climateWh,
                drivetrainEnergy = t.drivetrainWh,
                batteryCareEnergy = (t.totalWh * 0.02).toInt(),
                startDate = now.minusSeconds((offset + 1) * day),
                durationSeconds = t.durationMin * 60,
                avgSpeed = avg,
                maxSpeed = avg + 18.0,
            )
        }
    }

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        if (vehicleProvider.shouldFailPinValidation(vehicle.vin, accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating PIN validation failure")
            throw APIError.invalidPin(vehicleProvider.customPinErrorMessage(vehicle.vin, accountId), apiName = API_NAME)
        }
        if (vehicleProvider.shouldFailCommand(command, vehicle.vin, accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating ${command.name} failure")
            throw APIError.logError("Debug: Simulated ${command.name} failure", code = 500, apiName = API_NAME)
        }
        vehicleProvider.executeCommand(command, vehicle.vin, accountId)
        BBLogger.info(BBLogCategory.FAKE_API, "Command completed successfully for fake vehicle '${vehicle.vin}'")
    }

    private data class TripTemplate(
        val distance: Double,
        val durationMin: Int,
        val totalWh: Int,
        val regenWh: Int,
        val climateWh: Int,
        val drivetrainWh: Int,
    )

    private companion object {
        const val API_NAME = "FakeAPI"

        val tripTemplate = listOf(
            TripTemplate(28.4, 45, 8_950, 1_140, 720, 6_950),
            TripTemplate(12.1, 24, 3_820, 380, 240, 3_010),
            TripTemplate(46.7, 72, 14_120, 2_010, 980, 10_640),
            TripTemplate(8.3, 18, 2_710, 220, 410, 1_950),
            TripTemplate(35.6, 58, 11_330, 1_540, 880, 8_410),
            TripTemplate(19.2, 31, 6_120, 690, 410, 4_710),
            TripTemplate(112.5, 165, 33_980, 4_120, 2_140, 26_540),
        )
    }
}

/** Where the fake client's vehicles live, and which failures to simulate. */
interface FakeVehicleProvider {
    suspend fun fakeVehicles(username: String, accountId: UUID): List<Vehicle>
    suspend fun vehicleStatus(vin: String, accountId: UUID): VehicleStatus
    suspend fun executeCommand(command: VehicleCommand, vin: String, accountId: UUID)

    suspend fun shouldFailCredentialValidation(accountId: UUID): Boolean
    suspend fun shouldFailLogin(accountId: UUID): Boolean
    suspend fun shouldFailVehicleFetch(accountId: UUID): Boolean
    suspend fun shouldFailStatusFetch(vin: String, accountId: UUID): Boolean
    suspend fun shouldFailPinValidation(vin: String, accountId: UUID): Boolean
    suspend fun shouldFailCommand(command: VehicleCommand, vin: String, accountId: UUID): Boolean
    suspend fun customCredentialErrorMessage(accountId: UUID): String
    suspend fun customPinErrorMessage(vin: String, accountId: UUID): String
}
