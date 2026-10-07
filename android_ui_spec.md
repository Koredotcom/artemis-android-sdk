# Artemis Android UI SDK Specification

Version 1.1 — 6 October 2026 — implementation baseline.

This is a self-contained specification for an Android chat UI SDK and sample host application. It can be implemented without an attached prompt, Flutter project, screenshot, or earlier specification. The implementation uses a two-module layout matching the Socket SDK: `:artemis_ui_sdk` and `:app`.

The SDK supplies a complete configurable chat interface and predefined message templates. A host application supplies runtime configuration and opens or embeds chat. The Artemis Android Socket SDK provides the network connection. This document defines UI behavior and the boundary to that dependency; it does not authorize a new socket implementation.

“MUST” indicates a release requirement. “SHOULD” permits a documented alternative with equivalent behavior. Values and behavior explicitly defined here are the proposed Android product contract, including deliberate defaults and improvements; they are not claims about an existing library.

## 1 Scope and delivery status

Deliver these independently usable outputs:

1. `artemis_ui_sdk`: Kotlin Android library, AAR, sources, consumer rules and Maven metadata.
2. `:app`: Android application demonstrating full-screen chat, embedded chat, themes, custom renderers and all built-in templates.
3. A deterministic fixture mode exercising all UI features without network access or credentials.
4. A production adapter to the supported published Artemis Socket SDK.
5. Automated contract, state, rendering, lifecycle and packaging tests.
6. README, public API/configuration reference, template catalog and integration examples generated from the contracts in this document.

Included: text/Markdown, streaming responses, typing, connection/offline/error states, in-memory transcript, actions, forms, ratings, cards/carousels, lists, tables, charts, images, audio/video, file links, themes and extension points.

Excluded: a backend service, new authentication/network implementation, upload service, microphone/speech capture, text-to-speech, push notifications, offline outgoing queue, durable transcript storage, remote history retrieval, background service, floating minimized window, full Adaptive Card/Slack/WhatsApp runtimes, and a dedicated Compose API. None may be implied by a visible control or accepted configuration field.

### 1.1 Live transport integration

The requested dependency is:

```groovy
implementation 'com.github.SudheerJa-Kore.android-kore-sdk:artemis_socket_sdk:artemis-socket-0.0.2'
```

The supported socket module exposes API-key connection, raw incoming frames, text sending, structured action submissions, and feedback submissions. The UI SDK delegates these operations to that library. The coordinate `com.github.SudheerJa-Kore.android-kore-sdk:korebot:artemis-socket-0.0.2` resolves to the legacy Korebot UI AAR and does not contain the `artemis.socket` API. The expected JitPack module coordinate above must resolve to the tagged `artemis_socket_sdk` artifact before a complete build can be verified. The Socket SDK source module publishes `com.artemis:artemis-socket-sdk:1.0.0` for its Maven publication workflow.

## 2 Project and platform requirements

Create a Gradle project with `:artemis_ui_sdk` and `:app`. The new project MUST build without any legacy `korebot`, `korebotsdklib`, Flutter, or local sibling repository dependencies.

| Item | Requirement |
| --- | --- |
| Language | Kotlin; public builders and factories usable from Java |
| UI | Android Views/XML, RecyclerView, Activity and Fragment |
| State | ViewModel, immutable state and StateFlow |
| Minimum Android | API 24 |
| Compile SDK | API 36 |
| Sample target SDK | API 36 |
| Java/JVM target | 17 |
| Namespace | `com.artemis.uisdk`; sample `com.artemis.uisdk.sample` |
| Resource prefix | `artemis_ui_` for library resources |
| Build versions | Android Gradle Plugin 8.7.3, Gradle 8.14.3 wrapper, Kotlin 1.9.0. No dynamic versions. |
| Permissions | INTERNET and ACCESS_NETWORK_STATE; no microphone, storage or notification permission for the defined scope |

Use focused AndroidX/Material/lifecycle/coroutines dependencies, an image loader, Markdown renderer, YAML parser and Android media player where needed. Canvas is sufficient for the chart types specified here. Dependency versions are implementation choices, but must be pinned and validated. All transitive dependencies must be available to an ordinary consuming host.

Use Google and Maven Central repositories plus JitPack for the specified socket dependency. Validate the actual resolved AAR/POM; an artifact name is not evidence of contents. If resolution fails, report a dependency blocker and do not silently substitute copied source.

Publish to a local file Maven repository for consumption tests using the development coordinate `com.artemis:artemis-ui-sdk:0.1.0-SNAPSHOT`. This is a local development coordinate, not an assertion that a public release exists. Production coordinates/signing/repository are release configuration supplied by the owner.

## 3 Architecture and responsibility boundaries

```text
Host app
  ArtemisUI.initialize -> ArtemisUiHandle
    ChatActivity / ChatFragment
      ChatViewModel -> ChatUiState
        MessageStore + ActionCoordinator + TemplateRegistry
        ChatTransport adapter
          ArtemisSocketClient OR deterministic fixture transport
```

Package the implementation into `api`, `config`, `session`, `transport`, `model`, `chat`, `template`, `theme` and `media`. One UI artifact is sufficient.

- UI and templates render state and emit typed intents. They never call socket methods directly.
- The ViewModel owns message state, connection presentation, composer drafts and interaction state.
- The adapter maps public socket callbacks into typed events, normalizes received presentation data and delegates outgoing operations.
- Socket connection, token acquisition, handshakes, transport retry scheduling and wire sending remain Socket SDK responsibilities.
- No credentials, Activity, Fragment or View may be retained by a process-wide singleton. A handle may retain application context and configuration until closed.
- Media HTTP loading is distinct from chat transport and may use a dedicated image/player dependency. It must not receive chat authentication headers by default.

## 4 Public integration API

These declarations are an API contract, not existing implementation code. Exact internal class layout is flexible; names and semantics below form the public surface.

