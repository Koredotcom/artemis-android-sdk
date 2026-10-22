# Artemis Android UI SDK

This repository contains the Android chat UI SDK and a sample host app:

- `:artemis_ui_sdk` is the reusable Android library.
- `:app` is a small host app that accepts runtime connection settings and opens the SDK chat screen.

The SDK uses the Artemis Socket SDK for connection management, streamed assistant responses, text messages, structured action submissions, and feedback submissions. The UI library renders chat messages, connection status, Markdown, cards, carousels, forms, quick replies, feedback controls, tables, charts, progress, and media links.

## Try the sample

Copy `sample-runtime.local.properties.example` to `sample-runtime.local.properties` and fill in your endpoint, project ID, public SDK key, channel ID, and chat title. The real properties file is ignored by Git. Alternatively, set environment variables `ARTEMIS_SAMPLE_ENDPOINT`, `ARTEMIS_SAMPLE_PROJECT_ID`, `ARTEMIS_SAMPLE_API_KEY`, `ARTEMIS_SAMPLE_CHANNEL_ID`, and `ARTEMIS_SAMPLE_CHAT_TITLE`; these override local properties.

Open the project in Android Studio, sync Gradle, run the `app` configuration, then tap **Open chat**. Without local values, the sample opens with no configured Runtime. These settings are used only by the sample application; the SDK libraries receive configuration from their host.

## Chat layouts and default templates

The chat screen uses Android XML resources in `artemis_ui_sdk/src/main/res/layout`:

- `artemis_fragment_chat.xml`: header, connection status, message list, empty state, typing, retry, and composer.
- `artemis_chat_header.xml`, `artemis_chat_composer.xml`, and `artemis_chat_empty.xml`: reusable screen components.
- `artemis_message_row.xml`: message bubble, rich-content container, and timestamp.
- `artemis_template_*.xml`: cards, action buttons, quick-reply/feedback chips, images, list items, KPI, form fields, and linear/circular progress.

`DefaultRichTemplateRenderer.kt` binds server payloads to the native templates. `ArtemisDefaultTemplates.types` lists supported keys in rendering order: Markdown, carousel, image, HTML, video, audio, file, list, KPI, table, chart, form, progress, feedback, and quick replies. Structured actions are rendered separately. Adaptive Card, Slack, AG-UI, and WhatsApp payloads currently use readable text fallbacks, not full native channel renderers.

Debug builds write each completed assistant response to Logcat under the `ArtemisUI` tag, including its message ID, text, rich content, and actions. Filter with `adb logcat -s ArtemisUI:D`. Release builds omit response-body logging.

Variable-length content (table rows, form fields, carousel cards) is added dynamically inside the XML containers. Light/dark resource palettes follow the configured theme mode for the chat activity; an embedded fragment follows its host activity.

### Offline template preview (debug only)

Build and install the debug sample, then open the local gallery:

```shell
adb shell am start -n com.artemis.uisdk.sample/com.artemis.uisdk.chat.TemplatePreviewActivity
```

Add `--ez dark true` for dark mode or `--ei position 9` to jump to a later message. This screen uses the production adapter and layouts with local fixtures. It does not initialize the SDK or send requests. The preview activity is excluded from release builds.

## Host integration

Add JitPack to your host project's dependency repositories:

```groovy
maven { url = uri("https://jitpack.io") }
```

The current JitPack release is `1.0.2`. Add the dependency:

```groovy
implementation 'com.github.Koredotcom:artemis-android-sdk:1.0.2'
```

The UI and socket libraries are published together; the sample app is not a Maven artifact. The socket dependency is included transitively, so no separate socket dependency is required in the host app.

Add the UI artifact to a host app and initialize it with its runtime configuration:

```kotlin
val handle = ArtemisUI.initialize(
    applicationContext,
    ArtemisUIConfig(
        connection = ArtemisConnectionConfig(
            endpoint = "https://runtime.example.com",
            projectId = "your-project-id",
            apiKey = suppliedApiKey,
            channelId = "your-channel-id",
        ),
    ),
)
handle.showChat(this, ChatOptions(title = "Support"))
```

Alternatively, use `handle.createChatFragment()` in a `FragmentActivity` container. Close the handle when the host no longer needs it.

The YAML loader accepts `sdk_configurations.yaml` under the `artemis_ui_sdk` or `artemis_ui_plugin` root key. An optional environment override uses the sibling filename `sdk_configurations.<environment>.yaml` and recursively overrides the base configuration.

## Socket dependency status

The sample builds against the local socket and UI modules together. The current release packages both artifacts at version `1.0.2`; use the aggregate dependency shown above in host apps.

## Build

```shell
bash gradlew :artemis_ui_sdk:assembleRelease :app:assembleDebug
```

The repository targets Android API 24+, compiles against API 36, and uses Java 17.

## JitPack release

