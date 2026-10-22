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

Once the `ui-0.0.1` tag has been published successfully on JitPack, add:

```groovy
implementation 'com.github.Koredotcom:artemis-android-sdk:ui-0.0.1'
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

The UI and sample now use local project dependencies so source changes are exercised together: `:app` → `:artemis_ui_sdk` → `:artemis_socket_sdk`. The previous published baseline was `socket:1.0.1` / `ui:1.0.1`. Release both updated modules together before distributing the new SDK. See [steps 1–5 implementation and regression checks](docs/feature-parity-milestone-1.md).

## Build

```shell
bash gradlew :artemis_ui_sdk:assembleRelease :app:assembleDebug
```

The repository targets Android API 24+, compiles against API 36, and uses Java 17.

## JitPack release

`jitpack.yml` selects Java 17 and stages both `:artemis_socket_sdk` and `:artemis_ui_sdk`. The publication
version follows JitPack's `VERSION` environment variable, defaulting locally to
`ui-0.0.1`. The release includes an AAR, dependency metadata, and a sources JAR.

Before tagging, run:

```shell
bash gradlew :artemis_ui_sdk:assembleRelease :artemis_ui_sdk:lintRelease :artemis_ui_sdk:publishToMavenLocal :app:assembleDebug
```

Review and commit the release files, then create and push the `ui-0.0.1` tag on
that commit. Look up `Koredotcom/artemis-android-sdk` and `ui-0.0.1` on JitPack and
confirm the build succeeds before distributing the dependency coordinate.

Release lint disables only `NullSafeMutableLiveData` because the Lifecycle 2.9
detector crashes with AGP 8.7's Kotlin analysis API; this SDK uses StateFlow and
contains no MutableLiveData. Other release lint checks remain enabled.

## Optional attachments and human-agent chat

The sample enables the attachment and transferred-agent UI. SDK consumers opt in with
`ArtemisFeatureConfig(attachments = true, contactCenter = true)`; existing defaults keep both disabled.
The native picker and socket send API allow at most **five files per message**, with per-file progress, cancellation, retry and removal.
The picker button stays hidden until Runtime branding explicitly enables `widgetConfig.theme.attachmentButtonEnabled` and the session grants `attachment:write`; a socket connection alone does not show it.
The increment includes streamed uploads with progress/cancel/retry, scoped downloads, transfer text/typing,
external forms and dedicated post-transfer survey acknowledgements. Agent calls/video/screen sharing remain later work.

Run `bash gradlew verifyParityBaseline` to build, lint and run the complete SDK/sample regression gate.
See [configuration, behavior and live acceptance scenarios](docs/attachments-contact-center.md), including the
Runtime replay requirement for surveys missed while offline. Publish both modules at the same release version.

### Runtime appearance, strings and host renderers

Step 4 adds branding from existing SDK init/refresh responses, `ArtemisStrings`, runtime theme updates, local logo resolvers, custom template renderers, and native host commands/state. No additional branding API call is introduced. See [customization and host APIs](docs/customization-and-host-apis.md) for supported fields, precedence, examples and validation scope.

Hosts can replace the native header and composer per chat with `ChatOptions.headerBuilder` and `ChatOptions.footerBuilder`. The sample app uses custom views for both. See [customization and host APIs](docs/customization-and-host-apis.md#custom-chat-header-and-footer) for the builder contract.

Android also supports host typography (`ArtemisTypographyConfig`), a per-chat `runtimeUserContext`, and the Runtime `widgetConfig.welcomeMessage` empty-state message. Custom template requests include form draft and submission callbacks plus feedback state and callbacks. See [customization and host APIs](docs/customization-and-host-apis.md) for examples.

### Advanced feedback and remaining parity

Advanced assistant surveys are opt-in with `features.advancedFeedback = true` (enabled in the sample). See [advanced feedback](docs/advanced-feedback.md) for payloads and host APIs, and the [remaining parity plan](docs/pending-parity-plan.md) for the staged delivery sequence and acceptance criteria.

### Native template field coverage

See [native template parity](docs/template-parity.md) for form/action submissions, optional presentation fields, Markdown/channel fallbacks, offline gallery examples, native bounds and acceptance status.

### Service authorization

Opt in with `features.authentication = true` (enabled in the sample) for preflight/JIT authorization cards, Android system-browser or host-owned sign-in launch, explicit check/cancel, and Runtime-confirmed states. See [authentication workflows](docs/authentication-workflows.md) for host APIs, mobile return behavior, Runtime contract compatibility and pending live acceptance checks.
