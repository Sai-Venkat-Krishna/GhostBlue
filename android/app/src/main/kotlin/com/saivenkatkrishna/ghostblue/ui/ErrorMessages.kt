package com.saivenkatkrishna.ghostblue.ui

import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.APIError.ErrorType

/** A failure the screens show: a short line, and the details behind it. */
data class UserError(val action: String, val summary: String, val details: String, val requiresMFA: Boolean = false)

/** The short line iOS shows for a failed refresh (`MainView`'s error messages). */
fun refreshErrorMessage(error: Throwable): String {
    val apiError = error as? APIError ?: return "Unable to refresh - check connection and try again"
    return when (apiError.errorType) {
        ErrorType.INVALID_CREDENTIALS -> "Login expired - please check account settings"
        ErrorType.INVALID_PIN -> "PIN validation failed - check account settings"
        ErrorType.INVALID_VEHICLE_SESSION -> "Vehicle session expired - trying to reconnect"
        ErrorType.SERVER_ERROR -> "Server temporarily unavailable - try again later"
        ErrorType.CONCURRENT_REQUEST -> "Another request in progress - please wait and try again"
        ErrorType.FAILED_RETRY_LOGIN -> "Unable to reconnect - check account settings"
        ErrorType.REQUIRES_MFA -> "${apiError.message} - Tap to verify"
        ErrorType.KIA_INVALID_REQUEST -> apiError.message
        ErrorType.REGION_NOT_SUPPORTED -> "This region is not yet supported"
        ErrorType.GENERAL -> generalMessage(apiError)
    }
}

private fun generalMessage(error: APIError): String {
    val message = error.message.lowercase()
    val code = error.code
    return when {
        "timeout" in message || "timed out" in message -> "Vehicle not responding - try again later"
        "network" in message || "internet" in message -> "Network connection issue - check your internet"
        code == 404 -> "Vehicle not found on server"
        code == 500 || code == 502 || code == 503 -> "Server temporarily unavailable - try again later"
        code != null && code >= 400 -> "Server error ($code) - try again later"
        else -> "Unable to refresh - check connection and try again"
    }
}

/** The short line for a failed command or sign-in: the API's own message, which says what went wrong. */
fun commandErrorMessage(error: Throwable): String = when (error) {
    is APIError -> error.message
    else -> error.message ?: error.javaClass.simpleName
}

fun userError(action: String, error: Throwable): UserError {
    val apiError = error as? APIError
    val details = buildString {
        append(apiError?.failureReason ?: error.javaClass.simpleName)
        apiError?.code?.let { append(" · HTTP $it") }
        apiError?.apiName?.let { append(" · $it") }
        append('\n')
        append(error.message ?: error.toString())
    }
    return UserError(
        action = action,
        summary = commandErrorMessage(error),
        details = details,
        requiresMFA = apiError?.errorType == ErrorType.REQUIRES_MFA,
    )
}
