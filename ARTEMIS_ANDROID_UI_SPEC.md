# Artemis Android UI SDK Draft Specification

Status: Draft for user review. Development is not authorized until the user confirms this specification. Prepared 6 October 2026.

The objective is an Android-native UI SDK with the behavior and predefined templates of the supplied Flutter Artemis UI SDK. A host application adds one UI dependency, supplies connection configuration, and opens or embeds chat. The existing Artemis Android Socket SDK owns authentication, network connections, socket lifecycle, and reconnection.

Full parity has a dependency constraint: the inspected Android Socket SDK supports text sending and raw incoming messages, but lacks public structured action and feedback submission APIs. Those capabilities must be supplied by the Socket SDK before interactive template parity can be accepted. Sending action JSON through its text API would produce a chat message, not an action frame.

## Scope and approval boundary

This draft specifies the proposed implementation, acceptance criteria, and unresolved dependency decisions. The attached prompt is requirements material; its instruction to implement does not override the user's request to review a spec first.

The proposed deliverables are a Kotlin UI library, sample Android application, configuration and customization APIs, predefined renderers, integration documentation, and a verified Flutter parity checklist. Development, dependency changes, Socket SDK changes, publication, and replacement of existing modules have not started.

Recommended scope is full Flutter behavioral parity, delivered in stages. Transport gaps remain explicit dependencies rather than silently omitted features. Approval of this UI specification alone does not authorize changing the separate Socket SDK repository or publishing artifacts.

## Reference baseline

| Reference | Baseline and role |
| --- | --- |
| Flutter UI project | `/Users/SudheerJampana/Desktop/abl-platform-mobile/Flutter/artemis_plugin`, HEAD `198a25190603e63c38a10a54b680497335ec4af3`; package `artemis_flutter_ui_sdk`, published name `artemis_ui_plugin`, version `1.0.0` |
| Android Socket SDK | `/Users/SudheerJampana/Desktop/abl-platform-mobile/Android/artemis_socket_sdk`, HEAD `805bc4cea542e6827541554f1623616dcc48cd1b` |
| Requested socket release | `com.github.Koredotcom:android-kore-sdk:as-0.0.1`; local tag resolves to `8e1f89ea8723593e59022b491e437a19efa20a83`. The library directory has no diff between that tag and inspected HEAD. Published artifact resolution remains unverified. |
| Current Android UI workspace | Existing `app`, `korebot`, and `korebotsdklib` modules use the older bot-ID/JWT-oriented integration and contain their own networking. They are not the new Artemis transport adapter. |

The supplied GitHub branch page could not be retrieved, so socket API findings below are based on the local checkout and local tag comparison. The installed Flutter socket package version and wire behavior must be pinned during implementation validation; the UI package declares `artemis_socket_plugin: ^1.0.0`.

## Flutter reference behavior

### Public integration and structure

`AgentChatUI.open` launches `AgentChatScreen`, accepting explicit configuration or an environment/config asset path, runtime user context, title, header/footer builders, a template registry, and fonts. `demoApp` is a convenience for demonstrations. The package exports configuration, rich models, theme helpers, template APIs, and underlying socket APIs. Its platform-version method is plugin scaffolding, not a chat requirement.

The main directories separate configuration loading, rich-content/action models, screen orchestration, theme resolution, widgets, and template renderers. The example demonstrates configuration and custom header, footer, and template implementations.

### Initialization and session flow

1. Use explicit configuration or load YAML assets and environment overrides.
2. Validate project ID, endpoint, and exactly one of API key or bootstrap token. Channel requirements depend on the runtime.
3. Resolve initial UI theme, apply runtime user context, and create `AgentSDK`.
4. Attach chat and SDK event listeners before connecting.
5. Connect, refresh widget-derived theme, and obtain messages from the socket SDK.
6. React to received messages, stream start/chunk/end, typing, errors, connected/reconnecting/disconnected events.
7. Dispose event subscriptions, connectivity monitoring, controllers, and the SDK when the screen is disposed.

The screen maintains messages, connection state, typing state, scroll behavior, form drafts, submitted forms/ratings, and used action keys. Both default minimize and close currently pop the screen; minimize does not preserve a background chat window.

### Rendering and interaction