```kotlin
object ArtemisUI {
    fun initialize(context: Context, config: ArtemisUIConfig): ArtemisUiHandle
}

interface ArtemisUiHandle : AutoCloseable {
    fun showChat(activity: FragmentActivity, options: ChatOptions = ChatOptions())
    fun createChatFragment(options: ChatOptions = ChatOptions()): Fragment
    fun addListener(listener: ArtemisUiListener): ListenerRegistration
    override fun close()
}

fun interface ListenerRegistration : AutoCloseable {
    override fun close()
}

fun interface ArtemisUiListener {
    fun onEvent(event: ArtemisUiEvent)
}

data class ChatOptions(
    val title: String = "Chat",
    val showMinimize: Boolean = true,
    val customization: ChatCustomization = ChatCustomization()
)
```

`initialize` validates configuration synchronously and MUST NOT connect. Missing or unsupported configuration throws `ArtemisConfigurationException` containing field paths and safe reasons. It MUST NOT include credential values. `showChat`, Fragment creation and listener registration run on main; calling UI operations after handle closure throws `IllegalStateException`.

A handle supports one active chat session. Repeated launch requests while that session is active are ignored and emit `AlreadyOpen`; they must not create a second connection. Independent handles may host independent sessions. Fragment creation alone does not connect: the first started lifecycle attaches and connects. A Fragment is consumed only once; hosts create a fresh instance when reopening.

The returned Fragment MUST be recreatable with a no-argument constructor and primitive session/handle identifiers in arguments. Never put credentials, callback objects or renderer instances in a Bundle. Keep the live handle in an application-context registry with explicit removal on handle close. The Fragment resolves it by ID; after process death, if unavailable, show `Configuration unavailable. Reopen chat.` and close safely. The sample initializes a fresh handle before reopening.

`close()` is idempotent and closes the active session, removes registered listeners/customizations and releases the handle. Closing only the chat screen leaves the handle reusable for a new session. Default minimize closes/disposes the session and emits reason `MINIMIZE`; it is not a keep-alive operation.

### 4.1 Host usage

```kotlin
val chat = ArtemisUI.initialize(
    applicationContext,
    ArtemisUIConfig(
        connection = ArtemisConnectionConfig(
            endpoint = "https://runtime.example.com",
            projectId = "project-id",
            apiKey = suppliedApiKey,
            channelId = "channel-id"
        )
    )
)
chat.showChat(this, ChatOptions(title = "Support"))

// Alternative to showChat, not simultaneous with it:
supportFragmentManager.beginTransaction()
    .replace(R.id.chat_container, chat.createChatFragment())
    .commit()
```

Supply equivalent Java builders, for example `ArtemisUIConfig.Builder(connection).build()`, and overloads that do not require Java callers to construct Kotlin function types.

### 4.2 Host events

Events carry the UI session ID and timestamp. Define `Opened`, `ConnectionChanged`, `MessageChanged`, `SubmissionChanged`, `LinkRequested`, `Error`, `Closed(reason)` and `AlreadyOpen`. Error carries a typed code, safe message and `recoverable` flag. `MessageChanged` contains a sanitized immutable message snapshot, not credentials or arbitrary transport diagnostics.

Listeners run on main in registration order. Listener exceptions are caught and reported to debug diagnostics without interrupting other listeners. Removal is idempotent and prevents subsequent delivery; closing a handle removes all listeners. Connection/submission events are delivered once per state transition, not on every re-render.

## 5 Configuration contract

`ArtemisUIConfig` contains `connection`, `reconnection`, `features`, `theme`, `limits` and `debug`. Defaults below apply equally to Kotlin builders and YAML. Secrets must have redacted `toString` output.

| Group and field | Type and default | Validation/meaning |
| --- | --- | --- |
| connection.endpoint | Required string | Absolute HTTPS runtime base URL; no query, fragment or embedded credentials; strip trailing slash |
| connection.projectId | Required string | Nonblank |
| connection.apiKey | String, absent | Required for current adapter; mutually exclusive with bootstrapToken |
| connection.bootstrapToken | String, absent | Reserved alternative; reject with UNSUPPORTED_CAPABILITY for current adapter |
| connection.channelId | String, absent | At least channelId or channelName required; ID takes precedence |
| connection.channelName | String, absent | Used when channelId is absent |
| reconnection.enabled | Boolean, true | Delegated to socket configuration |
| reconnection.maxAttempts | Integer, 5 | 0–30 |
| reconnection.baseDelayMs | Integer, 1000 | Nonnegative |
| reconnection.maxDelayMs | Integer, 30000 | At least baseDelayMs |
| features.markdown | Boolean, true | False renders Markdown source as plain text |
| features.carousel | Boolean, true | False renders a readable card summary without interactive card buttons |
| features.typingIndicator | Boolean, true | Controls visibility only |
| features.timestamps | Boolean, true | Device locale/timezone formatting |
| debug.enabled | Boolean, false | Sanitized diagnostics; no raw credentials or message body logging |
| limits.maxTextChars | Integer, 10000 | Maximum outgoing text length; count Unicode code points |
| limits.maxMessages | Integer, 500 | 1–5000; in-memory visible transcript bound |
| limits.maxFrameBytes | Integer, 1048576 | Maximum raw UTF-8 frame size parsed by UI |
| limits.maxTemplateItems | Integer, 100 | 1–1000; array item cap except stricter template limits |
| limits.maxNestingDepth | Integer, 32 | JSON/YAML structural depth cap |

No runtime user-context, upload, speech, durable storage or remote history setting is accepted in this version. Unsupported keys produce a configuration diagnostic; unknown top-level groups and invalid types are fatal. Reserved authentication fields fail explicitly as specified. Do not silently accept nonfunctional feature switches.

### 5.1 Asset loading

Provide `ArtemisConfigLoader.load(context, assetPath = "sdk_configurations.yaml", environment = null)` returning `ArtemisUIConfig`. Asset paths are relative to Android assets, not filesystem paths. The root object is `artemis_ui_sdk`; accept `artemis_ui_plugin` as a legacy alias only when the canonical root is absent. Root presence with invalid content is an error, not a reason to use the alias.

