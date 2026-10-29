package com.saivenkatkrishna.ghostblue.kit.api

import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLogSink
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.util.UUID

data class APIClientConfiguration(
    val region: Region,
    val brand: Brand,
    val username: String,
    val password: String,
    val pin: String,
    val accountId: UUID,
    val logSink: HTTPLogSink? = null,
    val rememberMeToken: String? = null,
    /**
     * Long-lived refresh token from `login()`, for brands that can re-establish
     * a session without the password (Hyundai and Kia Europe).
     */
    val refreshToken: String? = null,
    val redactPII: Boolean = true,
    val deviceId: String? = null,
) {
    // Never print credentials.
    override fun toString(): String =
        "APIClientConfiguration(brand=$brand, region=$region, accountId=$accountId, redactPII=$redactPII)"
}

data class MFAVerification(val rememberMeToken: String, val sid: String)

enum class MFAMethod(val rawValue: String) { EMAIL("email"), SMS("sms") }

enum class HTTPMethod { GET, POST, PUT, DELETE }

/** Talks to one brand and region's vehicle service. */
interface APIClient {
    suspend fun login(): AuthToken

    /**
     * Registers the device and returns its server-issued identifier. Called
     * before `login()` when the account has no device ID yet. Brands without a
     * registration step get a stable client-generated UUID.
     */
    suspend fun registerDevice(): String = UUID.randomUUID().toString().uppercase()

    suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle>

    /**
     * The latest status. [cached] true returns the server's last snapshot
     * (cheap, instant); false asks the car itself (slow, wakes the modem, may
     * be rate-limited). Manual refreshes and checks after a command pass false;
     * widgets and background refreshes pass true.
     */
    suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean = true): VehicleStatus

    suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken)

    /** EV trips; null where the brand or region has no trip endpoint. */
    suspend fun fetchEVTripDetails(vehicle: Vehicle, authToken: AuthToken): List<EVTripDetail>? = null

    fun supportsMFA(): Boolean = false

    suspend fun sendMFACode(xid: String, otpKey: String, method: MFAMethod): Unit =
        throw APIError("MFA not supported for this API", apiName = "APIClient")

    suspend fun verifyMFACode(xid: String, otpKey: String, code: String): MFAVerification =
        throw APIError("MFA not supported for this API", apiName = "APIClient")

    suspend fun completeMFALogin(sid: String, rmToken: String): AuthToken =
        throw APIError("MFA not supported for this API", apiName = "APIClient")
}
