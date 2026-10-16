# Remaining Android parity work — review plan

## Current position

The original roadmap's foundation, connection/text work, native attachments, transferred-agent text/forms/surveys, customization, advanced feedback, rich templates and native authentication now have implementations. Representative template previews were reviewed on a Samsung device, and 134 baseline tests passed before the authentication increment. Authentication has build/lint checks but has not had test execution or live OAuth verification. Build success is not full feature acceptance; live transfer/provider delivery and missing offline survey replay remain separate acceptance/dependency items.

## Delivery sequence

Each row is a separate increment. Complete its acceptance checks before enabling the next feature in a release. This sequence prioritizes the feedback UI because its existing transport contract is already present, then addresses authentication before introducing audio/media lifecycles.

| Increment | Work | Existing server/API dependency | Acceptance before release |
| --- | --- | --- | --- |
| **Implemented in this increment: advanced feedback** | Scale 0–10, text-only surveys, 1–5 stars/faces, conditional comments and text/rating follow-ups; acknowledgement-driven pending/submitted/failed/expired UI; retained drafts and explicit retry | Existing `feedback.submit` / `feedback.ack`; optional existing follow-up fields; no new endpoint or server edit | Valid payload per rating type; no duplicate pending send; reject/timeout/disconnect restores retry; expired locks; minimize/theme/scroll preserves draft; normal text/attachment/transfer survey unaffected |
| Authentication implemented in Android; live acceptance pending | Typed preflight/JIT events, native cards, system-browser/host launch, explicit completion/cancellation/check, server-confirmed gate state | Existing Runtime auth contracts; provider/redirect and test workflow required. Actual preflight completion handler differs from golden fixture; mid-turn cancellation needs a matching paused-execution row ID | Debug/release builds and lint; live success/cancel/denial/expiry/reopen and one-time resume still required. See `authentication-workflows.md` |
| Native rich template increment implemented; representative device preview checked; live acceptance pending | Field-by-field comparison of supported native templates, fallback behavior, accessible controls, Markdown and presentation differences | Mostly current message payloads; unsupported channel-specific markup requires explicit native fallback or host renderer | 134 baseline tests pass; representative forms/pickers, charts, tables and Markdown reviewed on Samsung Android 13. Remaining edge cases and live submissions are listed in `template-parity.md` |
| Pipeline conversational voice | Microphone → transcription → normal response → audio playback; permission, start/stop and interruptions | Existing voice capabilities, audio endpoints/events; non-production voice-enabled project | Denied permission, Bluetooth/audio focus, backgrounding and stop leave text chat usable |
| Realtime conversational voice | Streaming audio, mute/interruption, audio/text synchronization and reconnect cleanup | Realtime session/audio contract and configured backend/provider | No duplicate sessions/audio; interruption clears playback; reconnect and return to text verified |
| Agent calls | Capability-gated offer/accept/reject/end controls, media session and independent transfer/call state | Existing provider call signalling, credentials and media integration | Bot text, agent text and calls have independent lifecycles; declined/failed/ended calls release resources |
| Agent video / screen sharing | Video before screen sharing; permission, preview, stop/background behavior and platform consent | Provider media capabilities; Android capture/media lifecycle | Denial/stop/revoke clears capture; no lingering camera/mic/capture after call/session end |
| Omnichannel continuity | Live discovery/join/leave, transcript reconciliation, participant events, consent and invitations | Existing Runtime continuity APIs/events and another connected surface | Cross-surface join/leave/reconnect yields one reconciled transcript without duplicates |
| Outbound calling | Separate host API/UI for consent, dial/cancel and call status; no implicit dial from chat events | Existing outbound capability, configured provider and test number | Explicit dialing only; failure/cancel/end statuses reconcile; ordinary chat remains usable |

## Advanced feedback implementation details

The advanced native UI is opt-in (`features.advancedFeedback = true`, YAML `features.enable_advanced_feedback: true`); the sample enables it. Existing host integrations keep the basic thumbs/star presentation until they opt in.

1. Keep existing feedback overloads and add typed optional follow-up fields to the socket call. Fields are absent for old submissions.
2. Parse/render only recognized feedback types. `stars` normalizes to `star`; scale includes zero, text submits zero with nonempty text, native stars follow Runtime's 1–5 ingress range.
3. Respect server-resolved comment conditions and per-rating follow-up questions. Hidden answers must not be sent after a rating changes. Bound each text answer to 5,000 characters.
4. Keep draft/response state scoped to the message, action render ID and Runtime session. Duplicate taps do not create parallel requests. Never silently replay a failed submission.
5. Submit through the existing socket feedback lane. Only its matching acknowledgement completes the card; duplicate-feedback rejection counts as already submitted; expired response becomes a terminal state.
6. Retain drafted answers through scroll/minimize and appearance changes. Keep transferred-agent surveys on their dedicated submission/ack lane.
7. Build both release SDKs and sample APK, run lint, and compile existing test sources. Unit tests and device/live checks are a separately recorded verification step when requested.

## Existing behavior to carry through each increment

Connection/bootstrap/token refresh, text send and streaming, history deduplication, minimize/reopen, template actions, scrolling, the 40dp composer, five-file limit, branding/permission attachment gate, and transferred-agent survey acknowledgements remain the regression scenarios. Keep credentials and generated artifacts excluded from commits. Publish socket/UI artifacts as a matching pair only after release acceptance.
