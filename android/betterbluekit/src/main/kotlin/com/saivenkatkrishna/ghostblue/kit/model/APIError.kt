package com.saivenkatkrishna.ghostblue.kit.model

import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger

/** Every API failure. `errorType` drives how the app recovers (re-login, MFA, PIN prompt…). */
class APIError(
    override val message: String,
    val code: Int? = null,
    val apiName: String? = null,
    val errorType: ErrorType = ErrorType.GENERAL,
    val userInfo: Map<String, String>? = null,
) : Exception(message) {

    enum class ErrorType(val rawValue: String, val displayLabel: String) {
        GENERAL("general", "Error"),
        INVALID_VEHICLE_SESSION("invalidVehicleSession", "Session Expired"),
        INVALID_CREDENTIALS("invalidCredentials", "Invalid Credentials"),
        SERVER_ERROR("serverError", "Server Error"),
        INVALID_PIN("invalidPin", "Invalid PIN"),
        CONCURRENT_REQUEST("concurrentRequest", "Request In Progress"),
        FAILED_RETRY_LOGIN("failedRetryLogin", "Reauthentication Failed"),
        REQUIRES_MFA("requiresMFA", "Verification Required"),
        KIA_INVALID_REQUEST("kiaInvalidRequest", "Request Rejected"),
        REGION_NOT_SUPPORTED("regionNotSupported", "Region Not Supported"),
    }

    val failureReason: String get() = errorType.displayLabel

    override fun toString(): String = "APIError(${errorType.rawValue}: $message)"

    companion object {
        /** Creates the error and writes it to the log. */
        fun logError(
            message: String,
            code: Int? = null,
            apiName: String? = null,
            errorType: ErrorType = ErrorType.GENERAL,
            userInfo: Map<String, String>? = null,
        ): APIError {
            val error = APIError(message, code, apiName, errorType, userInfo)
            var logMessage = "${apiName ?: "Unknown"}: $message"
            if (code != null) logMessage += " | Status Code: $code"
            if (errorType != ErrorType.GENERAL) logMessage += " | Error Type: ${errorType.rawValue}"
            if (userInfo != null) logMessage += " | User Info: $userInfo"
            BBLogger.error(BBLogCategory.API, logMessage)
            return error
        }

        fun requiresMFA(
            xid: String,
            otpKey: String? = null,
            hasEmail: Boolean = false,
            hasPhone: Boolean = false,
            email: String? = null,
            phone: String? = null,
            rmTokenExpired: Boolean = false,
            apiName: String? = null,
        ): APIError {
            val info = linkedMapOf("xid" to xid)
            if (otpKey != null) info["otpKey"] = otpKey
            info["hasEmail"] = hasEmail.toString()
            info["hasPhone"] = hasPhone.toString()
            if (email != null) info["email"] = email
            if (phone != null) info["phone"] = phone
            if (rmTokenExpired) info["rmTokenExpired"] = "true"
            val message = if (rmTokenExpired) "Session expired - verification required"
            else "Multi-Factor Authentication Required"
            return logError(message, apiName = apiName, errorType = ErrorType.REQUIRES_MFA, userInfo = info)
        }

        fun invalidVehicleSession(
            message: String = "Invalid vehicle for current session",
            apiName: String? = null,
        ) = logError(message, 1005, apiName, ErrorType.INVALID_VEHICLE_SESSION)

        fun invalidCredentials(message: String = "Invalid username or password", apiName: String? = null) =
            logError(message, 401, apiName, ErrorType.INVALID_CREDENTIALS)

        fun serverError(message: String = "Server temporarily unavailable", apiName: String? = null) =
            logError(message, 502, apiName, ErrorType.SERVER_ERROR)

        fun invalidPin(message: String, apiName: String? = null) =
            logError(message, apiName = apiName, errorType = ErrorType.INVALID_PIN)

        fun concurrentRequest(
            message: String = "Another request is already in progress. Please wait and try again.",
            apiName: String? = null,
        ) = logError(message, 502, apiName, ErrorType.CONCURRENT_REQUEST)

        fun failedRetryLogin(message: String = "Failed to reauthenticate", apiName: String? = null) =
            logError(message, 502, apiName, ErrorType.FAILED_RETRY_LOGIN)

        fun kiaInvalidRequest(message: String = "Invalid request", apiName: String? = null) =
            logError(message, 502, apiName, ErrorType.KIA_INVALID_REQUEST)

        fun regionNotSupported(message: String = "This region is not yet supported", apiName: String? = null) =
            logError(message, apiName = apiName, errorType = ErrorType.REGION_NOT_SUPPORTED)
    }
}