If the caller supplies an environment, load an optional sibling file named `sdk_configurations.<environment>.yaml` (for a custom filename insert the suffix before its extension). Environment must match `[A-Za-z0-9_-]+`. Recursively merge maps; lists/scalars replace; explicit null clears an optional value and fails validation for required values. Missing optional override is allowed; malformed override is an error. Use a safe YAML parser with no custom object construction, limit file size to 1 MiB and reject aliases exceeding parser limits. Explicit programmatic configuration bypasses asset loading entirely.

```yaml
artemis_ui_sdk:
  connection:
    endpoint: "https://runtime.example.com"
    project_id: "project-id"
    api_key: "REPLACE_AT_RUNTIME"
    channel_id: "channel-id"
  reconnection:
    enabled: true
    max_attempts: 5
    base_delay_ms: 1000
    max_delay_ms: 30000
  features:
    markdown: true
    carousel: true
    typing_indicator: true
    timestamps: true
  theme:
    mode: system
    primary: "#2563EB"
    bubble_radius_dp: 16
  debug:
    enabled: false
```

YAML uses snake_case for the camelCase fields in the configuration table. Empty, placeholder credentials in the sample do not trigger a live connection: the sample asks for valid runtime configuration first. No production credentials are shipped.

## 6 Transport adapter contract

The following interface belongs to the UI implementation and delegates transport operations to the supported Socket SDK.

```kotlin
interface ChatTransport {
    val capabilities: TransportCapabilities
    fun setObserver(observer: TransportObserver?)
    fun connect()
    fun sendText(text: String): SendAcceptance
    fun submitAction(request: ActionRequest): SendAcceptance
    fun submitFeedback(request: FeedbackRequest): SendAcceptance
    fun disconnect()
    fun close()
}
```

`TransportCapabilities` has booleans `text`, `actions`, `feedback`, `bootstrapToken` and `serverDeliveryReceipts`; default false. The current adapter sets only text true. Fixture transport sets text/actions/feedback true, bootstrapToken/receipts false. `SendAcceptance` is `Accepted(transportMessageId: String?)` or `Rejected(error: UiError)`. Accepted means local transport acceptance only. Observer events are `Connecting`, `Connected(sessionId)`, `Disconnected(reason)`, `RawFrame(json)`, `Failure(error)` and, only where publicly supported, `Reconnecting(attempt)` and `DeliveryConfirmed(transportMessageId)`.

All observer callbacks and send-result processing are serialized on the UI session's main dispatcher. Parse frames on a background serial worker and return results in received order. `close` is terminal/idempotent; ignore late callbacks using a generation token. An adapter must never block main for HTTP, reconnection waits, parsing large frames or media loading.

### 6.1 Current published adapter mapping

The public package is `artemis.socket`. Required callable contract:

```java
ArtemisSocketConfiguration configuration = ArtemisSocketConfiguration.builder()
    .endpoint(endpoint).projectId(projectId).apiKey(apiKey)
    .channelId(channelId).channelName(channelName)
    .reconnection(enabled, maxAttempts, baseDelayMs, maxDelayMs)
    .build();
ArtemisSocketClient client = new ArtemisSocketClient(configuration, listener);
client.connect();
client.sendMessage(text);
client.disconnect();
client.shutdown();
// Also available: boolean isConnected(); String getSessionId();
```

`ArtemisSocketListener` requires `onLog(String)`, `onConnecting()`, `onConnected(String sessionId)`, `onDisconnected(String reason)`, `onMessage(String payload)` and `onError(Throwable)`. Callbacks are delivered on main by the inspected SDK. Forward messages through normalization; omit logs from chat. Map exceptions to safe UI errors without printing raw exception messages containing server bodies.

The SDK internally obtains an SDK token, obtains a WebSocket ticket and opens `/ws/sdk`; the UI MUST NOT implement those steps. Connection readiness is `onConnected`, not `connect()` returning. Reconnection belongs to the socket SDK. Do not infer retry attempts from log strings or run a concurrent UI retry timer.

`sendMessage` generates its own message ID and returns void. Map successful return to `Accepted(null)` and keep the UI's local message ID separate. Do not match a server echo by text alone. Throwing maps to rejection. `submitAction` and `submitFeedback` return UNSUPPORTED_CAPABILITY without attempting any network operation.

### 6.2 Required outgoing contracts for an enhanced transport

The enhanced Socket SDK must send actual structured frames equivalent to these JSON bodies. Optional absent fields are omitted. Local UI message IDs used for interaction locking are not added to action frames unless the backend contract explicitly supports them.

```json
{"type":"chat_message","text":"Hello","messageId":"transport-generated-id","sessionId":"s1"}
```

```json
{"type":"action_submit","actionId":"choose-plan","value":"basic","renderId":"r1"}
```

```json
{"type":"action_submit","actionId":"form-submit","value":"{\"name\":\"Alex\",\"date\":\"2026-10-06\"}","formData":{"name":"Alex","date":"2026-10-06"}}
```

```json
{"type":"feedback.submit","messageId":"m1","ratingType":"thumbs","ratingValue":1,"feedbackText":"Helpful"}
```

ActionRequest contains required nonblank `actionId`, optional string `value`, optional `Map<String,String> formData`, and optional nonblank `renderId`. FeedbackRequest contains required messageId, ratingType (`thumbs` or `star`), integer ratingValue, optional feedbackText, and optional actionRenderId. The feedback wire key is exactly `feedback.submit`, not `feedback_submit`.

An enhanced adapter must use a public structured-send API with these semantics. This document intentionally cannot make unavailable dependency methods callable. Backend credentials, the dependency binary and a server honoring the contract are runtime prerequisites, not missing reference documents.

## 7 Incoming frames and message normalization

Only user and assistant content belong in the transcript. Connection/control frames never become chat bubbles. Frames must be JSON objects within configured size/depth limits. Unknown frame types are ignored with a debug diagnostic; malformed frames produce a nonfatal PARSE error and do not disconnect the socket.

