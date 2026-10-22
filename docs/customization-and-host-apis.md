# UI customization and host APIs

## Overview

This guide describes the SDK's presentation options and host extension APIs. Runtime branding uses the `widgetConfig` snapshot supplied during SDK initialization and token refresh. Theme, string, and renderer updates apply to the chat presentation without reconnecting or reloading conversation history.

Existing integrations require no new configuration. Attachment upload visibility still requires all three: the host attachment feature, branding `theme.attachmentButtonEnabled`, and `attachment:write`. The five-file limit and 40dp composer buttons/input minimum height remain in effect.

| Host customization | API |
| --- | --- |
| Chat header and composer | `ChatOptions.headerBuilder` and `footerBuilder` |
| Rich-content templates | `registerTemplateRenderer("carousel" / "list" / "table" / ...)` |
| Fonts and text styling | `ArtemisTypographyConfig` |
| Colors and branding | `ArtemisThemeConfig`, `updateTheme`, `setLogoResolver` |
| Strings and locale | `ArtemisStrings`, `updateStrings` |
| Welcome message and per-chat identity | `ArtemisUIConfig.welcomeMessage`, `ChatOptions.runtimeUserContext` |

## Runtime branding

Supported Web SDK fields:

| Runtime field | Android presentation |
| --- | --- |
| `theme.assistantName` | Header title and fallback initial |
| `theme.logoUrl` | Host-resolved local logo; otherwise initials |
| `theme.primaryColor`, `primaryForegroundColor` | Accent, send button and its icon |
| `theme.headerBackgroundColor`, `headerTextColor` | Header and controls |
| `theme.backgroundColor`, `textColor`, `textMutedColor` | Conversation background and native text |
| `theme.surfaceColor`, `borderColor` | Template card surfaces and input border |
| `theme.userBubbleColor`, `userBubbleTextColor` | User messages |
| `theme.assistantBubbleColor`, `assistantBubbleTextColor` | Assistant messages |
| `theme.composeBarBackgroundColor`, `composeBarTextColor`, `composeBarPlaceholderColor` | Composer |
| Numeric `theme.messageBubbleRadius` (0–32) | Message bubble radius in dp |
| `placeholderText`, `connectingStatusText` | Input hint and connecting label |

A valid runtime value overrides the host theme. Missing, null, malformed, or unsupported values use the host setting, then native defaults. Runtime config removal clears the previous runtime overlay. Identical refresh branding does not invalidate templates. Launcher styling, CSS fonts/images, density and Web-specific radius presets are not interpreted as Android layout instructions. Light/dark mode remains an initialization setting.

Runtime `widgetConfig.welcomeMessage` is shown in the empty conversation state and takes precedence over the optional host `ArtemisUIConfig.welcomeMessage`. The host value can be supplied in YAML as `chat.welcome_message`.

Host theme updates use the same fields through `ArtemisThemeConfig`:

```kotlin
// Run UI commands on the main thread. Runtime branding retains precedence.
handle.updateTheme(config.theme.copy(primary = "#14532D", assistantName = "Support"))
```

Host fonts and text styling are configured separately from colors. Supply a bundled `Typeface` or Android system family, an optional monospace face, a text-size scale, and an optional callback for per-`TextView` styling. The callback runs on the main thread after SDK styling, for SDK and custom-rendered text views.

```kotlin
val typography = ArtemisTypographyConfig(
    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.brand_sans),
    monospaceTypeface = android.graphics.Typeface.MONOSPACE,
    textSizeScale = 1.05f,
    textViewStyler = ArtemisTextViewStyler { textView ->
        textView.letterSpacing = 0.01f
    },
)
val config = ArtemisUIConfig(
    connection = connection,
    theme = ArtemisThemeConfig(primary = "#0F766E", headerBackground = "#115E59"),
    typography = typography,
    welcomeMessage = "Welcome! How can we help?",
)
// An open handle can also apply a new typography configuration at runtime.
handle.updateTypography(typography)
```

Put the font file or Android font-family XML in the host app's `res/font/` directory and load it with `ResourcesCompat.getFont`. The scale must be between `0.5f` and `2f`. Typography is applied to SDK and custom-rendered text; the callback runs on the main thread after the SDK styling pass. `theme.font_family` in YAML maps to an Android system family name. Use `ArtemisTypographyConfig` for bundled fonts.

For example, add `res/font/brand_sans.xml` and the regular/bold font files it references:

```xml
<font-family xmlns:android="http://schemas.android.com/apk/res/android">
    <font android:font="@font/brand_sans_regular" android:fontWeight="400" />
    <font android:font="@font/brand_sans_bold" android:fontWeight="700" />
</font-family>
```