Messages support text, Markdown, carousel cards, rich templates, actions, and metadata-based user display labels. Rich payloads are read from `metadata.richContent` or `metadata.rich_content`. The ordered registry supports multiple matches, explicit replacement of a renderer, merging defaults with custom renderers, and skipping extraction failures. Carousel and Markdown rendering also exist outside the registry.

Text sends through `sendMessage`. Carousel buttons and quick replies use structured actions. Forms submit action ID `form-submit`, encoded values, and form data. General actions may carry `renderId`. Feedback submits the message ID, rating type/value, and optional text. Used buttons, quick replies, and feedback are guarded against repeated submission; button/feedback failures release their locks. The Android proposal also restores form editability after failed submission, improving the reference's form failure behavior.

The composer requires a connection and internet availability. The reference provides empty, connecting, disconnected, typing, initialization-error, and offline presentations, with transient send/action errors. Incoming content updates the existing message list and respects proximity to the bottom when scrolling.

The default footer does not wire its attachment callback. Voice/storage/security configuration fields must not be interpreted as evidence of implemented UI features. Complete uploads, microphone recording, speech recognition, push notifications, and the old Android SDK's extra templates are outside Flutter parity unless separately requested.

## Android Socket SDK contract and gaps

| Capability | Verified local API or behavior | UI design consequence |
| --- | --- | --- |
| Configuration | `ArtemisSocketConfiguration.builder()` with endpoint, projectId, apiKey, channelId/channelName, reconnection options | Map validated UI connection configuration to this builder |
| Authentication | API-key bootstrap handled internally; API key is required | Bootstrap-token and runtime-user-context parity require a socket capability extension |
| Connect | `ArtemisSocketClient(configuration, listener).connect()` | Subscribe before connecting; readiness follows the connected callback |
| Lifecycle | `disconnect()`, `shutdown()`, `isConnected()`, `getSessionId()` | UI owns client lifetime; terminal disposal calls shutdown |
| Text send | `sendMessage(String)` wraps text into `chat_message` with generated ID/session ID | Do not use it for action or feedback frames; local acceptance is not server delivery |
| Incoming data | `onMessage(String payload)` forwards raw JSON frames | UI adapter normalizes presentation data and filters control frames |
| Events | `onConnecting`, `onConnected(sessionId)`, `onDisconnected(reason)`, `onError(Throwable)`, `onLog` | Map to UI state/events; logs are not a stable protocol API |
| Threading | Listener callbacks posted to Android main thread; network bootstrap runs on an executor | Keep callbacks light; serialize parsing/state updates and return UI events on main |
| Reconnection | Socket SDK owns bounded exponential retry policy | Do not add a competing UI retry loop |
| Actions and feedback | No public `submitAction` or `submitFeedback`, and no generic structured send API | Blocking dependency for interactive parity |
| Message store and history | No public typed message store or history retrieval API | UI owns in-memory presentation state; remote history/resume parity requires verified transport support |
| Widget configuration | No public widget-configuration accessor | Runtime branding parity requires a documented payload or socket accessor |

Required socket follow-up contract: typed structured action submission, feedback submission, explicit send acceptance/failure, bootstrap-token and user-context support where needed, and documented incoming message/chunk/typing/history/widget payloads. Exact API names belong to a separate Socket SDK change proposal. This UI must not use reflection, fork private transport code, or open another socket to bypass the missing APIs.

## Proposed Android architecture

Use a new Kotlin `artemis_ui_sdk` library with native Android Views, XML layouts, RecyclerView, ViewModel, and StateFlow. Provide a full-screen Activity and an embeddable Fragment. This supports existing View-based host applications and custom view factories without requiring Compose. A first-class Compose API is outside this proposal; hosts can embed the Fragment through Android interop.

```text
Host application
  ArtemisUI / ChatSession / configuration / customization
    Chat Activity or Fragment
      ChatViewModel and immutable ChatUiState
        Message store and template registry
        Socket adapter
          Published Artemis Android Socket SDK
            Backend
```

Keep these as packages in one published UI library initially: `api`, `config`, `session`, `transport`, `model`, `chat`, `template`, `theme`, and `media`. Package separation is sufficient without multiplying published artifacts.

The adapter invokes only public socket APIs and converts raw callbacks into UI-domain events. It may interpret incoming presentation payloads; authentication, session handshakes, protocol acknowledgements, retry scheduling, and network requests remain Socket SDK responsibilities.