| Frame type | Required interpretation |
| --- | --- |
| `session_start` | Control-only; connected state comes from transport callback |
| `response_start` | With nonblank messageId, create/reuse an assistant streaming record; enable typing; repeated start does not clear existing chunks |
| `response_chunk` | Require nonblank messageId; append first string among chunk, content, text; create streaming record if start was missing |
| `response_end` | Finalize/upsert assistant message; replace accumulated text with provided final text; attach rich content/actions; clear its streaming state |
| `error` | Stop affected stream when messageId is present, otherwise stop typing globally; emit safe SERVER error; do not show the raw payload as text |
| Other | Ignore for transcript unless supported by an explicitly versioned adapter extension |

### 7.1 Extraction precedence

For final text choose the first string present in `fullText`, `text`, `content`, `contentEnvelope.text`, `contentEnvelope.content`; an explicitly empty string is valid. If no string exists use accumulated chunks, otherwise empty text.

For rich content select the first JSON object in `richContent`, `rich_content`, `contentEnvelope.richContent`, `contentEnvelope.rich_content`, `metadata.richContent`, `metadata.rich_content`. For actions use `actions`, `contentEnvelope.actions`, then `metadata.actions`. Do not merge conflicting copies. Preserve unrelated metadata and unknown rich keys for extensions.

`response_end` without messageId receives a local ID and cannot be reliably de-duplicated. Emit a diagnostic. With an ID, key records by transport session ID plus messageId; repeated final frames replace the same record without moving its list position. A duplicate chunk cannot be safely detected by equal text because legitimate chunks may repeat. If no event ID/sequence is supplied, append in arrival order and let final fullText repair replay differences. Ignore chunks after a message is final. Do not promise exactly-once streaming without server identifiers.

A final response containing no text, valid template or actions removes an empty placeholder and emits EMPTY_RESPONSE. A rich-only final response must still render. Preserve first-seen timestamp unless a valid ISO-8601 timestamp is provided. Record order is first arrival, not a sort by unreliable server timestamps.

### 7.2 Canonical UI model

Each `UiMessage` contains `localId`, optional `serverId`, `sessionId`, role (`USER` or `ASSISTANT`), `text`, optional `displayLabel`, timestamp, `richContent`, optional `actions`, metadata, stream state (`NONE`, `STREAMING`, `COMPLETE`, `INTERRUPTED`) and outgoing state (`NONE`, `PENDING`, `ACCEPTED`, `DELIVERED`, `FAILED`). `DELIVERED` requires a correlated delivery event and cannot be inferred from a later assistant reply.

Display a nonblank metadata `display_label` for user messages while retaining original text internally. Never replace actual outbound payload values with the display label. Maintain O(1) lookup by message ID and stable RecyclerView IDs. Enforce maxMessages by evicting oldest completed records and their interaction/media state; do not evict active streaming/pending records until they settle.

## 8 Template schema conventions

`richContent` is an object containing any combination of the keys below. Render text first, distinct nonidentical `markdown` second, `carousel` third, followed by registry order: image, html, video, audio, file, list, kpi, table, chart, form, progress, feedback, actions, quick_replies, channel_fallback, then registered custom types. Actions are a sibling model, not required to be inside richContent. Render every matching valid section.

JSON property names in schemas below are exact. `?` marks an optional field; it is documentation notation and is not part of the JSON. String IDs must be nonblank. Missing optional arrays mean empty. Wrongly typed fields never trigger unchecked casts. Ignore an invalid child while preserving valid siblings, subject to the form safety rule below. Unknown keys are preserved as extension JSON. Limit arrays using maxTemplateItems, with more restrictive limits where specified. Show `Additional items omitted` when limiting visible content.

### 8.1 Plain text and Markdown

`markdown: string`. Support paragraphs, hard line breaks, bold/italic, headings, ordered/unordered lists, links, block quotes, inline/fenced code, and tables. Inline HTML is not executed. Code blocks scroll horizontally and support copy. If Markdown is disabled, display its source as plain text. Skip rich Markdown when its trimmed string equals the displayed main text.

### 8.2 Images and media

`image`, `audio`, and `video` each use `{url: string, alt?: string, thumbnail_url?: string, caption?: string}`. Require an HTTPS URL. Image height preserves aspect ratio, capped at 240 dp before optional full-screen viewer. Loading and failed-load placeholders preserve list position. Use alt/caption for accessibility; image failure leaves caption visible.

Audio provides play/pause, elapsed/duration and seek when supported. Video provides a thumbnail, play/pause, seek and full-screen. Never autoplay. Only one media item plays per session. Pause when offscreen or app backgrounded; release on eviction/close; remove listeners when views recycle. Media errors affect only that message. No downloading for offline use is required.

### 8.3 Files

`file: {url: string, filename: string, size_bytes?: integer, mime_type?: string}`. Render filename, human-readable nonnegative size and an Open button. Blank filename displays `File`. Open validated HTTPS URL through an external handler; show `No application can open this link` if unavailable. Do not request storage permission or automatically download.

### 8.4 Carousel

`carousel: {cards: Card[]}` where Card is `{title: string, subtitle?: string, image_url?: string, default_action_url?: string, buttons?: Button[]}` and Button is `{id: string, type?: "button", label: string, options?: array}`.

Require nonblank title per card. Horizontally scroll cards in payload order; card width is min(280 dp, 82% available content width). Card body opens default_action_url when valid. Image failure does not disable buttons. A button submits actionId=id and value=trimmed label (fallback id). It is not a URL action even if its text resembles a URL. Unknown button types are displayed disabled with a diagnostic; options are preserved for custom renderers. Missing button ID disables that button.

Lock each accepted button per message ID and button ID. Other cards/buttons remain available. A rejected submission unlocks it. With carousel feature disabled, show card titles/subtitles as text and hide card action buttons; valid destination links may still open.

### 8.5 Quick replies

