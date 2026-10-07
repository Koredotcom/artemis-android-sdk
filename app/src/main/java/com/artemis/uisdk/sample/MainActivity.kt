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

/** Minimal host-app integration sample. Configuration is kept in memory and never persisted. */
class MainActivity : AppCompatActivity() {
    private var handle: com.artemis.uisdk.ArtemisUiHandle? = null
    // Assign local runtime configuration here; do not commit real credentials.
    private var endpoint = ""
    private var projectId = ""
    private var apiKey = ""
    private var channelId = ""
    private var chatTitle = "Artemis Chat"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val margin = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin, margin, margin)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(TextView(this).apply {
            text = context.getString(R.string.artemis_android_ui_sdk)
            textSize = 24f
            setTextColor(0xFF172554.toInt())
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = context.getString(R.string.enter_your_runtime_configuration)
            textSize = 14f
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(10), 0, dp(18))
        }, matchWrap())

        root.addView(Button(this).apply {
            text = context.getString(R.string.open_chat)
            setOnClickListener { openChat() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        root.addView(TextView(this).apply {
            text = context.getString(R.string.socket_module_artemis_socket_sdk_artemis_socket_0_0_2)
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }, matchWrap())
        setContentView(root)
    }

    private fun openChat() {
        if (endpoint.isBlank() || projectId.isBlank() || apiKey.isBlank()) {
            Toast.makeText(
                this,
                "Set endpoint, project ID, and API key in MainActivity before opening chat.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        try {
            val channel = channelId.trim()
            val config = ArtemisUIConfig(
                connection = ArtemisConnectionConfig(
                    endpoint = endpoint.trim(),
                    projectId = projectId.trim(),
                    apiKey = apiKey,
                    channelId = channel.ifEmpty { null },
                    channelName = if (channel.isEmpty()) "Support" else null,
                ),
                theme = ArtemisThemeConfig(primary = "#2563EB", mode = "system"),
            )
            handle?.close()
            handle = ArtemisUI.initialize(applicationContext, config)
            handle?.showChat(this, com.artemis.uisdk.ChatOptions(title = chatTitle.ifBlank { "Chat" }))
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, error.message ?: "Check the configuration", Toast.LENGTH_LONG).show()
        } catch (error: IllegalStateException) {
            Toast.makeText(this, error.message ?: "Chat could not be opened", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (isFinishing) handle?.close()
        handle = null
        super.onDestroy()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
