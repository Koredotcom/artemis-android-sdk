package com.artemis.uisdk

import android.content.Context
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.FileNotFoundException
import java.net.URI
import com.google.gson.JsonObject
import artemis.socket.ArtemisSocketConfiguration

/** Connection information required by ArtemisSocketClient. Never log this object. */
class ArtemisConnectionConfig @JvmOverloads constructor(
    val endpoint: String,
    val projectId: String,
    val apiKey: String = "",
    val channelId: String? = null,
    val channelName: String? = null,
    val bootstrapToken: String? = null,
    val bootstrapTokenProvider: ArtemisSocketConfiguration.BootstrapTokenProvider? = null,
    val deploymentSlug: String? = null,
    val userContext: JsonObject? = null,
    val sessionMetadata: JsonObject? = null,
    val clientSessionIdentifier: Boolean = false,
) {
    override fun toString() =
        "ArtemisConnectionConfig(endpoint=$endpoint, projectId=$projectId, apiKey=<redacted>)"
}

data class ArtemisReconnectionConfig(
    val enabled: Boolean = true,
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    val unlimitedTransientRetries: Boolean = false,
)

data class ArtemisFeatureConfig @JvmOverloads constructor(
    val markdown: Boolean = true,
    val carousel: Boolean = true,
    val typingIndicator: Boolean = true,
    val timestamps: Boolean = true,
    val history: Boolean = true,
    val activityUpdates: Boolean = true,
    val citations: Boolean = true,
    val attachments: Boolean = false,
    val contactCenter: Boolean = false,
    val advancedFeedback: Boolean = false,
    val authentication: Boolean = false,
)

/** Client-side policy supplements the Runtime's project policy; it never overrides a server rejection. */
data class ArtemisAttachmentConfig(
    val maxFileSizeBytes: Long = 20L * 1024 * 1024,
    val allowedMimeTypes: Set<String> = setOf(
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp",
        "application/pdf",
        "text/plain",
        "text/csv",
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "audio/*",
        "video/*",
    ),
) {
    init {
        require(maxFileSizeBytes in 1..artemis.socket.ArtemisSocketClient.MAX_ATTACHMENT_BYTES) { "Attachment size must be between 1 byte and 20 MiB" }
        require(allowedMimeTypes.isNotEmpty() && allowedMimeTypes.all { it.matches(Regex("[a-z0-9!#$&^_.+-]+/(?:[a-z0-9!#$&^_.+-]+|\\*)")) }) { "Specify valid allowed MIME types" }
    }

    fun allows(mime: String): Boolean {
        val value = mime.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        return value in allowedMimeTypes || "${value.substringBefore('/')}/*" in allowedMimeTypes
    }
}

data class ArtemisThemeConfig @JvmOverloads constructor(
    val mode: String = "system",
    val primary: String = "#2563EB",
    val background: String? = null,
    val userBubble: String? = null,
    val assistantBubble: String? = null,
    val bubbleRadiusDp: Float = 16f,
    val primaryForeground: String? = null,
    val headerBackground: String? = null,
    val headerText: String? = null,
    val text: String? = null,
    val mutedText: String? = null,
    val surface: String? = null,
    val border: String? = null,
    val userBubbleText: String? = null,
    val assistantBubbleText: String? = null,
    val composerBackground: String? = null,
    val composerText: String? = null,
    val composerPlaceholder: String? = null,
    val assistantName: String? = null,
    val logoUrl: String? = null,
    val placeholderText: String? = null,
)

data class ArtemisSessionConfig(
    val idleDisconnectTimeoutMs: Long = 0,
    val endSessionOnIdle: Boolean = false,
    val staleSessionRefreshMs: Long = 0,
    val endSessionOnClose: Boolean = false,
)