`quick_replies: [{id: string, label: string, icon_url?: string}]`. Render wrapped chips in source order. Selecting one submits actionId=id and value=label. Lock the entire quick-reply group while pending/accepted. Rejection restores the group. A transport without action support shows chips disabled with `Interactive replies are unavailable with this connection`.

### 8.6 Lists and KPI

`list: {title?: string, items: [{title: string, subtitle?: string, image_url?: string, default_action_url?: string}]}`. Show rows in payload order with optional images and click only for a valid destination. Skip rows with blank titles. No implicit socket action is generated.

`kpi: {label: string, value: string|number|boolean, unit?: string, trend?: string, icon_url?: string}`. Require label and scalar value. Display value prominently, with unit and optional trend text. Never evaluate a value as code. A KPI-only assistant bubble uses at most 68% chat width; mixed content uses normal rich width.

### 8.7 Tables

`table: {columns: [{key: string, header: string, align?: "left"|"center"|"right"}], rows: object[], max_visible_rows?: integer}`.

Require at least one valid column and row. Preserve column/row order; use key to read cells; missing cells display empty, scalar values display text, structured values display compact JSON text. Default visible rows is 10; clamp positive configured limit to maxTemplateItems. Show `Show more` to expand to the bounded row count and `Show less` to collapse. Use horizontal scrolling for wide tables and keep a header visible within the section. Duplicate column keys after the first are ignored with a diagnostic.

### 8.8 Charts

`chart: {type: "bar"|"line"|"pie", title?: string, data: [{label: string, value: number, color?: string}]}`. Maximum 100 data points regardless of the generic cap; use the lower bound. Values must be finite. Preserve order; use theme palette where color is invalid. Unknown chart type renders an accessible label/value list instead.

Bars/lines include a zero baseline and handle negative/constant data without division by zero. Pie ignores negative values with a diagnostic; all-zero data shows `No chart data`. Provide a legend and a text summary for TalkBack, and never rely on color alone. Charts do not emit socket actions.

### 8.9 Forms

`form: {title?: string, fields: Field[], submit_label?: string}`. Field is `{id: string, type: "input"|"select", label: string, value?: string, input_type?: "text"|"email"|"number"|"date", placeholder?: string, required?: boolean, options?: [{id: string, label: string, description?: string}]}`.

Defaults: type=input, input_type=text, required=false, submit_label=`Submit`. IDs must be unique. Blank IDs, duplicate IDs or unsupported field types make the form non-submittable with `This form contains unsupported fields`; never silently submit a partial required form. Preserve readable labels and supported fields.

Text input is single-line unless a custom renderer overrides it. Email uses email keyboard and basic address-shape validation. Number accepts a finite decimal, offers increment/decrement by 1 and emits plain decimal text; no currency/group separators. Date uses a native date picker, persists `YYYY-MM-DD`, and validates a real calendar date. Select submits the chosen option ID; no default first selection unless a valid initial value is supplied. All values are strings. Required means trimmed nonempty; validation displays at the field and focuses the first failure.

Keep drafts keyed by session/message/field, including across rotation and view recycling. Submit sends actionId=`form-submit`, value=compact JSON encoding of the field-ID-to-string map, and the same map as formData. Include optional fields as empty strings when blank. Ordering is payload field order but receivers must not depend on JSON key order. No double encoding beyond the JSON string required for value.

Lock while sending and after local acceptance. On rejection restore editable fields and all drafts. Label accepted state `Submitted`; do not claim server confirmation. Explicit server rejection unlocks only when correlated to this submission.

### 8.10 Progress

`progress: {label?: string, value: number, max?: number, variant?: "bar"|"circle"}`. max defaults to 100 and must be positive; variant defaults to bar. Clamp visual ratio to 0–1; retain original numeric value for text. Invalid max/value shows `Progress unavailable`. Display percentage with an accessible progress description. Progress-only assistant bubbles use the compact 68% width.

### 8.11 Feedback

`feedback: {prompt: string, type?: "stars"|"thumbs"|"scale", max?: integer}`. Defaults type=stars and max=5. Require a nonblank prompt. Stars/scale allow 1 through max with max clamped to 1–10. Thumbs maps up to 1 and down to 0, independent of max. Optional text input, max 2000 code points, is available via an `Add comment` affordance before selecting the rating.

Selecting a rating sends feedback immediately using the originating server message ID, ratingType=`thumbs` for thumbs or `star` for stars/scale, ratingValue and optional nonblank feedbackText. Lock after local acceptance; rejection restores choices/comment. Do not submit against a synthetic local server ID: disable feedback with a diagnostic when the incoming message lacks a server messageId. Unsupported feedback capability renders disabled choices with an explanation.

### 8.12 General actions

`actions: {elements: Element[], submit_label?: string, submit_id?: string, renderId?: string}`. Element is `{id: string, type?: "button"|"select"|"input", label: string, value?: string, payload?: string, url?: string, description?: string, options?: Option[], input_type?: "text"|"email"|"number"|"date", placeholder?: string, required?: boolean}`. Option uses the form option schema.

Require valid unique element IDs and labels. Default type is button; an explicitly unknown type is disabled, not guessed. Resolve button value by first supplied string in value/payload/url, otherwise id. A url field is an action value and MUST NOT automatically open a URL. Render buttons, selects and inputs in those groups while preserving order inside each group.

Without submit_id, button taps emit their ID/value; a selected option emits element ID/option ID; input keyboard submit emits element ID/trimmed value after validation. With submit_id, select/input changes remain local until the group submit button is pressed. It emits submit_id, compact JSON value and formData for select/input elements. Individual buttons remain immediate. submit_label defaults to `Submit` when submit_id exists; submit_label without submit_id is invalid group configuration. Forward renderId unchanged.

Use per-message/action locks for one-shot buttons and grouped submit. Standalone inputs/selects may submit changed values after acceptance; suppress repeated identical accepted value for that element. All rejected operations restore usable controls. Invalid required group fields disable group submission and show inline errors.

### 8.13 HTML and channel fallback

