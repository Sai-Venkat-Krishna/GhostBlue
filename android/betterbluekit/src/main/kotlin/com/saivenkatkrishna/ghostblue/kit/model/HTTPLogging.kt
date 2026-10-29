package com.saivenkatkrishna.ghostblue.kit.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class HTTPRequestType(val displayName: String) {
    @SerialName("login") LOGIN("Login"),
    @SerialName("fetchVehicles") FETCH_VEHICLES("Fetch Vehicles"),
    @SerialName("fetchVehicleStatus") FETCH_VEHICLE_STATUS("Fetch Status"),
    @SerialName("sendCommand") SEND_COMMAND("Send Command"),
    @SerialName("sendMFA") SEND_MFA("Send MFA"),
    @SerialName("verifyMFA") VERIFY_MFA("Verify MFA"),
    @SerialName("fetchEVTripDetails") FETCH_EV_TRIP_DETAILS("Fetch Trip Details"),
    @SerialName("abrpPlan") ABRP_PLAN("ABRP Plan"),
    @SerialName("abrpTelemetry") ABRP_TELEMETRY("ABRP Telemetry"),
    @SerialName("abrpCarModels") ABRP_CAR_MODELS("ABRP Car Models"),
}

/** Receives a record of every request an API client makes. */
typealias HTTPLogSink = (HTTPLog) -> Unit

@Serializable
data class HTTPLog(
    @Serializable(with = Iso8601InstantSerializer::class) val timestamp: Instant,
    @Serializable(with = UppercaseUUIDSerializer::class) val accountId: UUID,
    val requestType: HTTPRequestType,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val responseStatus: Int?,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    val error: String?,
    val apiError: String? = null,
    /** Seconds. */
    val duration: Double,
    val stackTrace: String? = null,
    val vin: String? = null,
) {
    val statusText: String
        get() {
            val status = responseStatus ?: return if (error != null) "Error" else "Pending"
            return if (apiError != null) "$status (API Error)" else "$status"
        }

    val isSuccess: Boolean
        get() = responseStatus in 200..299 && error == null && apiError == null

    val formattedDuration: String get() = String.format(Locale.US, "%.2fs", duration)

    val preciseTimestamp: String
        get() = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(timestamp)
}
