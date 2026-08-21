package com.noctra.scout

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class CheckResult {
    /** The API answered: [taken] is false when the username is free. */
    data class Ok(val taken: Boolean) : CheckResult()

    /** Rate limited; wait [retryAfterMs] before the next attempt. */
    data class RateLimited(val retryAfterMs: Long) : CheckResult()

    /** Discord rejected the name itself (reserved word, blocked term). Skip it. */
    data class Invalid(val reason: String) : CheckResult()

    /** Transient or fatal failure. [fatal] means stop the run and tell the user. */
    data class Failure(val message: String, val fatal: Boolean) : CheckResult()
}

/**
 * Thin wrapper over Discord's username availability endpoint.
 *
 * Without a token it uses the unauthenticated endpoint the signup form calls; with a token
 * it uses the authenticated one. Either way this only asks "is this name free?" — it never
 * claims a name. 429s are always honoured.
 */
class DiscordClient(private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val url = if (token.isBlank()) UNAUTHED_URL else AUTHED_URL

    fun check(username: String): CheckResult {
        val body = JSONObject().put("username", username).toString().toRequestBody(JSON)
        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://discord.com")
            .header("Referer", "https://discord.com/register")
            .header("X-Discord-Locale", "en-US")
            .header("X-Super-Properties", superProperties)
        if (token.isNotBlank()) builder.header("Authorization", token)

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> parseOk(text)
                    429 -> CheckResult.RateLimited(parseRetryAfter(text, resp.header("Retry-After")))
                    400 -> parseBadRequest(text)
                    401 -> CheckResult.Failure("401 unauthorized — the saved token is invalid", true)
                    403 -> CheckResult.Failure("403 forbidden — request blocked by Discord", true)
                    in 500..599 -> CheckResult.Failure("Discord returned ${resp.code}", false)
                    else -> CheckResult.Failure("HTTP ${resp.code}", false)
                }
            }
        } catch (e: Exception) {
            CheckResult.Failure(e.javaClass.simpleName + ": " + (e.message ?: "network error"), false)
        }
    }

    private fun parseOk(text: String): CheckResult = try {
        val json = JSONObject(text)
        if (json.has("taken")) {
            CheckResult.Ok(json.getBoolean("taken"))
        } else {
            CheckResult.Failure("Unexpected response shape", false)
        }
    } catch (e: Exception) {
        CheckResult.Failure("Malformed response", false)
    }

    private fun parseBadRequest(text: String): CheckResult = try {
        val json = JSONObject(text)
        val reason = when {
            json.has("username") -> json.optJSONArray("username")?.optString(0) ?: "rejected"
            json.has("message") -> json.optString("message")
            else -> "rejected"
        }
        CheckResult.Invalid(reason)
    } catch (e: Exception) {
        CheckResult.Invalid("rejected")
    }

    private fun parseRetryAfter(text: String, header: String?): Long {
        val fromBody = try {
            JSONObject(text).optDouble("retry_after", -1.0)
        } catch (e: Exception) {
            -1.0
        }
        val seconds = when {
            fromBody > 0 -> fromBody
            header != null -> header.toDoubleOrNull() ?: 5.0
            else -> 5.0
        }
        return (seconds * 1000).toLong().coerceIn(1_000L, 15 * 60_000L)
    }

    companion object {
        private const val UNAUTHED_URL =
            "https://discord.com/api/v9/unique-username/username-attempt-unauthed"
        private const val AUTHED_URL =
            "https://discord.com/api/v9/unique-username/username-attempt"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val JSON = "application/json".toMediaType()

        /** The client-info blob Discord's API expects alongside every request. */
        private val superProperties: String by lazy {
            val props = JSONObject()
                .put("os", "Windows")
                .put("browser", "Chrome")
                .put("device", "")
                .put("system_locale", "en-US")
                .put("browser_user_agent", USER_AGENT)
                .put("browser_version", "126.0.0.0")
                .put("os_version", "10")
                .put("referrer", "")
                .put("referring_domain", "")
                .put("referrer_current", "")
                .put("referring_domain_current", "")
                .put("release_channel", "stable")
                .put("client_build_number", 300000)
                .put("client_event_source", JSONObject.NULL)
            Base64.encodeToString(props.toString().toByteArray(), Base64.NO_WRAP)
        }
    }
}