`jitpack.yml` selects Java 17 and stages both `:artemis_socket_sdk` and `:artemis_ui_sdk`. The current published tag is `1.0.2`; JitPack supplies that tag through its `VERSION` environment variable so both artifacts share the release version. Local builds without `VERSION` still default to `ui-0.0.1`. The release includes AARs, dependency metadata, and sources JARs.

Before tagging, run:

```shell
bash gradlew :artemis_ui_sdk:assembleRelease :artemis_ui_sdk:lintRelease :artemis_ui_sdk:publishToMavenLocal :app:assembleDebug
```

For a future release, publish a version tag and confirm the JitPack build succeeds before distributing its dependency coordinate. Keep the UI and socket artifact versions aligned.

Release lint disables only `NullSafeMutableLiveData` because the Lifecycle 2.9
detector crashes with AGP 8.7's Kotlin analysis API; this SDK uses StateFlow and
contains no MutableLiveData. Other release lint checks remain enabled.

## Optional attachments and human-agent chat

The sample enables attachments and transferred-agent chat. Host apps can opt in with
`ArtemisFeatureConfig(attachments = true, contactCenter = true)`. The attachment picker requires Runtime branding to enable `widgetConfig.theme.attachmentButtonEnabled` and the session to grant `attachment:write`. Up to five files can be sent per message. See the [attachments and transferred-agent guide](docs/attachments-contact-center.md) for configuration and behavior.

### Customizing the chat UI

The host can supply bundled fonts, replace the header or composer, and register renderers for selected rich-content types. Pass fonts through `ArtemisTypographyConfig`, register templates on the handle, and pass header/footer builders in `ChatOptions`:

This Activity example assumes a configured `connection` and host renderer functions (`renderCarousel`, `renderList`, `renderTable`, and `renderQuickReplies`). Imports are omitted for readability.

```kotlin
val handle = ArtemisUI.initialize(
    applicationContext,
    ArtemisUIConfig(
        connection = connection,
        theme = ArtemisThemeConfig(primary = "#0F766E"),
        typography = ArtemisTypographyConfig(
            typeface = ResourcesCompat.getFont(applicationContext, R.font.brand_sans),
            textSizeScale = 1.05f,
        ),
        welcomeMessage = "Welcome! How can we help?",
    ),
)

// Define renderCarousel / renderList / renderTable / renderQuickReplies to
// return unattached Views built from each request.payload.
val templateRegistrations = listOf(
    handle.registerTemplateRenderer("carousel", ArtemisTemplateRenderer(::renderCarousel)),
    handle.registerTemplateRenderer("list", ArtemisTemplateRenderer(::renderList)),
    handle.registerTemplateRenderer("table", ArtemisTemplateRenderer(::renderTable)),
    handle.registerTemplateRenderer("quick_replies", ArtemisTemplateRenderer(::renderQuickReplies)),
)

handle.showChat(this, ChatOptions(
    title = "Support",
    headerBuilder = ArtemisChatHeaderBuilder { context, header ->
        android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(android.widget.TextView(context).apply { text = header.title },
                android.widget.LinearLayout.LayoutParams(0, -2, 1f))
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
            addView(footer.attachmentPreview,
                android.widget.LinearLayout.LayoutParams(-1, -2))
            addView(android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(footer.input,
                    android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                footer.onAttach?.let { attach ->
                    addView(android.widget.Button(context).apply {
                        text = "+"
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

Use a font in the host app's `res/font/` resources and refer to its generated ID, such as `R.font.brand_sans`. A template renderer replaces only its matching `richContent` type; unregistered types keep the SDK renderer. In a renderer, use `request.submitAction(...)` for bot actions and `request.loadImage(...)` / `request.openUrl(...)` for images and links. Keep the returned registrations if you want to close them later and restore a built-in renderer. The custom footer receives the SDK-owned input and attachment preview; collect `footer.enabled` with the host lifecycle to keep send controls in sync. The sample app contains full carousel, list, and table renderers in [CustomTemplateInjection.kt](app/src/main/java/com/artemis/uisdk/sample/CustomTemplateInjection.kt) and custom chrome in [CustomChatheaderFooter.kt](app/src/main/java/com/artemis/uisdk/sample/CustomChatheaderFooter.kt).

Other host options include `ArtemisThemeConfig` / `updateTheme`, `ArtemisStrings` / `updateStrings`, a local `ArtemisLogoResolver`, and per-chat `ChatOptions.runtimeUserContext`. See [customization and host APIs](docs/customization-and-host-apis.md) for precedence, localization, renderer callbacks, and complete examples.

### Advanced feedback

Advanced assistant surveys are opt-in with `features.advancedFeedback = true` (enabled in the sample). See [advanced feedback](docs/advanced-feedback.md) for supported payloads and host APIs.

### Service authorization

Opt in with `features.authentication = true` (enabled in the sample) for preflight/JIT authorization cards, Android system-browser or host-owned sign-in launch, explicit check/cancel, and Runtime-confirmed states. See [authentication workflows](docs/authentication-workflows.md) for host APIs and mobile behavior.