`html: string` supports static paragraphs, line breaks, emphasis, headings, lists, anchors and tables. Render with a static parser/view; do not execute JavaScript, remote frames, forms or arbitrary WebView bridges. Unsupported tags degrade to readable text. Links use the common URL policy.

`adaptive_card`, `slack`, `ag_ui` and `whatsapp` accept a JSON object, array or string as opaque content. Render a labeled fallback card with extracted text, not a fully interactive channel UI. Extract nonblank string values under `text`, `title`, `label`, `description`, `content` recursively in object/array order, bounded by depth and a 2000-character preview. If no readable text exists use `Content is available in another channel`. Never render raw script strings as executable content.

Unknown rich keys are available to custom renderers. If nothing can render a nonempty payload, show `Unsupported message content` once, with a nonfatal diagnostic rather than an empty bubble.

## 9 Custom renderers and UI factories

Provide `RichTemplateRegistry.register(renderer, overrideExisting = false)`, `copy()` and `mergedWith(other)`. Registration of an existing type without explicit override throws. Overriding preserves the existing position; new types append. Copy/merge creates independent registries. Default registries must not be globally mutated.

Renderer contract: `type: String`, `extract(message): TemplateData?`, `createView(context): View`, `bind(view, data, TemplateContext)` and `unbind(view)`. TemplateContext includes resolved theme, message identity, immutable draft/submission state and a typed `dispatch(UiIntent)` function. UiIntent covers open-link, action submission, feedback submission, field change and media commands. No raw socket client is exposed. Runtime renderer failures are isolated to that section with a fallback and diagnostic; recycled views must not retain old message callbacks.

`ChatCustomization` offers a registry, optional header factory, optional footer factory and theme overrides. Header context supplies title and close/minimize callbacks. Footer context supplies text, enabled state, maximum length, onTextChanged and onSend. Custom footers can present additional host-owned controls; the SDK does not claim an attachment transport. Provide reusable default factories. Custom components own no connection lifecycle.

## 10 Visual and interaction specification

These defaults are sufficient to build the initial appearance without screenshots. Use dp for dimensions and sp for text; respect font scaling and layout direction.

| Element | Default |
| --- | --- |
| Chat surface | Full available content area, edge-to-edge with system insets applied |
| Header | Minimum 56 dp, title 18 sp medium, close/minimize targets at least 48 dp |
| Status strip | Minimum 28 dp, 12 sp text, text plus status icon |
| Message list | 12 dp horizontal padding, 8 dp vertical message gap |
| User bubble | End aligned, max 82% list width, 12 dp internal padding |
| Assistant text bubble | Start aligned, max 86% width, 12 dp padding |
| Rich bubble | Start aligned, max 94% width; KPI/progress-only max 68% |
| Bubble radius | 16 dp, configurable 0–32 |
| Body text | 16 sp, approximate line height 1.4 |
| Caption/timestamp | 12 sp; timestamp below content using locale short time |
| Template title | 16 sp medium |
| Template section gap | 8 dp |
| Composer | Minimum 56 dp; grows from 1 to 5 text lines; send target 48 dp |
| Media/image corners | 12 dp |
| Tablet layout | Centered chat content with maximum width 720 dp |

Theme fields and defaults:

| Token | Light | Dark |
| --- | --- | --- |
| primary / headerBackground | `#2563EB` | `#1D4ED8` |
| headerText | `#FFFFFF` | `#FFFFFF` |
| background | `#FFFFFF` | `#0F172A` |
| userBubble | `#2563EB` | `#1D4ED8` |
| userText | `#FFFFFF` | `#FFFFFF` |
| assistantBubble | `#F1F5F9` | `#1E293B` |
| assistantText / composerText | `#0F172A` | `#F8FAFC` |
| mutedText | `#475569` | `#CBD5E1` |
| composerBackground | `#FFFFFF` | `#1E293B` |
| border | `#CBD5E1` | `#475569` |
| error | `#B91C1C` | `#FCA5A5` |

Theme mode is light, dark or system (default). Support bundled font resource/typeface, optional monospace font, icon drawables/HTTPS icon URLs, bubble radius, horizontal padding and message gap. Avatars are optional and hidden by default. Invalid asset/color values fall back with a diagnostic. Colors accept `#RRGGBB` and `#AARRGGBB`. Resolution order: defaults, config theme, per-chat host overrides. Runtime widget branding is outside this version and must not be silently fetched with another network client.

The empty state reads `Start a conversation` with configurable welcome text. Placeholder is `Type a message`. Send is disabled for blank/over-limit text, disconnected/offline state or pending local send. Preserve draft on rejection; clear it only after acceptance and only if it still equals the submitted snapshot. Trim leading/trailing whitespace for outgoing text, retaining internal line breaks. Soft keyboard Enter inserts a newline; send button performs submission.

After accepted text/action, show typing until visible assistant content arrives, its response ends, an error/disconnect occurs, or a 60-second UI timeout elapses. Timeout hides the indicator and offers a neutral `Still waiting for a response` message; it does not imply delivery failure or resend. Track active streams so ending one does not clear another still-active stream.

Auto-scroll on local send. For incoming updates auto-scroll only when within 96 dp of the bottom; otherwise retain reading position and show `New messages` button. Streaming updates are coalesced at most once per display frame. Preserve scroll anchor across rotation. Do not rebuild the entire transcript for each chunk.

## 11 Connection, errors and lifecycle

| State | Presentation and permitted action |
| --- | --- |
| Idle | Initial nonconnected state before first start |
| Connecting | `Connecting…`; input disabled |
| Connected | `Connected`; input enabled if internet available |
| Reconnecting | `Reconnecting…`; only from reliable transport evidence |
| Disconnected | `Disconnected`; input disabled, Retry available |
| Failed | Safe error and Retry for recoverable failures |
| Closed | Terminal internal state; no events except the one Closed notification |

Offline is an independent flag: display `No internet connection`, disable network submissions, retain drafts/messages. Connectivity restoration does not itself set Connected. Continue to trust the Socket SDK's connection/retry ownership. The current adapter may show Connecting during retries rather than inventing a retry count. Show a manual `Retry connection` affordance after 30 seconds without connection; clicking it terminates the old client via close and creates exactly one new client. No overlapping attempts are permitted. Do not run a separate automatic reconnect timer.

