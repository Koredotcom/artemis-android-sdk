# Artemis Android UI SDK

This repository contains the Android chat UI SDK and a sample host app:

- `:artemis_ui_sdk` is the reusable Android library.
- `:app` is a small host app that accepts runtime connection settings and opens the SDK chat screen.

The SDK uses the Artemis Socket SDK for connection management, streamed assistant responses, text messages, structured action submissions, and feedback submissions. The UI library renders chat messages, connection status, Markdown, cards, carousels, forms, quick replies, feedback controls, tables, charts, progress, and media links.

## Try the sample

Open the project in Android Studio, sync Gradle, and run the `app` configuration. Assign the endpoint, project ID, API key, channel ID, and chat title strings in `MainActivity`, then tap **Open chat**.

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

Only the UI library is published; the sample app is not a Maven artifact. The
socket dependency is included transitively, so no separate socket dependency is
required in the host app.

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

The current dependency is `com.github.Koredotcom:artemis-android-sdk:socket-0.0.1`, declared in `artemis_ui_sdk/build.gradle`.

## Build

```shell
bash gradlew :artemis_ui_sdk:assembleRelease :app:assembleDebug
```

The repository targets Android API 24+, compiles against API 36, and uses Java 17.

## JitPack release

`jitpack.yml` selects Java 17 and publishes only `:artemis_ui_sdk`. The publication
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
