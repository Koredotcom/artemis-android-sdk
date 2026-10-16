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
import com.artemis.uisdk.ArtemisThemeConfig
import com.artemis.uisdk.ArtemisUI
import com.artemis.uisdk.ArtemisUIConfig
import com.artemis.uisdk.ArtemisUiHandle

/** Minimal host-app integration sample. Runtime settings come from ignored local properties or environment variables. */
class MainActivity : AppCompatActivity() {
    private var artemisUiHandle: ArtemisUiHandle? = null
    // Values are supplied to this sample by Gradle; no real runtime values belong in source.
    private var endpoint = BuildConfig.ARTEMIS_SAMPLE_ENDPOINT
    private var projectId = BuildConfig.ARTEMIS_SAMPLE_PROJECT_ID
    private var apiKey = BuildConfig.ARTEMIS_SAMPLE_API_KEY
    private var channelId = BuildConfig.ARTEMIS_SAMPLE_CHANNEL_ID
    private var chatTitle = BuildConfig.ARTEMIS_SAMPLE_CHAT_TITLE

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
        if (endpoint.isBlank() || projectId.isBlank() || apiKey.isBlank()) {
            Toast.makeText(
                this,
                "Set endpoint, project ID, and API key in sample-runtime.local.properties before opening chat.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        try {
            val channelIdentifier = channelId.trim()
            val artemisUiConfiguration = ArtemisUIConfig(
                connection = ArtemisConnectionConfig(
                    endpoint = endpoint.trim(),
                    projectId = projectId.trim(),
                    apiKey = apiKey,
                    channelId = channelIdentifier.ifEmpty { null },
                    channelName = if (channelIdentifier.isEmpty()) "Support" else null,
                ),
                features = com.artemis.uisdk.ArtemisFeatureConfig(attachments = true, contactCenter = true, advancedFeedback = true, authentication = true),
                theme = ArtemisThemeConfig(primary = "#2563EB", mode = "system"),
            )
            val handle = artemisUiHandle ?: ArtemisUI.initialize(applicationContext, artemisUiConfiguration).also {
                artemisUiHandle = it
            }
            handle.showChat(this, com.artemis.uisdk.ChatOptions(title = chatTitle.ifBlank { "Chat" }))
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, error.message ?: "Check the configuration", Toast.LENGTH_LONG).show()
        } catch (error: IllegalStateException) {
            Toast.makeText(this, error.message ?: "Chat could not be opened", Toast.LENGTH_LONG).show()
        }
    }

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
