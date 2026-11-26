package com.artemis.uisdk.sample

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.artemis.uisdk.ArtemisConnectionConfig
import com.artemis.uisdk.ArtemisFeatureConfig
import com.artemis.uisdk.ArtemisSessionConfig
import com.artemis.uisdk.ArtemisThemeConfig
import com.artemis.uisdk.ArtemisUI
import com.artemis.uisdk.ArtemisUIConfig
import com.artemis.uisdk.ArtemisUiEvent
import com.artemis.uisdk.ArtemisUiHandle
import com.artemis.uisdk.ChatOptions
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** Minimal host-app integration sample. Runtime settings come from ignored local properties or environment variables. */
class MainActivity : AppCompatActivity() {
    private var artemisUiHandle: ArtemisUiHandle? = null
    // Values are supplied to this sample by Gradle; no real runtime values belong in source.
    private var endpoint = BuildConfig.ARTEMIS_SAMPLE_ENDPOINT
    private var projectId = BuildConfig.ARTEMIS_SAMPLE_PROJECT_ID
    private var apiKey = BuildConfig.ARTEMIS_SAMPLE_API_KEY
    private var channelId = BuildConfig.ARTEMIS_SAMPLE_CHANNEL_ID
    private var channelName = BuildConfig.ARTEMIS_SAMPLE_CHANNEL_NAME
    private var chatTitle = BuildConfig.ARTEMIS_SAMPLE_CHAT_TITLE
    // Hosted bootstrap: set CHANNEL_SECRET to use /api/v1/sdk/customer-sessions instead of API_KEY.
    private var tenantId = BuildConfig.ARTEMIS_SAMPLE_TENANT_ID
    private var channelSecret = BuildConfig.ARTEMIS_SAMPLE_CHANNEL_SECRET
    private var verifiedUserId = BuildConfig.ARTEMIS_SAMPLE_VERIFIED_USER_ID
    private var customAttributesJson = BuildConfig.ARTEMIS_SAMPLE_CUSTOM_ATTRIBUTES_JSON

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contentMarginPixels = convertDpToPixels(20)
        val mainActivityRootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(contentMarginPixels, contentMarginPixels, contentMarginPixels, contentMarginPixels)
            gravity = Gravity.CENTER_VERTICAL
        }
        mainActivityRootLayout.addView(TextView(this).apply {
            text = context.getString(R.string.artemis_android_ui_sdk)
            textSize = 24f
            setTextColor(0xFF172554.toInt())
        }, createMatchParentWrapContentLayoutParams())
        mainActivityRootLayout.addView(TextView(this).apply {
            text = context.getString(R.string.enter_your_runtime_configuration)
            textSize = 14f
            setTextColor(0xFF475569.toInt())
            setPadding(0, convertDpToPixels(10), 0, convertDpToPixels(18))
        }, createMatchParentWrapContentLayoutParams())

        mainActivityRootLayout.addView(Button(this).apply {
            text = context.getString(R.string.open_chat)
            setOnClickListener { openChat() }
        }, LinearLayout.LayoutParams(-1, convertDpToPixels(52)).apply { topMargin = convertDpToPixels(12) })
        mainActivityRootLayout.addView(TextView(this).apply {
            text = context.getString(R.string.socket_module_artemis_socket_sdk_artemis_socket_0_0_2)
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, convertDpToPixels(18), 0, 0)
        }, createMatchParentWrapContentLayoutParams())
        setContentView(mainActivityRootLayout)
    }

    private fun openChat() {
        val hostedBootstrap = channelSecret.isNotBlank()
        if (endpoint.isBlank() || projectId.isBlank() || (!hostedBootstrap && apiKey.isBlank())) {
            Toast.makeText(
                this,
                "Set endpoint, project ID, and API key (or channel secret) in sample-runtime.local.properties before opening chat.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        // An existing handle keeps its own connection and token provider; just show it again.
        artemisUiHandle?.let { showChat(it); return }
        if (!hostedBootstrap) {
            runCatchingConfig { initializeAndShow(apiKeyConnection()) }
            return
        }
        val provider = runCatchingConfig { customerSessionProvider() } ?: return
        // The SDK calls the provider on its background thread for the first init and each later init.
        runCatchingConfig { initializeAndShow(hostedBootstrapConnection(provider)) }
    }

    private fun initializeAndShow(connection: ArtemisConnectionConfig) {
        val artemisUiConfiguration = ArtemisUIConfig(
            connection = connection,
            features = ArtemisFeatureConfig(
                attachments = true,
                contactCenter = true,
                advancedFeedback = true,
                authentication = true,
                showAnsweredByAI = false,
            ),
            session = ArtemisSessionConfig(endSessionOnClose = true),
            theme = ArtemisThemeConfig(primary = "#2563EB", mode = "system"),
        )
        val handle = ArtemisUI.initialize(applicationContext, artemisUiConfiguration).also {
            artemisUiHandle = it
            CustomTemplateInjection.register(it)
            // deep_link elements with navigation_type "internal" arrive here; the chat is already minimized.
            it.addListener { event ->
                if (event is ArtemisUiEvent.InternalDeepLink) {
                    Toast.makeText(this, "Open in app: ${event.url}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        showChat(handle)
    }

    private fun showChat(handle: ArtemisUiHandle) {
        runCatchingConfig {
            handle.showChat(this, ChatOptions(
                title = chatTitle.ifBlank { "Chat" },
//                headerBuilder = CustomChatheaderFooter.headerBuilder,
//                footerBuilder = CustomChatheaderFooter.footerBuilder,
            ))
        }
    }

    /** Shows configuration problems as a toast instead of crashing the sample. */
    private fun <T> runCatchingConfig(block: () -> T): T? = try {
        block()
    } catch (error: IllegalArgumentException) {
        Toast.makeText(this, error.message ?: "Check the configuration", Toast.LENGTH_LONG).show()
        null
    } catch (error: IllegalStateException) {
        Toast.makeText(this, error.message ?: "Chat could not be opened", Toast.LENGTH_LONG).show()
        null
    }

    private fun channelIdOrNull() = channelId.trim().ifEmpty { null }

    private fun channelNameOrDefault() = channelName.trim().ifEmpty { "Support" }

    private fun apiKeyConnection(): ArtemisConnectionConfig {
        val resolvedChannelId = channelIdOrNull()
        return ArtemisConnectionConfig(
            endpoint = endpoint.trim(),
            projectId = projectId.trim(),
            apiKey = apiKey,
            channelId = resolvedChannelId,
            channelName = if (resolvedChannelId == null) channelNameOrDefault() else null,
        )
    }

    private fun customerSessionProvider(): CustomerSessionBootstrapProvider {
        val resolvedChannelId = channelIdOrNull()
        val customAttributes = customAttributesJson.trim().takeIf { it.isNotEmpty() }?.let {
            val parsed = try {
                JsonParser.parseString(it)
            } catch (error: JsonParseException) {
                throw IllegalArgumentException("CUSTOM_ATTRIBUTES_JSON must be a JSON object")
            }
            require(parsed.isJsonObject) { "CUSTOM_ATTRIBUTES_JSON must be a JSON object" }
            parsed.asJsonObject
        }
        return CustomerSessionBootstrapProvider(
            endpoint = endpoint,
            channelSecret = channelSecret.trim(),
            tenantId = tenantId.trim(),
            projectId = projectId.trim(),
            channelId = resolvedChannelId,
            channelName = if (resolvedChannelId == null) channelNameOrDefault() else null,
            verifiedUserId = verifiedUserId.trim(),
            customAttributes = customAttributes,
        )
    }

    /** The SDK requests a fresh hosted bootstrap token for every init, starting with the first one. */
    private fun hostedBootstrapConnection(provider: CustomerSessionBootstrapProvider) =
        ArtemisConnectionConfig(
            endpoint = endpoint.trim(),
            projectId = projectId.trim(),
            bootstrapTokenProvider = provider,
        )

    override fun onDestroy() {
        if (isFinishing) artemisUiHandle?.close()
        artemisUiHandle = null
        super.onDestroy()
    }

    private fun createMatchParentWrapContentLayoutParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun convertDpToPixels(densityIndependentPixels: Int) =
        (densityIndependentPixels * resources.displayMetrics.density).toInt()
}
