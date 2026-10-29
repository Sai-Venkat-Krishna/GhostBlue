package com.saivenkatkrishna.ghostblue.kit.api

import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLog
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLogSink
import com.saivenkatkrishna.ghostblue.kit.model.HTTPRequestType
import com.saivenkatkrishna.ghostblue.kit.model.Region
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A finished HTTP exchange. */
class HTTPResponse(val statusCode: Int, val headers: Map<String, String>, val body: ByteArray)

/** A response whose body was read as a JSON object (empty when it isn't one). */
class JSONResponse(val data: ByteArray, val json: JsonObject, val response: HTTPResponse)

/**
 * Shared request execution, logging and error handling for API clients.
 * Subclasses implement [APIClient] for their brand and region.
 */
abstract class APIClientBase(
    val configuration: APIClientConfiguration,
    private val httpClient: OkHttpClient = sharedHttpClient,
) {
    val username: String get() = configuration.username
    val password: String get() = configuration.password
    val pin: String get() = configuration.pin
    val accountId: UUID get() = configuration.accountId
    val region: Region get() = configuration.region
    val brand: Brand get() = configuration.brand
    val logSink: HTTPLogSink? get() = configuration.logSink

    open val apiName: String get() = "APIClient"

    // MARK: - Requests

    suspend fun performRequest(
        url: String,
        method: HTTPMethod = HTTPMethod.GET,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        requestType: HTTPRequestType,
        vin: String? = null,
    ): HTTPResponse {
        val httpUrl = url.toHttpUrlOrNull() ?: throw APIError("Invalid URL: $url", apiName = apiName)
        val requestHeaders = LinkedHashMap(headers)
        if (requestHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            requestHeaders["Content-Type"] = "application/json"
        }
        val request = buildRequest(httpUrl, method, requestHeaders, body)
        return performLoggedRequest(
            request,
            RequestContext(requestType, method, url, requestHeaders, body?.decodeToString(), Instant.now(), vin),
        )
    }

    suspend fun performJSONRequest(
        url: String,
        method: HTTPMethod = HTTPMethod.GET,
        headers: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
        requestType: HTTPRequestType,
        vin: String? = null,
    ): JSONResponse {
        val bodyData = body?.let { Json.encodeToString(JsonObject.serializer(), it).toByteArray() }
        val response = performRequest(url, method, headers, bodyData, requestType, vin)
        return JSONResponse(response.body, parseJSONObject(response.body) ?: JsonObject(emptyMap()), response)
    }

    private fun buildRequest(
        url: HttpUrl,
        method: HTTPMethod,
        headers: Map<String, String>,
        body: ByteArray?,
    ): Request {
        val contentType = headers.entries.first { it.key.equals("Content-Type", ignoreCase = true) }.value
        val mediaType = contentType.toMediaTypeOrNull()
        val requestBody = when {
            body != null -> body.toRequestBody(mediaType)
            method == HTTPMethod.POST || method == HTTPMethod.PUT -> ByteArray(0).toRequestBody(mediaType)
            else -> null
        }
        // OkHttp only decompresses responses when it negotiated the encoding
        // itself, so the clients' own Accept-Encoding header is left out.
        val sent = Headers.Builder()
        headers.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }
            .forEach { (name, value) -> sent.addUnsafeNonAscii(name, value) }
        return Request.Builder().url(url).headers(sent.build()).method(method.name, requestBody).build()
    }

    private suspend fun performLoggedRequest(request: Request, context: RequestContext): HTTPResponse {
        BBLogger.debug(
            BBLogCategory.API,
            "[$apiName] Sending ${context.requestType.displayName} request" +
                " | URL: ${context.url} | Method: ${context.method.name}",
        )
        val response = try {
            httpClient.newCall(request).await().use { response ->
                HTTPResponse(response.code, responseHeaders(response), response.body.bytes())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw handleNetworkError(error, context)
        }
        return handleResponse(response, context)
    }

    private fun handleResponse(response: HTTPResponse, context: RequestContext): HTTPResponse {
        val responseBody = response.body.decodeToString()
        BBLogger.debug(
            BBLogCategory.API,
            "[$apiName] Response ${response.statusCode} for ${context.requestType.displayName}",
        )
        logHTTPRequest(
            context,
            responseStatus = response.statusCode,
            responseHeaders = response.headers,
            responseBody = responseBody,
            error = null,
            apiError = extractAPIError(response.body),
        )
        validateHTTPResponse(response.statusCode, responseBody)
        return response
    }

    // MARK: - Errors

    internal fun validateHTTPResponse(statusCode: Int, responseBody: String?) {
        when {
            statusCode == 401 -> throw APIError.invalidCredentials(
                "Authentication expired: ${responseBody ?: "Unknown error"}",
                apiName = apiName,
            )
            statusCode == 502 -> throw APIError.serverError(
                "Server error (502): ${responseBody ?: "Unknown error"}",
                apiName = apiName,
            )
            statusCode >= 400 -> throw APIError(
                "HTTP $statusCode: ${localizedStatusText(statusCode)}",
                code = statusCode,
                apiName = apiName,
            )
        }
    }

    private fun handleNetworkError(error: IOException, context: RequestContext): APIError {
        val description = error.message ?: error.javaClass.simpleName
        logHTTPRequest(context, null, emptyMap(), null, description, null)
        return APIError("Network error: $description", apiName = apiName)
    }

    /** The service's own error inside a response body, if it reports one. */
    internal fun extractAPIError(data: ByteArray?): String? {
        val json = data?.let(::parseJSONObject) ?: return null
        val status = json.obj("status")
        val statusCode = status?.int("errorCode")
        val statusMessage = status?.string("errorMessage")
        if (statusCode != null && statusCode != 0 && statusMessage != null) {
            return "API Error $statusCode: $statusMessage"
        }
        val errorCode = json.int("errorCode")
        if (errorCode != null && errorCode != 0) {
            return "API Error $errorCode: ${json.string("errorMessage") ?: "Unknown error"}"
        }
        return json.string("error")?.let { "API Error: $it" }
    }

    // MARK: - Logging

    private class RequestContext(
        val requestType: HTTPRequestType,
        val method: HTTPMethod,
        val url: String,
        val requestHeaders: Map<String, String>,
        val requestBody: String?,
        val startTime: Instant,
        val vin: String?,
    )

    private fun logHTTPRequest(
        context: RequestContext,
        responseStatus: Int?,
        responseHeaders: Map<String, String>,
        responseBody: String?,
        error: String?,
        apiError: String?,
    ) {
        val sink = logSink ?: return
        val redact = configuration.redactPII
        val duration = (Instant.now().toEpochMilli() - context.startTime.toEpochMilli()) / 1000.0
        sink(
            HTTPLog(
                timestamp = context.startTime,
                accountId = accountId,
                requestType = context.requestType,
                method = context.method.name,
                url = context.url,
                requestHeaders = if (redact) SensitiveDataRedactor.redactHeaders(context.requestHeaders)
                else context.requestHeaders,
                requestBody = if (redact) SensitiveDataRedactor.redact(context.requestBody) else context.requestBody,
                responseStatus = responseStatus,
                responseHeaders = if (redact) SensitiveDataRedactor.redactHeaders(responseHeaders)
                else responseHeaders,
                responseBody = if (redact) SensitiveDataRedactor.redact(responseBody) else responseBody,
                error = error,
                apiError = apiError,
                duration = duration,
                stackTrace = Thread.currentThread().stackTrace.drop(2).take(10).joinToString("\n"),
                vin = context.vin,
            ),
        )
    }

    private fun responseHeaders(response: Response): Map<String, String> =
        response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(", ") }

    companion object {
        /**
         * One client for every API client, like `URLSession.shared`: shared
         * connections, and cookies kept for the life of the process.
         */
        val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .cookieJar(InMemoryCookieJar())
                .build()
        }
    }
}