class ArtemisUIConfig @JvmOverloads constructor(
    val connection: ArtemisConnectionConfig,
    val reconnection: ArtemisReconnectionConfig = ArtemisReconnectionConfig(),
    val features: ArtemisFeatureConfig = ArtemisFeatureConfig(),
    val theme: ArtemisThemeConfig = ArtemisThemeConfig(),
    val session: ArtemisSessionConfig = ArtemisSessionConfig(),
    val attachment: ArtemisAttachmentConfig = ArtemisAttachmentConfig(),
    val strings: ArtemisStrings = ArtemisStrings(),
    val logoResolver: ArtemisLogoResolver? = null,
) {
    init {
        val uri = runCatching { URI(connection.endpoint) }.getOrNull()
        require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) {
            "connection.endpoint must be an absolute HTTP(S) URL"
        }
        require(connection.projectId.isNotBlank()) { "connection.projectId is required" }
        // Keep validation identical to the actual transport, including credential exclusivity.
        ArtemisSocketConfiguration.builder().endpoint(connection.endpoint)
            .projectId(connection.projectId)
            .apiKey(connection.apiKey).channelId(connection.channelId)
            .channelName(connection.channelName)
            .bootstrapToken(connection.bootstrapToken)
            .bootstrapTokenProvider(connection.bootstrapTokenProvider)
            .deploymentSlug(connection.deploymentSlug).userContext(connection.userContext)
            .sessionMetadata(connection.sessionMetadata)
            .clientSessionIdentifier(if (connection.clientSessionIdentifier) "validation" else null)
            .build()
        require(session.idleDisconnectTimeoutMs >= 0 && session.staleSessionRefreshMs >= 0) { "Invalid session timeouts" }
        require(reconnection.maxAttempts in 0..30) { "reconnection.maxAttempts must be between 0 and 30" }
        require(reconnection.baseDelayMs >= 0 && reconnection.maxDelayMs >= reconnection.baseDelayMs) {
            "Invalid reconnection delays"
        }
        require(
            theme.mode in setOf(
                "system",
                "light",
                "dark"
            )
        ) { "theme.mode must be system, light, or dark" }
        require(theme.bubbleRadiusDp in 0f..32f) { "theme.bubbleRadiusDp must be between 0 and 32" }
    }

    override fun toString() =
        "ArtemisUIConfig(connection=$connection, reconnection=$reconnection, features=$features, theme=$theme)"
}

