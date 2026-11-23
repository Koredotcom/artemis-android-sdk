package com.artemis.uisdk.sample

import artemis.socket.ArtemisSocketConfiguration
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Mints a fresh hosted bootstrap token from `POST /api/v1/sdk/customer-sessions` every time
 * the SDK asks for one.
 *
 * Bootstrap tokens are single use. The SDK calls [getToken] before every `/api/v1/sdk/init`,
 * including retries after a network drop, "start new chat" and SDK-token loss. Because each
 * call mints a new token, a token consumed by a lost `/sdk/init` response is never replayed.
 *
 * The SDK calls this on its own background thread, so the blocking HTTP call is safe here.
 *
 * SECURITY: `X-SDK-Channel-Secret` is a server credential. A production app must never ship
 * it. The app should call its own backend, which authenticates the user and calls
 * customer-sessions. This sample calls it directly only to show the integration end to end.
 */
class CustomerSessionBootstrapProvider(
    endpoint: String,
    private val channelSecret: String,
    private val tenantId: String,
    private val projectId: String,
    private val channelId: String?,
    private val channelName: String?,
    private val verifiedUserId: String,
    private val customAttributes: JsonObject?,
    private val httpClient: OkHttpClient = defaultClient,
    private val log: (String) -> Unit = { if (BuildConfig.DEBUG) Log.d(TAG, it) },
) : ArtemisSocketConfiguration.BootstrapTokenProvider {

    private val url = httpBase(endpoint) + "/api/v1/sdk/customer-sessions"

    init {
        require(channelSecret.isNotBlank()) { "Channel secret is required" }
        require(tenantId.isNotBlank()) { "Tenant ID is required" }
        require(projectId.isNotBlank()) { "Project ID is required" }
        require(!channelId.isNullOrBlank() || !channelName.isNullOrBlank()) { "Channel ID or channel name is required" }
        require(verifiedUserId.isNotBlank()) { "Verified user ID is required" }
    }

    override fun getToken(): String {
        val body = JsonObject().apply {
            addProperty("tenantId", tenantId)
            addProperty("projectId", projectId)
            if (!channelId.isNullOrBlank()) addProperty("channelId", channelId)
            else addProperty("channelName", channelName)
            addProperty("verifiedUserId", verifiedUserId)
            if (customAttributes != null) add("customAttributes", customAttributes.deepCopy())
        }
        val request = Request.Builder()
            .url(url)
            .header("X-SDK-Channel-Secret", channelSecret)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val call = callCount.incrementAndGet()
        // Request/response logging for the sample. The channel secret and any token are always redacted.
        log("[$call] --> POST $url")
        log("[$call]     X-SDK-Channel-Secret: ${maskSecret(channelSecret)}")
        log("[$call]     body: $body")
        val startedAt = System.nanoTime()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (error: IOException) {
            log("[$call] <-- FAILED after ${elapsedMs(startedAt)}ms: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
        response.use {
            val text = it.body?.string().orEmpty()
            val payload = try { JsonParser.parseString(text) } catch (error: RuntimeException) { null }
            log("[$call] <-- HTTP ${it.code} in ${elapsedMs(startedAt)}ms")
            log("[$call]     body: ${if (payload != null) redact(payload) else "<non-JSON, ${text.length} chars>"}")
            // Never include the response body or headers in errors: they may carry the token.
            if (!it.isSuccessful) throw IOException("Customer session request failed (${it.code})")
            if (payload == null) throw IOException("Customer session response was not JSON")
            val token = extractToken(payload) ?: throw IOException("Customer session response did not contain a bootstrap token")
            log("Minted bootstrap token #${mintCount.incrementAndGet()}")
            return token
        }
    }

    private val mintCount = AtomicInteger()
    private val callCount = AtomicInteger()

    private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

    internal companion object {
        private val SENSITIVE_KEY = Regex("token|secret|ticket|envelope|password", RegexOption.IGNORE_CASE)

        /** Shows only the secret's prefix, e.g. `sk_…(51 chars)`. */
        fun maskSecret(secret: String): String = "${secret.substringBefore('_', "").let { if (it.isEmpty()) "" else "${it}_" }}…(${secret.length} chars)"

        /** Copies the JSON with every token-, secret- or ticket-like value replaced by its length. */
        fun redact(element: JsonElement): JsonElement = when {
            element.isJsonObject -> JsonObject().also { copy ->
                for ((key, value) in element.asJsonObject.entrySet()) {
                    copy.add(key, if (SENSITIVE_KEY.containsMatchIn(key) && !value.isJsonNull)
                        com.google.gson.JsonPrimitive("<redacted ${if (value.isJsonPrimitive) value.asString.length else 0} chars>")
                    else redact(value))
                }
            }
            element.isJsonArray -> com.google.gson.JsonArray().also { copy -> element.asJsonArray.forEach { copy.add(redact(it)) } }
            else -> element
        }

        private const val TAG = "ArtemisSampleSession"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** The Runtime endpoint may be configured as ws(s); the REST call needs http(s). */
        fun httpBase(endpoint: String): String {
            val trimmed = endpoint.trim().trimEnd('/')
            return when {
                trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
                trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
                else -> trimmed
            }
        }

        /** Accepts `bootstrapToken` or `token`, at the top level or under `data`. */
        fun extractToken(payload: JsonElement): String? {
            if (!payload.isJsonObject) return null
            val root = payload.asJsonObject
            val candidates = listOfNotNull(root, root.objectOrNull("data"))
            for (candidate in candidates) for (key in arrayOf("bootstrapToken", "token")) {
                val value = candidate.get(key)
                if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    val token = value.asString.trim()
                    if (token.isNotEmpty()) return token
                }
            }
            return null
        }

        private fun JsonObject.objectOrNull(key: String): JsonObject? =
            get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    }
}
