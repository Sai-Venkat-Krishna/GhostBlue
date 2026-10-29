package com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa

import com.saivenkatkrishna.ghostblue.kit.api.APIClient
import com.saivenkatkrishna.ghostblue.kit.api.APIClientBase
import com.saivenkatkrishna.ghostblue.kit.api.APIClientConfiguration
import com.saivenkatkrishna.ghostblue.kit.api.HTTPMethod
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.HTTPRequestType
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** Hyundai USA (Bluelink). */
class HyundaiUSAAPIClient(
    configuration: APIClientConfiguration,
    httpClient: OkHttpClient = APIClientBase.sharedHttpClient,
    /** The phone's time zone: trip start times and `payloadGenerated` are local time. */
    internal val zoneId: ZoneId = ZoneId.systemDefault(),
    /** Overrides the service address; tests point it at a local server. */
    baseUrlOverride: String? = null,
) : APIClientBase(configuration, httpClient), APIClient {

    private val clientId = "m66129Bb-em93-SPAHYN-bZ91-am4540zp19920"
    private val clientSecret = "v558o935-6nne-423i-baa8"

    internal val baseURL: String = baseUrlOverride ?: region.apiBaseUrl(Brand.HYUNDAI)
    internal val apiHost = "api.telematics.hyundaiusa.com"

    override val apiName: String get() = "HyundaiUSA"

    // MARK: - Headers

    internal fun headers(): MutableMap<String, String> = linkedMapOf(
        "client_id" to clientId,
        "clientSecret" to clientSecret,
        "Host" to apiHost,
        "User-Agent" to "okhttp/3.12.0",
        "Content-Type" to "application/json",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Accept-Language" to "en-US,en;q=0.9",
        "Connection" to "Keep-Alive",
    )

    internal fun authorizedHeaders(
        authToken: AuthToken,
        vehicle: Vehicle? = null,
        refresh: Boolean = false,
    ): MutableMap<String, String> = headers().apply {
        put("accessToken", authToken.accessToken)
        put("language", "0")
        put("to", "ISS")
        put("encryptFlag", "false")
        put("from", "SPA")
        put("offset", "-5")
        put("brandIndicator", "H")
        put("origin", "https://$apiHost")
        put("referer", "https://$apiHost/login")
        put("username", username)
        put("blueLinkServicePin", pin)
        // "true" makes the service poll the car's modem instead of returning
        // its last snapshot, as MyHyundai's pull-to-refresh does.
        put("refresh", if (refresh) "true" else "false")
        if (vehicle != null) {
            put("gen", vehicle.generation.toString())
            put("registrationId", vehicle.regId)
            put("vin", vehicle.vin)
            put("APPCLOUD-VIN", vehicle.vin)
        }
        put("payloadGenerated", payloadFormatter.format(LocalDateTime.now(zoneId)))
        put("includeNonConnectedVehicles", "Y")
    }

    // MARK: - APIClient

    override suspend fun login(): AuthToken {
        BBLogger.info(BBLogCategory.AUTH, "HyundaiUSA: Attempting login for $username")
        val response = performJSONRequest(
            url = "$baseURL/v2/ac/oauth/token",
            method = HTTPMethod.POST,
            headers = headers(),
            body = buildJsonObject {
                put("username", username)
                put("password", password)
            },
            requestType = HTTPRequestType.LOGIN,
        )
        return parseLoginResponse(response.data)
    }

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> {
        val response = performJSONRequest(
            url = "$baseURL/ac/v2/enrollment/details/$username",
            method = HTTPMethod.GET,
            headers = authorizedHeaders(authToken),
            requestType = HTTPRequestType.FETCH_VEHICLES,
        )
        return parseVehiclesResponse(response.data)
    }

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        val response = performJSONRequest(
            url = "$baseURL/ac/v2/rcs/rvs/vehicleStatus",
            method = HTTPMethod.GET,
            headers = authorizedHeaders(authToken, vehicle, refresh = !cached),
            requestType = HTTPRequestType.FETCH_VEHICLE_STATUS,
            vin = vehicle.vin,
        )
        return parseVehicleStatusResponse(response.data, vehicle)
    }

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        val response = performJSONRequest(
            url = commandURL(command, vehicle),
            method = HTTPMethod.POST,
            headers = authorizedHeaders(authToken, vehicle),
            body = commandBody(command, vehicle),
            requestType = HTTPRequestType.SEND_COMMAND,
            vin = vehicle.vin,
        )
        parseCommandResponse(response.data)
    }

    override suspend fun fetchEVTripDetails(vehicle: Vehicle, authToken: AuthToken): List<EVTripDetail> {
        val tripHeaders = authorizedHeaders(authToken, vehicle).apply {
            put("userId", username)
            put("access_token", authToken.accessToken)
        }
        val response = performJSONRequest(
            url = "$baseURL/ac/v2/ts/alerts/maintenance/evTripDetails",
            method = HTTPMethod.GET,
            headers = tripHeaders,
            requestType = HTTPRequestType.FETCH_EV_TRIP_DETAILS,
            vin = vehicle.vin,
        )
        return parseEVTripDetailsResponse(response.data)
    }

    private companion object {
        val payloadFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US)
    }
}
