# Step 4: Android presentation and host extension points

## Scope

This increment adds local UI configuration and host integration. Branding uses the existing, token-free `widgetConfig` snapshot from SDK init and token refresh. It introduces no endpoint, request schema, socket dependency, refresh timer, or server configuration change. Theme/string/renderer updates do not reconnect, reload history, upload files, or submit actions.

Existing integrations require no new configuration. Attachment upload visibility still requires all three: the host attachment feature, branding `theme.attachmentButtonEnabled`, and `attachment:write`. The five-file limit and 40dp composer buttons/input minimum height remain in effect.

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
val config = ArtemisUIConfig(connection = connection, typography = typography)
// An open handle can also apply a new typography configuration at runtime.
handle.updateTypography(typography)
```

`theme.font_family` in YAML maps to an Android system family name. Use `ArtemisTypographyConfig` for fonts bundled by the host application.

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
        android.widget.Button(context).apply {
            text = header.title
            setOnClickListener { header.onClose() }
        }
    },
    footerBuilder = ArtemisChatFooterBuilder { context, footer ->
        android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(footer.input, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.Button(context).apply {
                text = "Send"
                setOnClickListener { footer.onSend() }
            })
        }
    },
))
```

The header context provides the current title and minimize/close actions. The footer context provides the SDK-owned `EditText` (preserving draft and input behavior), an enabled `StateFlow<Boolean>`, the attachment preview view, and send/optional attach callbacks. A custom footer can collect `enabled` to update its controls. Add `attachmentPreview` to the returned view if the host wants attachment drafts displayed. `ChatOptions.runtimeUserContext` optionally overrides connection user context for that chat, using the socket schema (`user_id` and `custom_attributes`). The app module demonstrates both replacements in `CustomChatheaderFooter`.

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

Registration is optional and scoped to the handle. Names match `richContent` keys or canonical `renderables[].name`; built-in names can also be overridden. The existing protocol parser still decides which payloads become rich content. Canonical unknown renderables are the supported path for new template names.

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

## Build checks for this increment

- Sample debug APK and release SDKs build.
- UI and socket release lint complete; pre-existing warnings remain.
- Existing UI/sample unit-test sources compile against the additive APIs.
- No unit tests or live/device checks run as part of this implementation request.

Suggested acceptance checks before releasing: open/minimize/reopen, text/streaming, scrolling rich cards, five-file upload/retry, branding-disabled attachments, token refresh with unchanged/changed/removed branding, string fallback and invalid format, custom renderer success/null/exception/unregister, unsent native form values during a theme update, and transfer survey submission. Live contact-center/backend acceptance remains separate from this local UI increment.