/** Loads a safe YAML asset and recursively applies an optional environment override. */
object ArtemisConfigLoader {
    fun load(
        context: Context,
        assetPath: String = "sdk_configurations.yaml",
        environment: String? = null
    ): ArtemisUIConfig {
        require(environment == null || environment.matches(Regex("[A-Za-z0-9_-]+"))) {
            "environment may contain only letters, digits, underscore, and dash"
        }
        val baseConfiguration = readAsset(context, assetPath)
        val mergedConfiguration = if (environment == null) baseConfiguration else {
            val extensionSeparatorIndex = assetPath.lastIndexOf('.')
            val environmentOverridePath =
                if (extensionSeparatorIndex < 0) "$assetPath.$environment.yaml"
                else assetPath.substring(
                    0,
                    extensionSeparatorIndex
                ) + ".$environment" + assetPath.substring(extensionSeparatorIndex)
            val environmentOverride = try {
                readAsset(context, environmentOverridePath)
            } catch (_: FileNotFoundException) {
                null
            }
            if (environmentOverride == null) baseConfiguration else deepMerge(
                baseConfiguration,
                environmentOverride
            )
        }

        @Suppress("UNCHECKED_CAST")
        val configurationRoot = (mergedConfiguration["artemis_ui_sdk"]
            ?: mergedConfiguration["artemis_ui_plugin"]) as? Map<String, Any?>
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
        return ArtemisUIConfig(
            connection = ArtemisConnectionConfig(
                endpoint = connection.readString("endpoint").orEmpty(),
                projectId = connection.readString("project_id").orEmpty(),
                apiKey = apiKey.orEmpty(),
                channelId = channel.readString("channel_id") ?: connection.readString("channel_id"),
                channelName = channel.readString("channel_name")
                    ?: connection.readString("channel_name"),
                bootstrapToken = connection.readString("bootstrap_token"),
                deploymentSlug = connection.readString("deployment_slug"),
                clientSessionIdentifier = connection.readBoolean(
                    "client_session_identifier",
                    false
                ),
                userContext = (connection["user_context"] as? Map<*, *>)?.let {
                    com.google.gson.Gson().toJsonTree(it).asJsonObject
                },
                sessionMetadata = (connection["session_metadata"] as? Map<*, *>)?.let {
                    com.google.gson.Gson().toJsonTree(it).asJsonObject
                },
            ),
            reconnection = ArtemisReconnectionConfig(
                enabled = reconnection.readBoolean("enabled", true),
                maxAttempts = reconnection.readInteger("max_attempts", 5),
                baseDelayMs = reconnection.readLong("base_delay_ms", 1_000),
                maxDelayMs = reconnection.readLong("max_delay_ms", 30_000),
                unlimitedTransientRetries = reconnection.readBoolean(
                    "unlimited_transient_retries",
                    false
                ),
            ),
            features = ArtemisFeatureConfig(
                markdown = features.readBoolean("enable_markdown", true),
                carousel = features.readBoolean("enable_carousel", true),
                typingIndicator = chat.readBoolean("enable_typing_indicator", true),
                timestamps = configurationRoot.readBoolean("timestamps", true),
                history = features.readBoolean("enable_history", true),
                activityUpdates = features.readBoolean("enable_activity_updates", true),
                citations = features.readBoolean("enable_citations", true),
                attachments = features.readBoolean("enable_attachments", false),
                contactCenter = features.readBoolean("enable_contact_center", false),
                advancedFeedback = features.readBoolean("enable_advanced_feedback", false),
                authentication = features.readBoolean("enable_authentication", false),
            ),
            attachment = configurationRoot.nestedMap("attachments").let { options ->
                ArtemisAttachmentConfig(
                    maxFileSizeBytes = options.readLong("max_file_size_bytes", 20L * 1024 * 1024),
                    allowedMimeTypes = if (options.containsKey("allowed_mime_types")) {
                        val types = options["allowed_mime_types"] as? List<*>
                            ?: error("allowed_mime_types must be a list")
                        require(types.all { it is String }) { "allowed_mime_types must contain strings" }
                        types.filterIsInstance<String>().toSet()
                    } else ArtemisAttachmentConfig().allowedMimeTypes,
                )
            },
            session = configurationRoot.nestedMap("session").let { session ->
                ArtemisSessionConfig(
                    idleDisconnectTimeoutMs = session.readLong("idle_disconnect_timeout_ms", 0),
                    endSessionOnIdle = session.readBoolean("end_session_on_idle", false),
                    staleSessionRefreshMs = session.readLong("stale_session_refresh_ms", 0),
                    endSessionOnClose = session.readBoolean("end_session_on_close", false),
                )
            },
            strings = configurationRoot.nestedMap("strings").let { strings ->
                ArtemisStrings(
                    overrides = strings.nestedMap("overrides")
                        .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
                        .toMap(),
                    localeTag = strings.readString("locale"),
                )
            },
            theme = ArtemisThemeConfig(
                mode = if (theme.readBoolean(
                        "dark_mode",
                        false
                    )
                ) "dark" else theme.readString("mode") ?: "system",
                primary = theme.readString("primary_color") ?: "#2563EB",
                background = theme.readString("background_color"),
                userBubble = theme.readString("user_bubble_color"),
                assistantBubble = theme.readString("assistant_bubble_color"),
                bubbleRadiusDp = theme.readFloat("border_radius", 16f),
                primaryForeground = theme.readString("primary_foreground_color"),
                headerBackground = theme.readString("header_background_color"),
                headerText = theme.readString("header_text_color"),
                text = theme.readString("text_color"),
                mutedText = theme.readString("text_muted_color"),
                surface = theme.readString("surface_color"),
                border = theme.readString("border_color"),
                userBubbleText = theme.readString("user_bubble_text_color"),
                assistantBubbleText = theme.readString("assistant_bubble_text_color"),
                composerBackground = theme.readString("compose_bar_background_color"),
                composerText = theme.readString("compose_bar_text_color"),
                composerPlaceholder = theme.readString("compose_bar_placeholder_color"),
                assistantName = theme.readString("assistant_name"),
                logoUrl = theme.readString("logo_url"),
                placeholderText = theme.readString("placeholder_text"),
            ),
        )
    }

    private fun readAsset(context: Context, path: String): Map<String, Any?> =
        context.assets.open(path).use { configInputStream ->
            require(configInputStream.available() <= 1_048_576) { "Configuration asset exceeds 1 MiB" }
            @Suppress("UNCHECKED_CAST")
            ((Yaml(SafeConstructor(LoaderOptions())).load<Any?>(configInputStream) as? Map<String, Any?>)
                ?: error("Configuration asset must contain a YAML object"))
        }

    private fun deepMerge(
        baseConfiguration: Map<String, Any?>,
        environmentOverride: Map<String, Any?>
    ): Map<String, Any?> {
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
        (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            ?: emptyMap()

    private fun Map<String, Any?>.readString(key: String) = this[key] as? String
    private fun Map<String, Any?>.readBoolean(key: String, default: Boolean) =
        this[key] as? Boolean ?: default

    private fun Map<String, Any?>.readInteger(key: String, default: Int) =
        (this[key] as? Number)?.toInt() ?: default

    private fun Map<String, Any?>.readLong(key: String, default: Long) =
        (this[key] as? Number)?.toLong() ?: default

    private fun Map<String, Any?>.readFloat(key: String, default: Float) =
        (this[key] as? Number)?.toFloat() ?: default
}