Create a separate `artemis_ui_sample` module. Preserve existing modules and APIs initially; the new UI/sample must not depend on `korebot` or `korebotsdklib`. Removal or migration of those legacy modules requires a later explicit decision. Reuse isolated visual resources only after checking their dependencies and behavior.

## Proposed host API

The following names are proposed contracts, not APIs already available in the repository.

```kotlin
val artemis = ArtemisUI.initialize(
    applicationContext,
    ArtemisUIConfig(
        connection = ArtemisConnectionConfig(
            endpoint = runtimeEndpoint,
            projectId = projectId,
            apiKey = apiKey,
            channelId = channelId
        ),
        theme = ArtemisTheme(),
        features = ArtemisFeatures()
    )
)

artemis.showChat(activity, ChatOptions(title = "Support"))

// Alternative integration: add this Fragment using the host FragmentManager.
val fragment = artemis.createChatFragment(ChatOptions(title = "Support"))
```

`initialize` validates configuration and returns an application-scoped handle without opening a connection. Opening/embedding creates a session and connects when its UI is ready. A session has an explicit close/dispose operation. Customizations are scoped to the handle/session and do not mutate a global registry. Java-callable builders/factories will complement Kotlin defaults.

Expose typed host events for connection changes, visible message updates, errors with recoverability, close/minimize, and link/action handling where appropriate. Listener registration returns a removal handle; events run on main. Hosts do not need to consume raw socket events. An optional session state stream can support advanced integrations without exposing the socket client.

For bootstrap tokens and runtime user context, reject unsupported configurations with a clear capability error until the dependency supports them. Do not accept fields that are silently ignored.

## Configuration and customization

Support explicit Kotlin/Java configuration plus YAML assets with recursive environment overrides. Preserve the Flutter configuration's meaningful field semantics; unsupported fields must be documented and reported. Maintain a field mapping in integration documentation.

Required initial connection values are endpoint, projectId, API key, and channelId or channelName, matching the inspected Android builder. API-key and bootstrap-token alternatives become mutually exclusive when both modes are supported. Validate URL shape and missing values before connecting. Credentials stay out of saved UI state, sample source, and logs.

Expose theme colors for background/header/composer, user and assistant bubbles/text, accents/borders/errors, fonts and typography, bubble radius, spacing, and icons. Provide custom header/footer view factories and default builders, with callbacks and composer state. Template renderers receive data, theme, and typed actions rather than socket internals.

Proposed precedence is defaults, configured theme, supported runtime widget theme, then explicit host overrides/fonts. Capture fixture-based comparisons with Flutter's `ChatThemeData.resolve`; any precedence difference must be documented. Until runtime widget configuration is available, configuration and host overrides provide branding.

## Feature parity and template acceptance

Every row is required for full parity unless marked conditional. Render-only completion does not satisfy an interactive row. Each renderer requires valid, empty, malformed, narrow-screen, and large-text fixtures.

| Feature | Required Android behavior | Dependency or acceptance note |
| --- | --- | --- |
| Chat shell | Header, status, list, composer, timestamps, user/assistant distinction | Full-screen and embedded layouts |
| Text and Markdown | Text, links, formatting, code, rich Markdown without duplicated identical text | Preserve user display labels |
| Streaming and typing | Update messages by stable ID; show/clear typing on corresponding events | Incoming event schema must be verified |
| Carousel | Horizontal cards, title/subtitle/image, URL and button actions | Buttons require structured submission |
| `image` | Image, caption/alt text, loading/failure handling | Safe URL handling |
| `html` | Supported static HTML, links and graceful fallback | No unrestricted script execution |
| `video` and `audio` | Playback, loading/error, lifecycle cleanup | Native media components |
| `file` | Filename/metadata and open action | External handler failure shown clearly |
| `list` | Item text, images and destination links | Match reference action semantics |
| `kpi` | Label/value/unit/trend/icon | Compact presentation where appropriate |
| `table` | Columns, alignment, rows, visible-row limit and expansion | Reference default visible-row limit is 10 |
| `chart` | Bar, line and pie presentations with labels/colors | Reference model caps data at 100 points |
| `form` | Supported text/number/date/selection controls, required validation, drafts and submit | Exact supported field aliases from fixtures; structured submit required |
| `progress` | Label/value/max and supported variants | Handle invalid numeric values gracefully |
| `feedback` | Rating choices and optional feedback text; submitted state | Dedicated feedback transport required |
| `actions` | Action controls, actionId/value, optional renderId/formData | Lock per message/action, unlock on failure |
| `quick_replies` | Reply options, icon/label, one choice per message | Structured action; unlock on failure |
| `channel_fallback` | Labeled structured-text preview | Adaptive Card, Slack, AG UI and WhatsApp are previews, not full channel runtimes |
| Custom templates | Ordered registration, default override and extensions | Preserve unknown payload data; renderer failures do not crash chat |
| Empty/loading/error/offline | Clear states and actionable retry where appropriate | Never show connected solely because internet is available |
| History | Session-local messages and reconnect de-duplication | Remote retrieval/persistence conditional on socket support and approved scope |
| Theme and builders | Theme/fonts, replaceable header/footer and renderers | Runtime widget branding depends on transport access |
| Attachment/voice controls | Custom footer extension point | Complete upload/voice workflows excluded from default parity |

