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

    /**
     * Rate limited; wait [retryAfterMs] before the next attempt. [shared] marks Discord's
     * `x-ratelimit-scope: shared` — a global pool contended by everyone using the public
     * endpoint, which no amount of pacing can win.
     */
    data class RateLimited(val retryAfterMs: Long, val shared: Boolean = false) : CheckResult()

    /** Discord rejected the name itself (reserved word, blocked term). Skip it. */
    data class Invalid(val reason: String) : CheckResult()

    /**
     * Transient or fatal failure. [fatal] means stop the run and tell the user.
     * [tryNext] marks a failure that says nothing about the other candidates — a missing path
     * or a refused credential — so probing should move on rather than give up.
     */
    data class Failure(
        val message: String,
        val fatal: Boolean,
        val tryNext: Boolean = false
    ) : CheckResult()
}

/** First token of a failure message, for a compact "what did we try" list. */
private fun CheckResult.Failure.shortReason(): String =
    message.substringBefore(" —").substringBefore(" on ").trim().ifEmpty { "failed" }

/** One candidate availability endpoint. */
private data class Endpoint(val label: String, val url: String, val needsToken: Boolean)

/**
 * Asks Discord whether a username is free. Nothing here can claim or change a name —
 * the only request this class makes is the availability check below.
 *
 * Discord has moved this endpoint before, so rather than hard-coding one path the client
 * probes the known candidates on the first check and locks onto whichever answers.
 */
class DiscordClient(private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val candidates: List<Endpoint> = buildList {
        if (token.isNotBlank()) {
            add(Endpoint("pomelo-attempt", "$API/users/@me/pomelo-attempt", true))
            add(Endpoint("username-attempt", "$API/unique-username/username-attempt", true))
        }
        add(Endpoint("username-attempt-unauthed", "$API/unique-username/username-attempt-unauthed", false))
    }

    @Volatile
    private var resolved: Endpoint? = null

    /** Label of the endpoint in use, once one has answered. */
    val activeEndpoint: String
        get() = resolved?.label.orEmpty()

    fun check(username: String): CheckResult {
        resolved?.let { return request(it, username) }

        // First call of the run: find an endpoint that answers. A missing path or a refused
        // token says nothing about the remaining candidates, so keep going — a bad token must
        // never dead-end a run when the public check still works.
        val tried = ArrayList<String>(candidates.size)
        var last: CheckResult.Failure? = null
        for (candidate in candidates) {
            val result = request(candidate, username)
            if (result is CheckResult.Failure && result.tryNext) {
                tried.add("${candidate.label} (${result.shortReason()})")
                last = result
                continue
            }
            resolved = candidate
            if (tried.isNotEmpty() && !candidate.needsToken && token.isNotBlank()) {
                skippedAuth = true
            }
            return result
        }
        return CheckResult.Failure(
            "No endpoint answered — tried ${tried.joinToString(", ")}. " +
                (last?.message ?: "Discord changed the API."),
            true
        )
    }

    /** True when a token was set but rejected, and the public endpoint is being used instead. */
    @Volatile
    var skippedAuth = false
        private set

    private fun request(endpoint: Endpoint, username: String): CheckResult {
        val body = JSONObject().put("username", username).toString().toRequestBody(JSON)
        val builder = Request.Builder()
            .url(endpoint.url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://discord.com")
            .header("Referer", "https://discord.com/channels/@me")
            .header("X-Discord-Locale", "en-US")
            .header("X-Super-Properties", superProperties)
        if (endpoint.needsToken) builder.header("Authorization", token)

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> parseOk(text)
                    429 -> CheckResult.RateLimited(
                        parseRetryAfter(text, resp.header("Retry-After")),
                        shared = resp.header("x-ratelimit-scope")
                            .equals("shared", ignoreCase = true) && !endpoint.needsToken
                    )
                    400 -> parseBadRequest(text)
                    401 -> CheckResult.Failure(
                        "401 on ${endpoint.label} — token rejected. Paste it without the " +
                            "surrounding quotes, and re-copy it if you changed the password since.",
                        fatal = true, tryNext = true
                    )
                    403 -> CheckResult.Failure(
                        "403 on ${endpoint.label} — Discord refused this request " +
                            "(it blocks datacenter and VPN addresses; try mobile data or home Wi-Fi)",
                        fatal = true, tryNext = true
                    )
                    404, 405 -> CheckResult.Failure(
                        "HTTP ${resp.code} on ${endpoint.label}", fatal = true, tryNext = true
                    )
                    in 500..599 -> CheckResult.Failure("Discord returned ${resp.code}", false)
                    else -> CheckResult.Failure("HTTP ${resp.code} on ${endpoint.label}", false)
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
        private const val API = "https://discord.com/api/v9"

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
