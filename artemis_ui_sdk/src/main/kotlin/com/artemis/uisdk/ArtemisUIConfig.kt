package com.artemis.uisdk

import android.content.Context
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.FileNotFoundException
import java.net.URI

/** Connection information required by ArtemisSocketClient. Never log this object. */
class ArtemisConnectionConfig(
    val endpoint: String,
    val projectId: String,
    val apiKey: String,
    val channelId: String? = null,
    val channelName: String? = null,
) {
    override fun toString() = "ArtemisConnectionConfig(endpoint=$endpoint, projectId=$projectId, apiKey=<redacted>)"
}

data class ArtemisReconnectionConfig(
    val enabled: Boolean = true,
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
)

data class ArtemisFeatureConfig(
    val markdown: Boolean = true,
    val carousel: Boolean = true,
    val typingIndicator: Boolean = true,
    val timestamps: Boolean = true,
)

data class ArtemisThemeConfig(
    val mode: String = "system",
    val primary: String = "#2563EB",
    val background: String? = null,
    val userBubble: String? = null,
    val assistantBubble: String? = null,
    val bubbleRadiusDp: Float = 16f,
)

class ArtemisUIConfig(
    val connection: ArtemisConnectionConfig,
    val reconnection: ArtemisReconnectionConfig = ArtemisReconnectionConfig(),
    val features: ArtemisFeatureConfig = ArtemisFeatureConfig(),
    val theme: ArtemisThemeConfig = ArtemisThemeConfig(),
) {
    init {
        val uri = runCatching { URI(connection.endpoint) }.getOrNull()
        require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) {
            "connection.endpoint must be an absolute HTTP(S) URL"
        }
        require(connection.projectId.isNotBlank()) { "connection.projectId is required" }
        require(connection.apiKey.isNotBlank()) { "connection.apiKey is required" }
        require(!connection.channelId.isNullOrBlank() || !connection.channelName.isNullOrBlank()) {
            "connection.channelId or connection.channelName is required"
        }
        require(reconnection.maxAttempts in 0..30) { "reconnection.maxAttempts must be between 0 and 30" }
        require(reconnection.baseDelayMs >= 0 && reconnection.maxDelayMs >= reconnection.baseDelayMs) {
            "Invalid reconnection delays"
        }
        require(theme.mode in setOf("system", "light", "dark")) { "theme.mode must be system, light, or dark" }
        require(theme.bubbleRadiusDp in 0f..32f) { "theme.bubbleRadiusDp must be between 0 and 32" }
    }

    override fun toString() = "ArtemisUIConfig(connection=$connection, reconnection=$reconnection, features=$features, theme=$theme)"
}

/** Loads a safe YAML asset and recursively applies an optional environment override. */
object ArtemisConfigLoader {
    fun load(context: Context, assetPath: String = "sdk_configurations.yaml", environment: String? = null): ArtemisUIConfig {
        require(environment == null || environment.matches(Regex("[A-Za-z0-9_-]+"))) {
            "environment may contain only letters, digits, underscore, and dash"
        }
        val base = readAsset(context, assetPath)
        val merged = if (environment == null) base else {
            val dot = assetPath.lastIndexOf('.')
            val overridePath = if (dot < 0) "$assetPath.$environment.yaml"
            else assetPath.substring(0, dot) + ".$environment" + assetPath.substring(dot)
            val override = try {
                readAsset(context, overridePath)
            } catch (_: FileNotFoundException) {
                null
            }
            if (override == null) base else deepMerge(base, override)
        }
        @Suppress("UNCHECKED_CAST")
        val root = (merged["artemis_ui_sdk"] ?: merged["artemis_ui_plugin"]) as? Map<String, Any?>
            ?: error("Missing artemis_ui_sdk configuration object")
        return fromMap(root)
    }

    fun fromMap(root: Map<String, Any?>): ArtemisUIConfig {
        val connection = root.map("connection")
        val channel = root.map("channel")
        val reconnection = root.map("websocket").map("reconnection")
        val features = root.map("features")
        val chat = root.map("chat")
        val theme = root.map("theme")
        val apiKey = connection.string("api_key")
        require(connection.string("bootstrap_token").isNullOrBlank()) {
            "bootstrap_token is not supported by the configured Android Socket SDK"
        }
        return ArtemisUIConfig(
            connection = ArtemisConnectionConfig(
                endpoint = connection.string("endpoint").orEmpty(),
                projectId = connection.string("project_id").orEmpty(),
                apiKey = apiKey.orEmpty(),
                channelId = channel.string("channel_id") ?: connection.string("channel_id"),
                channelName = channel.string("channel_name") ?: connection.string("channel_name"),
            ),
            reconnection = ArtemisReconnectionConfig(
                enabled = reconnection.bool("enabled", true),
                maxAttempts = reconnection.int("max_attempts", 5),
                baseDelayMs = reconnection.long("base_delay_ms", 1_000),
                maxDelayMs = reconnection.long("max_delay_ms", 30_000),
            ),
            features = ArtemisFeatureConfig(
                markdown = features.bool("enable_markdown", true),
                carousel = features.bool("enable_carousel", true),
                typingIndicator = chat.bool("enable_typing_indicator", true),
                timestamps = root.bool("timestamps", true),
            ),
            theme = ArtemisThemeConfig(
                mode = if (theme.bool("dark_mode", false)) "dark" else theme.string("mode") ?: "system",
                primary = theme.string("primary_color") ?: "#2563EB",
                background = theme.string("background_color"),
                userBubble = theme.string("user_bubble_color"),
                assistantBubble = theme.string("assistant_bubble_color"),
                bubbleRadiusDp = theme.float("border_radius", 16f),
            ),
        )
    }

    private fun readAsset(context: Context, path: String): Map<String, Any?> = context.assets.open(path).use { input ->
        require(input.available() <= 1_048_576) { "Configuration asset exceeds 1 MiB" }
        @Suppress("UNCHECKED_CAST")
        ((Yaml(SafeConstructor(LoaderOptions())).load<Any?>(input) as? Map<String, Any?>)
            ?: error("Configuration asset must contain a YAML object"))
    }

    private fun deepMerge(base: Map<String, Any?>, override: Map<String, Any?>): Map<String, Any?> {
        val result = base.toMutableMap()
        for ((key, value) in override) {
            val previous = result[key]
            result[key] = if (previous is Map<*, *> && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                deepMerge(previous as Map<String, Any?>, value as Map<String, Any?>)
            } else value
        }
        return result
    }

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
        (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
    private fun Map<String, Any?>.string(key: String) = this[key] as? String
    private fun Map<String, Any?>.bool(key: String, default: Boolean) = this[key] as? Boolean ?: default
    private fun Map<String, Any?>.int(key: String, default: Int) = (this[key] as? Number)?.toInt() ?: default
    private fun Map<String, Any?>.long(key: String, default: Long) = (this[key] as? Number)?.toLong() ?: default
    private fun Map<String, Any?>.float(key: String, default: Float) = (this[key] as? Number)?.toFloat() ?: default
}