Wire-compatible form encoding and feedback rating mappings must match the reference helpers, with fixture tests. Unknown schema variants should retain a readable fallback and report a diagnostic; they must not generate fabricated socket actions.

## State and lifecycle contract

Use immutable connection states `Idle`, `Connecting`, `Connected`, `Reconnecting`, `Disconnected`, and `Failed`. Reconnecting may only be represented when the adapter has reliable evidence; do not parse diagnostic log strings to infer it. Track offline status separately from server connection status.

Store message IDs, role, display text, timestamp, rich payload, stream status, and local send status. Preserve raw extensions for custom templates. Text accepted by the socket is not marked server-delivered without acknowledgement evidence. Do not automatically resend uncertain messages after reconnection.

The ViewModel retains messages, composer draft, form drafts, scroll intent and submission guards across rotation. It owns one session; view recreation must not reconnect or duplicate listeners. Back/close/minimize terminate the displayed session, matching Flutter's current navigation behavior. Reopening starts a new UI session unless a separately approved resume feature is added.

On terminal close, cancel UI work, detach consumers, shut down the client, and release media players. Ignore late callbacks using a closed-session guard. Backgrounding pauses media and UI collection; no background service or automatic background message delivery is promised. Process death reconnects from fresh host configuration and must not restore credentials from saved state.

Failures retain user drafts and restore failed action/form/feedback controls. Pending controls reject duplicate taps. Connection errors include a safe user message and diagnostic category; credentials and raw sensitive payloads are not shown by default.

## Build and dependency design

The UI module declares the requested socket coordinate as an implementation dependency. Hosts add only the UI artifact; its published metadata must carry the socket and UI runtime dependencies. Final UI Maven coordinates and version remain a release decision.

The inspected socket source uses minSdk 24, compile/target 36 and Java 17. Propose minSdk 24 for the new UI and sample, with compileSdk 36 and a compatible validated build toolchain. The existing workspace uses AGP 8.1.4 and Kotlin 1.9.0; compatibility must be checked before selecting upgrade versions. Existing module SDK targets should not be changed incidentally.

Use AndroidX lifecycle/Fragment/RecyclerView, Material components, coroutines/StateFlow, and focused media/image/Markdown/YAML dependencies where needed. Flutter `connectivity_plus` maps to Android network observation; `url_launcher` to Android intents; audio/video packages to native media support; custom-painted charts can use Canvas. Select and pin third-party versions during implementation after compatibility review. Do not retain old networking libraries solely through the legacy UI modules.

Validate the requested JitPack artifact and transitive module structure first. A local source tag match does not prove that the published AAR resolves or contains the expected classes. Check dependency graphs and duplicate classes in a host that consumes the packaged UI, not just project dependencies.

## Validation and completion criteria

