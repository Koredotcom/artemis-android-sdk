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
        val baseConfiguration = readAsset(context, assetPath)
        val mergedConfiguration = if (environment == null) baseConfiguration else {
            val extensionSeparatorIndex = assetPath.lastIndexOf('.')
            val environmentOverridePath = if (extensionSeparatorIndex < 0) "$assetPath.$environment.yaml"
            else assetPath.substring(0, extensionSeparatorIndex) + ".$environment" + assetPath.substring(extensionSeparatorIndex)
            val environmentOverride = try {
                readAsset(context, environmentOverridePath)
            } catch (_: FileNotFoundException) {
                null
            }
            if (environmentOverride == null) baseConfiguration else deepMerge(baseConfiguration, environmentOverride)
        }
        @Suppress("UNCHECKED_CAST")
        val configurationRoot = (mergedConfiguration["artemis_ui_sdk"] ?: mergedConfiguration["artemis_ui_plugin"]) as? Map<String, Any?>
            ?: error("Missing artemis_ui_sdk configuration object")
        return fromMap(configurationRoot)
    }

    fun fromMap(configurationRoot: Map<String, Any?>): ArtemisUIConfig {
        val connection = configurationRoot.nestedMap("connection")
        val channel = configurationRoot.nestedMap("channel")
        val reconnection = configurationRoot.nestedMap("websocket").nestedMap("reconnection")
        val features = configurationRoot.nestedMap("features")
        val chat = configurationRoot.nestedMap("chat")
        val theme = configurationRoot.nestedMap("theme")
        val apiKey = connection.readString("api_key")
        require(connection.readString("bootstrap_token").isNullOrBlank()) {
            "bootstrap_token is not supported by the configured Android Socket SDK"
        }
        return ArtemisUIConfig(
            connection = ArtemisConnectionConfig(
                endpoint = connection.readString("endpoint").orEmpty(),
                projectId = connection.readString("project_id").orEmpty(),
                apiKey = apiKey.orEmpty(),
                channelId = channel.readString("channel_id") ?: connection.readString("channel_id"),
                channelName = channel.readString("channel_name") ?: connection.readString("channel_name"),
            ),
            reconnection = ArtemisReconnectionConfig(
                enabled = reconnection.readBoolean("enabled", true),
                maxAttempts = reconnection.readInteger("max_attempts", 5),
                baseDelayMs = reconnection.readLong("base_delay_ms", 1_000),
                maxDelayMs = reconnection.readLong("max_delay_ms", 30_000),
            ),
            features = ArtemisFeatureConfig(
                markdown = features.readBoolean("enable_markdown", true),
                carousel = features.readBoolean("enable_carousel", true),
                typingIndicator = chat.readBoolean("enable_typing_indicator", true),
                timestamps = configurationRoot.readBoolean("timestamps", true),
            ),
            theme = ArtemisThemeConfig(
                mode = if (theme.readBoolean("dark_mode", false)) "dark" else theme.readString("mode") ?: "system",
                primary = theme.readString("primary_color") ?: "#2563EB",
                background = theme.readString("background_color"),
                userBubble = theme.readString("user_bubble_color"),
                assistantBubble = theme.readString("assistant_bubble_color"),
                bubbleRadiusDp = theme.readFloat("border_radius", 16f),
            ),
        )
    }

    private fun readAsset(context: Context, path: String): Map<String, Any?> = context.assets.open(path).use { configInputStream ->
        require(configInputStream.available() <= 1_048_576) { "Configuration asset exceeds 1 MiB" }
        @Suppress("UNCHECKED_CAST")
        ((Yaml(SafeConstructor(LoaderOptions())).load<Any?>(configInputStream) as? Map<String, Any?>)
            ?: error("Configuration asset must contain a YAML object"))
    }

    private fun deepMerge(baseConfiguration: Map<String, Any?>, environmentOverride: Map<String, Any?>): Map<String, Any?> {
        val mergedConfiguration = baseConfiguration.toMutableMap()
        for ((key, value) in environmentOverride) {
            val existingValue = mergedConfiguration[key]
            mergedConfiguration[key] = if (existingValue is Map<*, *> && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                deepMerge(existingValue as Map<String, Any?>, value as Map<String, Any?>)
            } else value
        }
        return mergedConfiguration
    }

    private fun Map<String, Any?>.nestedMap(key: String): Map<String, Any?> =
        (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
    private fun Map<String, Any?>.readString(key: String) = this[key] as? String
    private fun Map<String, Any?>.readBoolean(key: String, default: Boolean) = this[key] as? Boolean ?: default
    private fun Map<String, Any?>.readInteger(key: String, default: Int) = (this[key] as? Number)?.toInt() ?: default
    private fun Map<String, Any?>.readLong(key: String, default: Long) = (this[key] as? Number)?.toLong() ?: default
    private fun Map<String, Any?>.readFloat(key: String, default: Float) = (this[key] as? Number)?.toFloat() ?: default
}