Define error codes CONFIGURATION, UNSUPPORTED_CAPABILITY, CONNECTION, AUTHENTICATION (only when reliably identifiable), SEND_REJECTED, SERVER, PARSE, EMPTY_RESPONSE, MEDIA, LINK, RENDERER and CONFIGURATION_UNAVAILABLE. Do not classify errors by fragile substring matching. Initialization errors use an in-screen panel; send errors use a recoverable banner/snackbar and preserve input; template/media errors stay local. Raw server errors are never shown verbatim by default.

Rotate: retain one ViewModel/session/client and restore message/draft/interaction state. Navigate away/back/close/minimize: close the session once and release players/client/listeners. Background: pause media and UI collection, retain in-memory session without promising execution while suspended; reconnect only through transport. Foreground: refresh from transport state. Process death: do not restore secrets or pending sends; require reinitialization/reopen. Screen close must never reconnect through late callbacks.

On disconnect mid-stream retain partial text as INTERRUPTED with `Response interrupted`; do not pretend it completed. Do not replay accepted/uncertain text or actions automatically. Retry of a definitely rejected text send is explicit and reuses its failed bubble; accepted messages are never given a blind resend action.

## 12 URL, accessibility and resource rules

Default URL opener accepts HTTPS web/media URLs. Visible navigation links may additionally use `mailto` and `tel` through Android chooser/intent. Reject javascript, data, file, content and intent schemes from server payloads. Custom app deep links require an explicit host interceptor returning HANDLED; the default opener rejects them. Host interception returns HANDLED, USE_DEFAULT or REJECT, and must not be implemented as an asynchronous listener return value.

All controls have at least 48 dp touch targets, meaningful TalkBack labels and logical focus order. Errors must be announced without continuously announcing streaming tokens. Announce completed assistant messages once. Respect system font scale through 200%, RTL layout and keyboard/system insets. Tables/charts expose readable text. Do not rely on color alone for statuses or selected ratings. Credentials, full raw frames and private message bodies are absent from default diagnostics.

Close image requests and player resources on recycling/close; cancel coroutine scopes on terminal disposal. Enforce frame, depth and item limits before allocating unbounded structures. Do not silently retain old session data after handle close.

## 13 Embedded fixture catalog

Implement fixture mode entirely within the sample/test support. It uses the same production models, parser, renderers and state transitions through a deterministic fake transport. It MUST be clearly labeled `Demo data` and MUST NOT require a server or the original reference files.

### 13.1 Stream sequence

Feed these frames in order. Exactly one assistant bubble must end with `Hello there`, not `Hello Hello there`.

```json
[
  {"type":"response_start","messageId":"m-stream"},
  {"type":"response_chunk","messageId":"m-stream","chunk":"Hello "},
  {"type":"response_chunk","messageId":"m-stream","chunk":"there"},
  {"type":"response_end","messageId":"m-stream","fullText":"Hello there"},
  {"type":"response_end","messageId":"m-stream","fullText":"Hello there"}
]
```

### 13.2 Complete rich payload

This payload defines a fixture for every built-in rich section and general actions. Split sections into individual messages as well for layout and interaction tests. Replace `.invalid` media URLs with packaged sample assets through the fixture loader only; these example URLs are deliberately not live services. Production URL handling must remain unchanged.

```json
{
  "type": "response_end",
  "messageId": "m-rich",
  "fullText": "Available examples",
  "richContent": {
    "markdown": "**Details**\n\n- First item\n- Second item",
    "carousel": {"cards": [{"title": "Basic plan", "subtitle": "For individuals", "image_url": "https://media.invalid/plan.png", "default_action_url": "https://example.com/plans", "buttons": [{"id": "choose-basic", "type": "button", "label": "Choose basic"}]}]},
    "image": {"url": "https://media.invalid/image.png", "alt": "Sample landscape", "caption": "A landscape"},
    "html": "<p>A <strong>formatted</strong> message.</p>",
    "video": {"url": "https://media.invalid/video.mp4", "thumbnail_url": "https://media.invalid/poster.png", "caption": "Video demo"},
    "audio": {"url": "https://media.invalid/audio.mp3", "caption": "Audio demo"},
    "file": {"url": "https://example.com/report.pdf", "filename": "report.pdf", "size_bytes": 2048, "mime_type": "application/pdf"},
    "list": {"title": "Resources", "items": [{"title": "Documentation", "subtitle": "Getting started", "default_action_url": "https://example.com/docs"}]},
    "kpi": {"label": "Completed", "value": 42, "unit": "tasks", "trend": "+4 today"},
    "table": {"columns": [{"key": "name", "header": "Name"}, {"key": "count", "header": "Count", "align": "right"}], "rows": [{"name": "A", "count": 2}, {"name": "B", "count": 3}], "max_visible_rows": 1},
    "chart": {"type": "bar", "title": "Counts", "data": [{"label": "A", "value": 2}, {"label": "B", "value": 3, "color": "#2563EB"}]},
    "form": {"title": "Your details", "fields": [{"id": "name", "type": "input", "label": "Name", "required": true}, {"id": "date", "type": "input", "input_type": "date", "label": "Date"}, {"id": "count", "type": "input", "input_type": "number", "label": "Count"}, {"id": "plan", "type": "select", "label": "Plan", "options": [{"id": "basic", "label": "Basic"}]}], "submit_label": "Send details"},
    "progress": {"label": "Processing", "value": 40, "max": 100, "variant": "bar"},
    "feedback": {"prompt": "Was this useful?", "type": "thumbs"},
    "quick_replies": [{"id": "yes", "label": "Yes"}, {"id": "no", "label": "No"}],
    "adaptive_card": {"body": [{"text": "Preview of channel content"}]},
    "slack": {"text": "Slack preview"},
    "ag_ui": {"title": "Agent UI preview"},
    "whatsapp": {"text": "WhatsApp preview"}
  },
  "actions": {
    "renderId": "r1",
    "elements": [{"id": "continue", "type": "button", "label": "Continue", "value": "next"}, {"id": "choice", "type": "select", "label": "Choice", "options": [{"id": "a", "label": "A"}]}, {"id": "note", "type": "input", "label": "Note"}],
    "submit_id": "save",
    "submit_label": "Save"
  }
}
```