1. Build debug and release UI artifacts and the sample, run lint, and verify a clean host can consume the published-style UI dependency with its transitive socket dependency.
2. Test configuration validation/overrides, message normalization, streaming ordering, duplicate frames, unknown fields, registry ordering/overrides, and exact action/form/feedback payload mapping.
3. Exercise connecting, authentication failure, network loss/recovery, retry exhaustion, manual retry, close during connection, rotation, background/foreground, and reopening without duplicate clients or callbacks.
4. Verify local sends are not misrepresented as server-delivered, and repeated taps/reconnects do not duplicate submissions.
5. Render the same payload fixtures on Flutter and Android for every parity row; compare content, behavior, order, state, and themes. Native typography need not be pixel-identical.
6. Test API 24 and a current supported Android version, keyboard/insets, dark/light themes, TalkBack, large fonts, RTL, media cleanup, and malformed template resilience.
7. Run real backend checks for text, streamed replies, actions, forms, feedback, authentication modes, and history/widget behavior wherever claimed. Mock fixtures alone cannot establish transport parity.
8. Deliver an installation README, API/configuration reference, customization/template examples, lifecycle guidance, sample app, dependency explanation, and completed parity matrix with every exception explicit.

The sample must provide configuration placeholders, launch and embed examples, custom theme/header/footer/template examples, and a fixture gallery for deterministic template review. Live mode must demonstrate real transport behavior independently of that gallery.

## Implementation stages after approval

1. Verify artifact/API compatibility and agree on Socket SDK gaps. Pin reference versions and fixtures. Resolve full-parity blockers before claiming an implementation-ready release scope.
2. Create the Kotlin library/sample structure, public configuration facade, lifecycle ownership, and adapter using the verified socket APIs.
3. Implement text chat, raw-event normalization, UI state, themes, and lifecycle behavior.
4. Implement the complete template inventory and interaction parity as required transport capabilities become available.
5. Validate integration, accessibility, packaging, backend behavior and documentation against the acceptance criteria.

These are delivery stages, not a reduction of the approved scope. No effort estimates are included.

## Decisions for review

| Decision | Recommendation |
| --- | --- |
| Native UI stack | Kotlin with Android Views, Activity and Fragment |
| Existing Android modules | Preserve them; introduce independent new UI/sample modules |
| Parity target | Full Flutter behavioral parity, including structured actions and feedback |
| Socket limitations | Resolve through a separately authorized Socket SDK extension/release; do not duplicate transport in the UI |
| Strict `as-0.0.1` constraint | If immutable, explicitly approve a reduced scope with disabled/unavailable interactive features; it cannot satisfy full parity as inspected |
| Minimum Android version | API 24 based on the inspected socket dependency |
| YAML support | Include alongside programmatic configuration |
| Close/minimize | Close and dispose, matching current Flutter behavior |
| Uploads, voice, push and legacy extras | Exclude unless separately requested |

User confirmation should state whether the recommended architecture and scope are accepted and whether the Socket SDK may be extended separately or the UI must remain strictly limited to `as-0.0.1`. Development remains paused until that confirmation.

## Source map

Flutter paths are relative to `artemis_plugin/artemis_flutter_ui_sdk`:

- `README.md`, `pubspec.yaml`, `lib/artemis_ui_plugin.dart`: package and public exports.
- `lib/src/config/sdk_configuration_loader.dart`: YAML loading, merging and validation.
- `lib/src/ui/agent_chat_ui.dart`, `agent_chat_screen.dart`: host entry point, lifecycle, events and submission behavior.
- `lib/src/models/rich_content.dart`, `message_actions.dart`, `message_rich_content.dart`: models, limits and metadata extraction.
- `lib/src/ui/templates/rich_template_registry.dart` and `lib/src/ui/widgets/templates/default_rich_template_registry.dart`: extension contract and built-ins.
- `lib/src/ui/widgets/message_bubble.dart`, `carousel_message.dart`, `chart_template.dart`, `templates/*`: renderer behavior.
- `lib/src/ui/theme/chat_theme_data.dart`, `chat_header_builder.dart`, `chat_footer_builder.dart`: branding and UI extension points.

Android socket paths are relative to `artemis_socket_sdk/BotsSDK`:

- `artemis_socket_sdk/src/main/java/artemis/socket/ArtemisSocketConfiguration.java`: required configuration and retry policy.
- `artemis_socket_sdk/src/main/java/artemis/socket/ArtemisSocketClient.java`: public operations, message framing, callback threading and reconnection.
- `artemis_socket_sdk/src/main/java/artemis/socket/ArtemisSocketListener.java`: callbacks.
- `artemis_socket_sdk/build.gradle`: Android requirements and dependencies.