internal fun parseJSONObject(data: ByteArray): JsonObject? =
    try {
        Json.parseToJsonElement(data.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

/** Status text in the words Foundation's `HTTPURLResponse.localizedString(forStatusCode:)` uses. */
internal fun localizedStatusText(code: Int): String = when (code) {
    400 -> "bad request"
    401 -> "unauthorized"
    402 -> "payment required"
    403 -> "forbidden"
    404 -> "not found"
    405 -> "method not allowed"
    406 -> "unacceptable"
    407 -> "proxy authentication required"
    408 -> "request timed out"
    409 -> "conflict"
    410 -> "no longer exists"
    411 -> "length required"
    412 -> "precondition failed"
    413 -> "request too large"
    414 -> "requested URL too long"
    415 -> "unsupported media type"
    416 -> "requested range not satisfiable"
    417 -> "expectation failed"
    429 -> "too many requests"
    500 -> "internal server error"
    501 -> "unimplemented"
    502 -> "bad gateway"
    503 -> "service unavailable"
    504 -> "gateway timed out"
    505 -> "unsupported version"
    in 400..499 -> "client error"
    in 500..599 -> "server error"
    else -> "unknown"
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        },
    )
}

/** Keeps cookies in memory for the life of the process. */
private class InMemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now) this.cookies += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }
}
