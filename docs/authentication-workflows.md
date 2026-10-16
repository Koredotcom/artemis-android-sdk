# Android service authorization workflows

## Scope

This increment adds opt-in native handling of the supplied Web SDK's preflight and just-in-time (JIT) service authorization contracts. These workflows authorize Runtime to use a connector such as Calendar or CRM. They are separate from the existing SDK bootstrap/token-refresh connection flow and from signing the user into the host app.

Enable `ArtemisFeatureConfig(authentication = true)` or YAML `features.enable_authentication: true`. It is enabled in the sample and defaults to false for existing hosts. An older Runtime that never emits these events never activates this path. This increment adds no endpoints, server changes, dependencies, automatic browser launches or automatic chat-message replay.

## Mobile flow

1. Runtime emits `auth_required` / `auth_gate_updated` for a preflight gate or `auth_challenge` for a paused tool call. A native transcript card shows each service and its state. Multiple JIT challenges within a turn share a card and are deduplicated by tool-call ID.
2. An explicit **Authorize** tap opens the Runtime-provided HTTPS URL in Android's system browser. The browser handles provider credentials. The SDK neither collects them nor reads browser cookies. Non-HTTPS/malformed links and links with embedded user information are unavailable.
3. The user completes or dismisses the browser flow and returns to the app. Returning alone restores the retry/check controls; it does not grant authorization. Runtime's OAuth callback may already have resumed the paused call and emitted its resolution event.
4. **Check authorization** explicitly sends the existing `auth_response` completion signal for a JIT row. For a preflight row it sends `consent_satisfy` with `sessionId`, `authProfileRef` and `requirementKey` so the actual SDK handler re-evaluates stored credentials. Mid-turn preflight rows also use the Web SDK's `auth_response` row-ID convention.
5. Only `auth_challenge_resolved` or authoritative preflight gate updates produce **Connected**. A matching cancelled resolution produces **Cancelled**. The client does not create a new user turn or replay a held message; Runtime owns workflow resume and queued-message replay.

**Cancel request** is available for JIT challenges and preflight requirements explicitly marked `usageMode: jit`. Advisory session-start preflight rows have no paused invocation to cancel. Cancellation sends the same `auth_response(rowId, cancelled)` convention used by the Web UI and waits for server resolution; it does not close the chat.

## Optional host-owned flow

The host may launch the provided authorization URL using its existing browser/auth integration. It must complete the Runtime's own authorization request: an unrelated host-app login or local access token does not grant Runtime connector access.

```kotlin
handle.setAuthorizationLauncher { request ->
    // Keep this request in memory and launch request.authorizationUrl using
    // the app's own auth integration. Correlate its verified callback to THIS
    // launch, rather than accepting an arbitrary deep-link query as success.
    appAuth.launch(request.authorizationUrl) { result ->
        activity.runOnUiThread {
            handle.completeAuthorization(request, when (result) {
                AppAuthResult.Completed -> ArtemisAuthorizationResult.COMPLETED
                AppAuthResult.Dismissed -> ArtemisAuthorizationResult.DISMISSED
                else -> ArtemisAuthorizationResult.FAILED
            })
        }
    }
}
```

`appAuth` / `AppAuthResult` above represent the host's integration, not SDK classes. Call SDK UI commands on the main thread while the handle is available. Setting the launcher back to `null` restores the system-browser path. The host owns callback verification, app-link/redirect configuration and its launcher lifecycle; the SDK registers no generic redirect handler.

For a fully custom host UI, `handle.state` exposes `ChatMessage.authorization`. Use `beginAuthorization(messageId, rowId)` to obtain a one-use `ArtemisAuthorizationRequest`, then `completeAuthorization(request, result)`. `checkAuthorization` and `cancelAuthorization` use the same guarded transport paths as the native buttons. `DISMISSED`/`FAILED` restore retry without cancelling a workflow; explicit `cancelAuthorization` is the workflow cancellation command.

## State and compatibility

- Requests are scoped to a handle, Runtime session, message, row and one-use request ID. Duplicate/stale host completions are ignored. Changed authorization URLs invalidate an in-progress launch. Only Runtime can mark a row Connected; a late local browser callback cannot downgrade it.
- Minimize/reopen and view recreation retain in-memory cards and outstanding host requests in the handle-owned view model. Ending/changing the conversation clears requests. URLs and OAuth state are not written to preferences, logs or history by this increment. Process death requires Runtime to reissue current authorization requirements; stale historical cards are not made actionable.
- Disconnect changes pending confirmation/cancellation to a recoverable failed state. It does not replay auth responses. A 30-second local confirmation wait restores explicit retry; it never sends an automatic cancellation. `timeoutMs` from a challenge is informational, matching Web's lack of a client auto-cancel timer. Expiry/cancellation authority remains with Runtime.
- Preflight updates reuse the active card; `presentation: append` creates a new one and disables the replaced card. Requirements removed by an authoritative update are treated as satisfied, matching Web. Each incoming requirement list accepts at most 50 entries; a merged preflight card accepts 100 rows and a JIT card 50. Malformed, duplicate or oversized requirement sets reject that frame. At most 50 browser/host launches and 100 local authorization operations can be outstanding per handle.
- Text sending, attachments, feedback and transferred-agent surveys keep their existing routes. The client does not globally lock the composer because a service needs authorization; Runtime decides which operations wait. Existing connection defaults and the 40dp composer are preserved.

## Contract compatibility and acceptance

The supplied repository's golden `consent_satisfy` fixture declares `{ requirementKey, status }`, while the actual `/ws/sdk` handler currently requires `authProfileRef` plus its bound session and rechecks credentials. Android uses that handler's supported completion shape. It does **not** send a generic `consent_satisfy(status: cancelled)`: that handler does not implement cancellation from that field. Mid-turn preflight cancellation must have a matching Runtime paused-execution row ID, as required by the Web UI's `auth_response` convention. Rejection remains recoverable; it must not be reported as a successful cancellation. No Runtime files were edited.

The debug-only gallery includes focused `auth-preflight` and `auth-challenge` examples (`--es example auth-preflight`). Their buttons only show offline toasts; they never open a provider or send socket events.

Implementation checks: sample debug APK, both release AARs, UI/socket lint and compilation of existing UI/socket test sources. No new tests were added or run for this increment, and no live OAuth flow was executed. The earlier 134 passing baseline tests predate these authentication changes.

Live acceptance remains: preflight success, multi-service JIT success, explicit cancellation (including the mid-turn preflight identity contract), denied/expired authorization, browser dismissal, blocked/missing URL, duplicate callback, minimize/reopen, lost connection, process restart and one-time workflow resume. Recheck ordinary connection/text/streaming, attachments and both feedback lanes before release. A configured non-production provider and Runtime workflow are needed for those checks.
