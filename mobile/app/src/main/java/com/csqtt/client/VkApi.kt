package com.csqtt.client

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal sealed class VkApiResult<out T> {
    data class Ok<T>(val value: T) : VkApiResult<T>()
    data class ApiError(val code: Int, val message: String) : VkApiResult<Nothing>()
    data class Failed(val reason: String) : VkApiResult<Nothing>()
}

internal object VkApi {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    private val TOKEN_INVALID_CODES = setOf(4, 5, 27, 28)

    fun isTokenInvalid(error: VkApiResult.ApiError): Boolean = error.code in TOKEN_INVALID_CODES

    data class StartedCall(
        val callId: String,
        val hash: String,
        val joinLink: String,
    )

    fun startCall(token: String): VkApiResult<StartedCall> = request("calls.start", token, emptyMap()) { json ->
        val response = json.optJSONObject("response")
        val callId = response?.optString("call_id").orEmpty()
        val joinLink = response?.optString("join_link").orEmpty()
        val hash = response?.optString("ok_join_link")
            ?.takeIf { it.isNotBlank() }
            ?: joinLink.substringAfterLast('/').trimEnd('/')
        check(callId.isNotBlank() && hash.isNotBlank()) { "пустой call_id/hash в ответе calls.start" }
        StartedCall(callId = callId, hash = hash, joinLink = joinLink)
    }

    fun forceFinishCall(token: String, callId: String): VkApiResult<Int> = request(
        "calls.forceFinish",
        token,
        mapOf("call_id" to callId),
    ) { json ->
        json.optInt("response", 0)
    }

    private fun <T> request(
        method: String,
        token: String,
        params: Map<String, String>,
        parse: (JSONObject) -> T,
    ): VkApiResult<T> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(CsqttConstants.VkAutoHash.API_METHOD_BASE_URL + method).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = buildString {
                params.forEach { (key, value) ->
                    append(URLEncoder.encode(key, "UTF-8"))
                    append('=')
                    append(URLEncoder.encode(value, "UTF-8"))
                    append('&')
                }
                append("v=")
                append(CsqttConstants.VkAutoHash.API_VERSION)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            val json = JSONObject(text)

            val errorObj = json.optJSONObject("error")
            if (errorObj != null) {
                VkApiResult.ApiError(
                    code = errorObj.optInt("error_code", 1),
                    message = errorObj.optString("error_msg"),
                )
            } else {
                VkApiResult.Ok(parse(json))
            }
        } catch (e: Exception) {
            VkApiResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
