package com.artemis.uisdk.sample

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CustomerSessionBootstrapProviderTest {
    private val requests = mutableListOf<Request>()

    private fun client(code: Int, vararg bodies: String): OkHttpClient {
        var index = 0
        return OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val body = bodies[minOf(index++, bodies.size - 1)]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    private fun provider(http: OkHttpClient, channelId: String? = "ch", channelName: String? = null) =
        CustomerSessionBootstrapProvider(
            endpoint = "wss://runtime.example.com/",
            channelSecret = "secret",
            tenantId = "t",
            projectId = "p",
            channelId = channelId,
            channelName = channelName,
            verifiedUserId = "user-1",
            customAttributes = JsonObject().apply { addProperty("plan", "gold") },
            httpClient = http,
            log = {},
        )

    private fun bodyOf(request: Request): JsonObject =
        JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject

    @Test fun sendsContractAndMintsFreshTokenPerCall() {
        val provider = provider(client(200, """{"bootstrapToken":"bt-1"}""", """{"data":{"token":"bt-2"}}"""))
        assertEquals("bt-1", provider.getToken())
        assertEquals("bt-2", provider.getToken())

        assertEquals(2, requests.size)
        val request = requests.first()
        assertEquals("https://runtime.example.com/api/v1/sdk/customer-sessions", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("secret", request.header("X-SDK-Channel-Secret"))
        val body = bodyOf(request)
        assertEquals("t", body["tenantId"].asString)
        assertEquals("p", body["projectId"].asString)
        assertEquals("ch", body["channelId"].asString)
        assertFalse(body.has("channelName"))
        assertEquals("user-1", body["verifiedUserId"].asString)
        assertEquals("gold", body["customAttributes"].asJsonObject["plan"].asString)
    }

    @Test fun sendsChannelNameWhenNoChannelId() {
        provider(client(200, """{"token":"bt"}"""), channelId = null, channelName = "Support").getToken()
        val body = bodyOf(requests.single())
        assertEquals("Support", body["channelName"].asString)
        assertFalse(body.has("channelId"))
    }

    @Test(expected = IOException::class) fun httpFailureThrows() {
        provider(client(401, """{"error":"bad secret"}""")).getToken()
    }

    @Test(expected = IOException::class) fun missingTokenThrows() {
        provider(client(200, """{"ok":true}""")).getToken()
    }

    @Test(expected = IllegalArgumentException::class) fun requiresChannel() {
        provider(client(200, "{}"), channelId = null, channelName = null)
    }

    @Test fun logsRequestAndResponseWithoutSecretOrToken() {
        val logs = mutableListOf<String>()
        CustomerSessionBootstrapProvider(
            endpoint = "https://runtime.example.com", channelSecret = "sk_super_secret_value", tenantId = "t", projectId = "p",
            channelId = "ch", channelName = null, verifiedUserId = "user-1", customAttributes = null,
            httpClient = client(200, """{"bootstrapToken":"bt-secret-token","tokenEnvelope":"env","expiresIn":300,"channelId":"ch"}"""),
            log = { logs += it },
        ).getToken()
        val all = logs.joinToString("\n")
        assertTrue(all.contains("--> POST https://runtime.example.com/api/v1/sdk/customer-sessions"))
        assertTrue(all.contains("\"verifiedUserId\":\"user-1\""))
        assertTrue(all.contains("<-- HTTP 200"))
        assertTrue(all.contains("\"expiresIn\":300"))
        assertTrue(all.contains("sk_…(21 chars)"))
        assertFalse(all.contains("super_secret"))
        assertFalse(all.contains("bt-secret-token"))
        assertFalse(all.contains("\"env\""))
    }

    @Test fun failedResponseIsLoggedRedacted() {
        val logs = mutableListOf<String>()
        try {
            CustomerSessionBootstrapProvider("https://runtime.example.com", "sk_x", "t", "p", "ch", null, "u", null,
                client(404, """{"success":false,"error":{"code":"NOT_FOUND","message":"SDK channel not found"}}"""), { logs += it }).getToken()
            fail("Expected HTTP failure")
        } catch (expected: IOException) { }
        assertTrue(logs.any { it.contains("<-- HTTP 404") })
        assertTrue(logs.any { it.contains("SDK channel not found") })
    }

    @Test fun extractTokenIgnoresNonStrings() {
        assertNull(CustomerSessionBootstrapProvider.extractToken(JsonParser.parseString("""{"token":5}""")))
        assertNull(CustomerSessionBootstrapProvider.extractToken(JsonParser.parseString("""["bt"]""")))
    }
}