### 13.3 Expected interaction assertions

- Carousel click emits `action_submit`, actionId `choose-basic`, value `Choose basic`, exactly once after repeated taps.
- Selecting Yes emits actionId `yes`, value `Yes`, then disables both quick replies; rejection re-enables both.
- General Continue emits actionId `continue`, value `next`, renderId `r1`, regardless of the presence of grouped fields.
- Selecting Choice A and entering Note `Hello`, then Save emits actionId `save`, value JSON for `{"choice":"a","note":"Hello"}`, the identical map as formData, renderId `r1`.
- A form with Name `Alex`, Date `2026-10-06`, Count `2`, Plan `basic` emits form-submit and a map of all four string values; blank required Name emits no frame.
- Thumbs up emits type `feedback.submit`, messageId `m-rich`, ratingType `thumbs`, ratingValue 1. Thumbs down emits 0. Rejection unlocks the control.
- Current live adapter emits no outgoing frame for any unsupported action/feedback click.

### 13.4 Mandatory fixture variants

Generate line/pie/unknown chart types; all-zero and negative chart data; progress circle/invalid max; star and scale ratings; 11 table rows; empty and malformed child arrays; duplicate form IDs; unknown form type; unknown rich key; rich-only response; nested contentEnvelope; snake_case rich_content; same Markdown/text; missing final messageId; repeated legitimate chunks; malformed JSON; oversized/deep frame; invalid URL schemes; image/media failure; custom registry override; rejected submissions; delayed response; disconnected mid-stream.

The sample controls connection success/failure, disconnect, retry, submission rejection and incoming timing. Fixture sends record exact payloads in memory for assertions; they never send network requests. A media test loader may map fixture HTTPS URLs to bundled files in memory without weakening production URL checks.

## 14 Acceptance tests and definition of done

| ID | Scenario | Required result |
| --- | --- | --- |
| CFG-01 | Missing endpoint/project/API key/channel | Field-specific validation before any connection |
| CFG-02 | YAML base + environment override | Recursive map merge, list replacement and null rules match section 5 |
| CFG-03 | Bootstrap token on current adapter | Explicit UNSUPPORTED_CAPABILITY |
| SDK-01 | Initialize without opening | Zero network calls |
| SDK-02 | Open twice, rotate three times | One active session/client, no duplicate observers |
| SDK-03 | Close during connect then delayed callback | No reopened UI or state mutation after close |
| SDK-04 | Process death then Fragment recreation | Safe configuration-unavailable flow, no credential restoration |
| MSG-01 | Stream fixture and repeated final | One finalized bubble with correct text |
| MSG-02 | Rich-only final and malformed sibling | Valid content remains visible, no crash |
| MSG-03 | Send rejected then explicit retry | Draft retained, failed bubble reused, no duplicate accepted send |
| MSG-04 | Local text accepted without receipt | ACCEPTED, never DELIVERED |
| ACT-01 | Each assertion in section 13.3 | Exact frame type/fields and correct locks |
| ACT-02 | Failed form/feedback/button send | Values retained, controls unlocked |
| CAP-01 | Same templates with current adapter | Unsupported controls explained, no fabricated chat_message actions |
| UI-01 | Every template at 320 dp and tablet width | No inaccessible/clipped controls; bounded horizontal scrolling only where specified |
| UI-02 | 200% font, dark/light, RTL, TalkBack | Readable layout, focus/labels, statuses understandable without color |
| UI-03 | Read older messages during chunks | Scroll anchor preserved and New messages affordance shown |
| MEDIA-01 | Scroll/recycle/background/close | At most one player; paused/released as specified |
| NET-01 | Offline and connection failure/recovery | Draft survives, no competing retries or automatic message replay |
| EXT-01 | Renderer override, throw, registry copy | Stable order, isolated errors, no global mutation |
| PKG-01 | New host consumes local Maven artifact | Builds debug/release with transitive dependency and no duplicate classes |
| SEC-01 | Invalid links and secret diagnostics | Links rejected appropriately, credentials absent |

Required checks: unit tests for config/parser/reducers/encoders/registry, Android UI tests for lifecycle and interactions, screenshot/layout review of fixture gallery, lint, debug/release builds, and dependency graph inspection. Run device/emulator checks on API 24 and API 36. Build and packaging tests use a clean host project that contains no SDK sources.

Live acceptance additionally requires valid backend configuration and tests for connection, text, streaming and reconnection. Full interactive acceptance requires an enhanced supported Socket SDK and actual action/form/feedback backend responses. Fixture success is not evidence of live protocol compatibility. Document untested capability honestly rather than marking it complete.

## 15 Delivery and implementation sequence

After approval, an implementer can start with this file alone:

1. Scaffold the modules and compatible pinned build toolchain. Implement configuration, API models and the fixture transport.
2. Implement reducer/state ownership, parser and all embedded fixtures; pass model and transport contract tests.
3. Implement chat shell, defaults and every renderer; complete fixture-mode interaction and accessibility checks.
4. Integrate and verify the published current Socket SDK adapter using the explicit contract above. Keep unsupported features visibly capability-limited.
5. If separately authorized, integrate a compatible enhanced Socket SDK for action/feedback production support and validate the actual wire flow.
6. Package the library, verify a clean consuming app, finish sample/docs and publish the capability/test report. Public publishing remains a separate release action.

The delivery report must state resolved dependency versions, supported capabilities, test results and any unmet acceptance IDs. No screenshot, hidden reference project or unstated “match Flutter” requirement is an acceptance dependency. Product changes to defaults or scope require updating this specification before implementation claims compliance.