The app module demonstrates this with the 29LT Bukra regular and bold font files copied from the Flutter example. `MainActivity` loads `R.font.bukra` and registers client renderers for the `carousel`, `list`, and `table` rich-content keys in `CustomTemplateInjection`. Each renderer reads its own `request.payload`; the SDK uses the returned view in place of its built-in template. If a payload does not match the sample renderer's expected structure, it returns null and the SDK falls back to its built-in renderer.

### Logos without SDK network requests

A logo URL is an identifier for a host-supplied, fast, local resolver. The SDK never downloads it or passes SDK credentials to it. Return a fresh drawable; null or an exception falls back to initials. The resolver is called on the UI thread, so do not perform blocking I/O there.

```kotlin
handle.setLogoResolver { context, logoUrl ->
    if (logoUrl == "https://assets.example.com/support.png")
        androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.support_logo)
    else null
}
```

A resolver may also be passed to `ArtemisUIConfig(logoResolver = ...)` at initialization.

## Custom chat header and footer

Pass native view builders in `ChatOptions` to replace the SDK header, composer, or both for a chat. Builders return unattached views. The default SDK views remain in use when a builder is omitted.

```kotlin
handle.showChat(activity, ChatOptions(
    title = "Support",
    headerBuilder = ArtemisChatHeaderBuilder { context, header ->
        android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(android.widget.TextView(context).apply {
                text = header.title
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.Button(context).apply {
                text = "Minimize"
                setOnClickListener { header.onMinimize() }
            })
            addView(android.widget.Button(context).apply {
                text = "Close"
                setOnClickListener { header.onClose() }
            })
        }
    },
    footerBuilder = ArtemisChatFooterBuilder { context, footer ->
        android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(footer.attachmentPreview, android.widget.LinearLayout.LayoutParams(-1, -2))
            addView(android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(footer.input, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                footer.onAttach?.let { attach ->
                    addView(android.widget.Button(context).apply {
                        text = "+"
                        contentDescription = "Attach file"
                        setOnClickListener { attach() }
                    })
                }
                addView(android.widget.Button(context).apply {
                    text = "Send"
                    setOnClickListener { footer.onSend() }
                })
            })
        }
    },
    runtimeUserContext = com.google.gson.JsonObject().apply {
        addProperty("user_id", "customer-123")
        add("custom_attributes", com.google.gson.JsonObject().apply {
            addProperty("membership", "gold")
        })
    },
))
```

The header context provides the current title and minimize/close actions. The footer context provides the SDK-owned `EditText` (preserving draft and input behavior), an enabled `StateFlow<Boolean>`, the attachment preview view, and send/optional attach callbacks. Include the input in the returned hierarchy; include `attachmentPreview` when users need to see selected files. Collect `enabled` with the host lifecycle to keep custom controls in sync. Omit either builder to keep that SDK-provided view. `runtimeUserContext` uses the socket schema (`user_id` and `custom_attributes`) and overrides the connection context for this chat. The app module has a styled version in `CustomChatheaderFooter.kt`.

The optional host welcome message can also be provided at initialization with `ArtemisUIConfig.welcomeMessage`; Runtime `widgetConfig.welcomeMessage` takes precedence. `ChatOptions.runtimeUserContext` is per chat, so it can vary between chats opened from the same handle.

## Strings and localization

`ArtemisStrings` uses Android string resource names as keys. Missing keys fall back to Android resources. The host supplies translations either through overrides or matching resource names in `values-<language>`; no translation service or language download is used. Omit `localeTag` to follow the device locale. In an App Bundle, hosts selecting packaged resources for another language must make that language available in their app distribution.

```kotlin
handle.updateStrings(ArtemisStrings(
    localeTag = "en-IN",
    overrides = mapOf(
        "artemis_hint" to "Ask us a question…",
        "artemis_connecting" to "Connecting to support…",
        "artemis_upload_pending" to "Uploading file…",
        "artemis_upload_progress" to "Uploading file… %1\$d%%",
        "artemis_submit" to "Send feedback",
        "artemis_survey_success" to "Thank you!",
    ),
))
```

Formatting uses positional Android/Java format placeholders. An invalid format falls back to the resource string. Overrides replace the previous override map. Existing errors already stored in conversation state retain their text; new SDK errors use the current strings. Runtime placeholder/connecting text and explicitly supplied survey labels take precedence over local fallback strings. Assistant content, provider text and server error messages are not automatically translated.

YAML initialization is supported:

```yaml
artemis_ui_sdk:
  # Existing connection and feature configuration goes here.
  strings:
    locale: en-IN
    overrides:
      artemis_hint: "Ask us a question…"
      artemis_retry: "Reconnect"
  theme:
    assistant_name: Support
    header_background_color: "#14532D"
    header_text_color: "#FFFFFF"
    compose_bar_text_color: "#111827"
```

## Custom template renderers

Registration is optional and scoped to the handle. Names match `richContent` keys or canonical `renderables[].name`; built-in names can also be overridden. Register only the types you want to replace: for example, a `carousel` renderer replaces the carousel portion while other rich-content types keep their built-in renderers. Common names include `carousel`, `list`, `table`, and `quick_replies`. The existing protocol parser still decides which payloads become rich content. Canonical unknown renderables are the supported path for new template names.

Register renderers before opening chat. Return an unattached Android view for each payload type:

```kotlin
val customTemplateRegistrations = listOf(
    handle.registerTemplateRenderer("carousel", ArtemisTemplateRenderer(::renderCarousel)),
    handle.registerTemplateRenderer("list", ArtemisTemplateRenderer(::renderList)),
    handle.registerTemplateRenderer("table", ArtemisTemplateRenderer(::renderTable)),
    handle.registerTemplateRenderer("quick_replies", ArtemisTemplateRenderer(::renderQuickReplies)),
)
// Keep the AutoCloseable registrations for the handle lifetime. Close one to restore that built-in renderer.
```

Each `renderCarousel` / `renderList` / `renderTable` function receives an `ArtemisTemplateRequest`. Read that template's data from `request.payload`; use `request.submitAction` for bot actions, `request.loadImage` for rich-content images, and `request.openUrl` for links. The app module includes a complete example that registers carousel, list, and table renderers in `CustomTemplateInjection.kt`.

```kotlin
val registration = handle.registerTemplateRenderer("booking_summary") { request ->
    android.widget.Button(request.context).apply {
        text = "View booking"
        isEnabled = !request.actionLocked("view_booking")
        setOnClickListener {
            request.submitAction("view_booking", value = "booking-123")
        }
    }
}
// Call on the main thread to restore built-in/fallback rendering.
registration.close()
```

Return an unattached view. Returning null, throwing, or returning an already attached view falls back to built-in rendering, then canonical fallback text. `request.payload` and message JSON are detached copies. Renderers must not send requests while rendering; submit actions from user interaction. Actions go through existing connection validation and action locks. The request exposes custom form draft values/change/submit callbacks, feedback draft/submission state and callbacks, locked action state, and the active typography config. Custom templates can load images through `request.loadImage(url)`, which uses the SDK's bounded image loader and URL checks, and open links through `request.openUrl(url)`, which uses the SDK navigation policy. Form drafts use the SDK's bounded per-session draft cache. The SDK reuses views during scrolling and normal status/progress updates. Renderer registration and actual presentation changes invalidate affected presentation caches. Host renderers own their own view state and media/resource lifecycle and should use attach/detach listeners where needed.

## Native host APIs

All commands below run on the main thread and require an open handle. A chat must be opened before connecting; commands do not implicitly create sessions or queue work before a session exists.

| API | Behavior |
| --- | --- |
| `state` | Lifecycle-collectable `StateFlow<ChatUiState>` with detached message JSON, connection, transfer, upload progress and survey state |
| `branding` / `brandingUpdates` | Current resolved appearance / `StateFlow` when it changes; existing sealed event variants stay unchanged |
| `sendText(text, metadata)` | Existing text path; returns whether the transport accepted it locally |
| `sendComposedMessage(text)` | Existing composed text + ready attachments path; same five-file/policy checks |
| `uploadAttachments(uris)` | Stage and upload host-picked URI files under existing policy |
| `retryAttachment`, `cancelAttachmentUpload`, `removeAttachment` | Operate on a draft's `localId` |
| `resolveAttachmentUrl(messageId, attachmentId, callback)` | Explicitly resolve a received attachment using existing session-scoped download behavior |
| `retryConnection()` | Existing reconnect path, invoked explicitly by host |
| `submitAction`, `submitFeedback`, `submitTransferSurvey` | Existing validated submission paths and acknowledgement behavior |
| `endSession()`, `close()`, `showChat()`, `createChatFragment()` | Existing lifecycle controls |

Host action and feedback APIs use the local message ID exposed by `state`; feedback resolves the server ID internally. Snapshot publication reuses unchanged message copies to avoid copying the entire transcript during each upload/stream update. Hosts should treat snapshots as read-only and collect with their lifecycle. `sendText`/`sendComposedMessage` success is local transport acceptance, not proof of server or human-agent delivery. Explicit sending/upload/download/retry commands still make their normal existing requests.
