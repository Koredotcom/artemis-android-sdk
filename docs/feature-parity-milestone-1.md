# Android feature parity: steps 1–5

This milestone implements the first five steps of the Web/Android comparison plan. The supplied Web source is the protocol reference. It is not a release or a deployment.

## Compatibility baseline

The initial workspace resolved published `socket:1.0.1` and `ui:1.0.1` artifacts. Local builds now use `:artemis_socket_sdk` and `:artemis_ui_sdk`, so the sample exercises the source under development. Both modules must be released together before distributing this milestone. The JitPack build publishes both modules to its local Maven staging repository.

The existing API-key flow remains: POST `/api/v1/sdk/init` with `X-Public-Key`, POST `/api/v1/sdk/ws-ticket` with `X-SDK-Token`, then `/ws/sdk` with `sdk-ticket, <ticket>` in the WebSocket subprotocol header. Credentials are never appended to the URL. `session_start` remains the readiness boundary. Existing `sendMessage(String)`, structured action and feedback callback APIs remain available.

The five-attempt retry limit, 1–30 second backoff, API 24 minimum, text chat, native templates and basic feedback remain the defaults. Idle disconnect, persistent client session identifiers and unlimited transient retries are opt-in. Source configuration calls remain compatible; consumers must rebuild when adopting the new libraries rather than mixing old and new binary artifacts.

## Implemented capabilities

| Step | Implementation |
| --- | --- |
| 1. Protect current behavior | Connection fixtures intercept HTTP and WebSocket dependencies, covering the baseline handshake, sends, actions, feedback acknowledgements, reconnect and failure behavior. Native UI and transcript fixtures complement them. |
| 2. Event foundation | `ChatFrameParser` provides typed events and canonical normalization. `ChatTranscript` handles state independently of Android views, ignores unknown/malformed frames and provides one history/live reconciliation path. |
| 3. Correctness | Shared message IDs, accepted-send user bubbles, server-controlled redaction, withheld/early render reconciliation, URL navigation, carousel values, pie charts, action echo and render-scoped locking. Failed basic feedback and rejected actions trigger a fresh presentation so controls can retry. |
| 4. Session management | Hosted bootstrap token/provider, deployment slug, bounded user context, session metadata, token refresh with scope checks, same-token reconnect, scoped opt-in persistent identifiers, explicit runtime session end, idle policies, retry classification, generation guards and a bounded session-start timeout. Minimize retains the handle-owned session; close releases it. |
| 5. Text conversation | Paginated persisted history/backfill, ID/occurrence reconciliation, durable sequence order, restored action selections, typing/presence, owned cancellation and late-frame suppression, server-policy-controlled thoughts/status/handoffs/notices, citations, source provenance, canonical envelopes/renderables and timestamp preservation. |

History is optional via `features.history`. A Runtime without the history route leaves live chat usable. Server capabilities govern redaction; an older Runtime that does not advertise it retains immediate user echo. Activity requires `showActivityUpdates` and the server visibility policy; detailed thoughts remain hidden by default.

## Configuration examples

Existing initialization works as before:

```kotlin
val handle = ArtemisUI.initialize(context, ArtemisUIConfig(
    connection = ArtemisConnectionConfig(
        endpoint = runtimeEndpoint,
        projectId = projectId,
        apiKey = publicKey,
        channelName = "mobile",
    ),
))
handle.showChat(activity)
```

Opt in to durable session identity and the Web transient-retry policy:

```kotlin
val config = ArtemisUIConfig(
    connection = ArtemisConnectionConfig(
        endpoint = runtimeEndpoint,
        projectId = projectId,
        apiKey = publicKey,
        channelName = "mobile",
        clientSessionIdentifier = true,
    ),
    reconnection = ArtemisReconnectionConfig(unlimitedTransientRetries = true),
    session = ArtemisSessionConfig(
        idleDisconnectTimeoutMs = 10 * 60_000L,
        endSessionOnIdle = false,
    ),
)
```

The opt-in identifier is scoped by endpoint, project, channel/deployment, public-key hash and user identity. Only its opaque ID and pending-new-session intent are cached, not credentials. A handle should be closed when its host no longer needs it, including when its UI is minimized.

Hosted bootstrap requires exactly one token or provider and omits the API key, channel selection, deployment slug, user context and client identifier (the hosted token owns that scope):

```kotlin
val hosted = ArtemisConnectionConfig(
    endpoint = runtimeEndpoint,
    projectId = projectId,
    bootstrapTokenProvider = ArtemisSocketConfiguration.BootstrapTokenProvider {
        hostBackend.fetchBootstrapToken()
    },
)
```

