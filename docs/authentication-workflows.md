# Service authorization workflows

## Scope

The SDK can display Runtime preflight and just-in-time (JIT) authorization requests for services such as Calendar or CRM. These workflows authorize Runtime to use a connector; they are separate from the SDK connection flow and from signing in to the host app.

Enable `ArtemisFeatureConfig(authentication = true)` or YAML `features.enable_authentication: true`. The setting defaults to false. Authorization cards appear when Runtime sends supported authorization events.

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
- Minimize/reopen and view recreation retain in-memory cards and outstanding host requests in the handle-owned view model. Ending/changing the conversation clears requests. The SDK does not write authorization URLs or OAuth state to preferences, logs or chat history. After process death, Runtime must reissue current authorization requirements before the cards become actionable again.
- Disconnect changes pending confirmation/cancellation to a recoverable failed state. It does not replay auth responses. A 30-second local confirmation wait restores explicit retry; it never sends an automatic cancellation. `timeoutMs` from a challenge is informational, matching Web's lack of a client auto-cancel timer. Expiry/cancellation authority remains with Runtime.
- Preflight updates reuse the active card; `presentation: append` creates a new one and disables the replaced card. Requirements removed by an authoritative update are treated as satisfied, matching Web. Each incoming requirement list accepts at most 50 entries; a merged preflight card accepts 100 rows and a JIT card 50. Malformed, duplicate or oversized requirement sets reject that frame. At most 50 browser/host launches and 100 local authorization operations can be outstanding per handle.
- Text sending, attachments, feedback and transferred-agent surveys keep their existing routes. The client does not globally lock the composer because a service needs authorization; Runtime decides which operations wait. Existing connection defaults and the 40dp composer are preserved.
