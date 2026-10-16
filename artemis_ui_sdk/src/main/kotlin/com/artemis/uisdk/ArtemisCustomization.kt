package com.artemis.uisdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import com.artemis.uisdk.chat.ChatMessage
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale

/** Keys are Android string resource names (for example artemis_hint). Missing keys use resources. */
class ArtemisStrings @JvmOverloads constructor(
    overrides: Map<String, String> = emptyMap(),
    val localeTag: String? = null,
) {
    val overrides: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(overrides))
    init { require(localeTag == null || Locale.forLanguageTag(localeTag).language.isNotBlank()) { "Invalid locale tag" } }

    @SuppressLint("AppBundleLocaleChanges") // Hosts supply translations/resources; no language downloads are performed.
    internal fun text(context: Context, id: Int, vararg args: Any): String {
        val localized = if (localeTag == null) context else context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(localeTag))
        })
        val fallback = localized.getString(id)
        val value = overrides[context.resources.getResourceEntryName(id)] ?: fallback
        if (args.isEmpty()) return value
        val locale = localeTag?.let(Locale::forLanguageTag) ?: localized.resources.configuration.locales[0]
        return runCatching { String.format(locale, value, *args) }.getOrElse { String.format(locale, fallback, *args) }
    }
}

/** Local asset resolver. Return a fresh drawable or null for initials; the SDK does not fetch logos. */
fun interface ArtemisLogoResolver { fun resolve(context: Context, logoUrl: String): Drawable? }

/** Returning null (or throwing) keeps the built-in renderer/fallback. Return an unattached view. */
fun interface ArtemisTemplateRenderer { fun render(request: ArtemisTemplateRequest): View? }

class ArtemisTemplateRequest internal constructor(
    val context: Context,
    val message: ChatMessage,
    val templateName: String,
    val payload: JsonElement,
    val theme: ArtemisThemeConfig,
    val actionLocked: (String) -> Boolean,
    private val action: (String, String?, Map<String, String>?, String?) -> Unit,
) {
    @JvmOverloads
    fun submitAction(id: String, value: String? = null, formData: Map<String, String>? = null, renderId: String? = null) =
        action(id, value, formData, renderId)
}

/** Only supported presentation fields, with host fallbacks already applied. No token or permissions. */
data class ArtemisBranding(
    val theme: ArtemisThemeConfig,
    val assistantName: String? = null,
    val logoUrl: String? = null,
    val placeholderText: String? = null,
    val connectingStatusText: String? = null,
)

internal data class ArtemisPresentation(
    val branding: ArtemisBranding,
    val strings: ArtemisStrings,
    val rendererRevision: Int = 0,
    val logoResolver: ArtemisLogoResolver? = null,
)

internal fun resolveBranding(host: ArtemisThemeConfig, widget: JsonObject?): ArtemisBranding {
    val theme = widget?.get("theme")?.takeIf { it.isJsonObject }?.asJsonObject
    fun string(node: JsonObject?, key: String) = node?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.trim()?.takeIf(String::isNotBlank)
    fun color(key: String, fallback: String?) = string(theme, key)?.takeIf { runCatching { Color.parseColor(it) }.isSuccess } ?: fallback
    val radius = theme?.get("messageBubbleRadius")?.let { runCatching { it.asFloat }.getOrNull() }?.takeIf { it.isFinite() && it in 0f..32f }
    return ArtemisBranding(
        theme = host.copy(
            primary = color("primaryColor", host.primary) ?: host.primary,
            primaryForeground = color("primaryForegroundColor", host.primaryForeground),
            background = color("backgroundColor", host.background),
            headerBackground = color("headerBackgroundColor", host.headerBackground),
            headerText = color("headerTextColor", host.headerText),
            text = color("textColor", host.text), mutedText = color("textMutedColor", host.mutedText),
            surface = color("surfaceColor", host.surface), border = color("borderColor", host.border),
            userBubble = color("userBubbleColor", host.userBubble), userBubbleText = color("userBubbleTextColor", host.userBubbleText),
            assistantBubble = color("assistantBubbleColor", host.assistantBubble), assistantBubbleText = color("assistantBubbleTextColor", host.assistantBubbleText),
            composerBackground = color("composeBarBackgroundColor", host.composerBackground),
            composerText = color("composeBarTextColor", host.composerText),
            composerPlaceholder = color("composeBarPlaceholderColor", host.composerPlaceholder),
            bubbleRadiusDp = radius ?: host.bubbleRadiusDp,
        ),
        assistantName = string(theme, "assistantName") ?: host.assistantName,
        logoUrl = string(theme, "logoUrl") ?: host.logoUrl,
        placeholderText = string(widget, "placeholderText") ?: host.placeholderText,
        connectingStatusText = string(widget, "connectingStatusText"),
    )
}

internal fun ChatMessage.hostSnapshot() = copy(
    metadata = metadata?.deepCopy(), richContent = richContent?.deepCopy(), actions = actions?.deepCopy(),
    messageSource = messageSource?.deepCopy(), renderables = renderables?.deepCopy(), contentEnvelope = contentEnvelope?.deepCopy(),
    citations = citations.toList(), attachments = attachments.toList(), feedbackResponses = feedbackResponses.toMap(),
    authorization = authorization?.copy(rows = authorization.rows.toList()),
    transferSurvey = transferSurvey?.copy(commentRatings = transferSurvey.commentRatings.toList(), suppliedLabels = transferSurvey.suppliedLabels.toSet()),
)

internal fun Context.artemisColor(theme: ArtemisThemeConfig, id: Int): Int {
    val value = when (id) {
        R.color.artemis_primary -> theme.primary
        R.color.artemis_text -> theme.text
        R.color.artemis_muted -> theme.mutedText
        R.color.artemis_surface -> theme.surface
        R.color.artemis_border -> theme.border
        R.color.artemis_background -> theme.background
        else -> null
    }
    return runCatching { Color.parseColor(value) }.getOrDefault(getColor(id))
}