The provider runs on the socket worker. Implement its own bounded backend request. `handle.endSession()` ends the Runtime conversation and marks the next opt-in session initialization as new. The default close still disconnects; `session.endSessionOnClose` enables ending on close. Stale-send refresh is optional and retains the composer text while reconnecting, so the user explicitly retries instead of silently replaying a potentially accepted turn.

YAML accepts the new connection keys `bootstrap_token`, `deployment_slug`, `user_context`, `session_metadata`, `client_session_identifier`; reconnection key `unlimited_transient_retries`; feature keys `enable_history`, `enable_activity_updates`, `enable_citations`; and a `session` object with `idle_disconnect_timeout_ms`, `end_session_on_idle`, `stale_session_refresh_ms`, `end_session_on_close`. Token providers are programmatic only.

## Regression commands

```shell
bash gradlew :artemis_socket_sdk:testDebugUnitTest :artemis_ui_sdk:testDebugUnitTest
bash gradlew :artemis_socket_sdk:assembleRelease :artemis_ui_sdk:assembleRelease :app:assembleDebug
bash gradlew :artemis_socket_sdk:lintRelease :artemis_ui_sdk:lintRelease
```

Robolectric uses API 28 for native UI tests. The production minimum remains API 24; release lint checks compatibility. Connection tests use intercepted Runtime fixtures and never reach a live deployment. Robolectric may download its Android test runtime on a fresh machine.

## Validation result

On 13 October 2026, all 58 tests passed (30 socket/configuration, 22 parser/transcript, 6 native UI). Both release AARs and the sample debug APK built successfully. Release lint reported zero errors for both modules; it retained dependency/resource/style warnings. Generated publication metadata points the UI at the matching socket module version. No artifacts were published remotely.

## Before adoption

Run a live Runtime smoke check with an authorized test project: existing API-key connection, streamed response, drop/reconnect, missed-reply backfill, minimize/reopen, refresh, explicit end/new session, privacy-enabled user rendering, hosted bootstrap and existing template/action/feedback examples. No connected device or emulator was available during this implementation, so device/live acceptance remains outstanding.

Steps 6–12 remain outside this milestone: attachments, OAuth cards/gates, complete feedback surveys and rich presentation parity, broader branding/localization/extensibility, conversational voice, contact center, omnichannel and outbound calling. Scale/text feedback is supported at the socket API boundary, but its native survey UI belongs to step 8; unsupported survey templates display an availability message instead of submitting the wrong rating type.

## Minimize/reopen crash follow-up

The sample previously closed and recreated its handle on every Open Chat click. After minimizing, that called shutdown on the UI thread, where evicting an idle HTTPS connection could write a TLS close notification and throw NetworkOnMainThreadException. The sample now reuses its handle; socket shutdown cancels calls and evicts pooled connections on a background cleanup executor. Shutdown still immediately invalidates connection callbacks and prevents reconnection.

Two regressions cover host handle reuse and the thread that closes an actual pooled loopback connection. All 60 tests pass, the sample and release libraries build, and socket release lint passes. On the connected device, three consecutive minimize/reopen cycles stayed Connected in the same process; close/reopen also completed without a crash. This verifies the existing API-key lifecycle, not the entire live feature acceptance checklist above.

## Template scrolling follow-up

Unchanged bound rows now skip rendering, and text-only updates keep existing template controls. Recently recycled non-media template trees are reused with stable message IDs. Retention is bounded to 12 messages and 1,000 view nodes, and caches are cleared when the adapter detaches. This avoids unbounded retention while preserving recent form fields and horizontal/expanded template state. Action-state changes invalidate the cached controls so locks/retries remain correct.

Image loads are deduplicated, decoded on workers, limited to an 8 MiB bitmap cache and downsampled; repeated Markdown uses a bounded parsed-span cache. Audio/video is stopped on detach and excluded from the reusable tree cache. Video is prepared only when Play is tapped.

Connection/activity-only updates no longer resubmit an unchanged transcript or force a bottom scroll. Automatic following pauses during a scroll gesture and stays off when the user stops above the bottom. New messages still follow when the user is at the bottom.

All 71 tests pass, including 11 new scrolling/cache regressions; the sample/release builds and UI release lint pass. Device checks used the offline template gallery and repeated chat minimize/reopen. The warmed gallery run reported 13 janky frames out of 403 (3.23%), with a 95th-percentile frame time of 16 ms. Initial cold rendering still constructs templates, and these device counters are a local smoke measurement rather than a controlled benchmark.

## Subsequent attachments/contact-center increment

The later delivery steps 1–3 add baseline gating plus optional attachments and transferred-agent text/forms/surveys.
See [the increment documentation](attachments-contact-center.md) for the current scope, feature switches and live checks.
The earlier “steps 6–12 remain outside” statement records the original milestone, not the status of this later increment.
