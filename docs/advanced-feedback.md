# Advanced assistant feedback

Advanced feedback uses the Runtime `feedback.submit` / `feedback.ack` contract. Enable it in the SDK configuration to display supported feedback templates.

## Enable

```kotlin
ArtemisFeatureConfig(
    attachments = true,
    contactCenter = true,
    advancedFeedback = true,
)
```

YAML: `features.enable_advanced_feedback: true`. The sample enables it. The default is **false**, preserving the existing native thumbs/star presentation for integrations that have not opted in. Advanced controls appear only when the Runtime sends a feedback template. Merely enabling the flag makes no request and creates no feedback message.

## Payloads and native presentation

| Contract | Native behavior |
| --- | --- |
| `type: thumbs` | Values 0/1; selects and immediately submits when nothing else is asked; otherwise opens optional answers |
| `type: star` or legacy `stars` | Canonical `star`, values 1–5; explicit submit |
| `type: scale` | All NPS values 0–10, including zero; wraps into bounded native rows |
| `type: text` | No invented rating control; nonempty comment required, sends `ratingType: text`, `ratingValue: 0` |
| `rating_display: emoji` on stars | Shared Web face vocabulary: 😠 Terrible, 🙁 Poor, 😐 Okay, 🙂 Good, 😀 Great; still submits `star` with numeric value |
| `comment_visibility` / `comment_ratings` | Server-resolved always/never/conditional visibility; only listed ratings show a conditional comment |
| Legacy `comment_threshold` | Star comments at/below threshold when no resolved visibility exists; thumbs-down defaults to asking a comment |
| `follow_up.enabled`, `answer_type`, `questions` | Per-primary-rating question; missing question means none. Text or rating answer, never both |
| `follow_up.rating_type` | Independent thumbs 0/1, star 1–5, scale 0–10 control; star follow-ups on face surveys use faces |
| Authored prompt, submit and status labels | Server-authored values retained; absent values use the configurable SDK string resources |

Star ratings use the Runtime range of 1–5. Unknown or missing types display an unavailable message rather than guessing a control. Changing a rating clears a follow-up answer or comment that no longer applies. Each free-text answer is limited to 5,000 characters. Optional answers can be omitted with **Submit rating only**. Text-only surveys require a nonempty response.

Feedback may arrive as `richContent.feedback` or a canonical `renderables` entry named `feedback`. Action render IDs are carried through to the transport key and acknowledgement matching. Templates that require separate responses within one message must provide distinct action render IDs. A custom host renderer registered for feedback still overrides the native renderer.

## Response lifecycle

- `READY` → `PENDING` only on user/host submission.
- A matching successful `feedback.ack` moves to `SUBMITTED` and locks controls.
- `DUPLICATE_FEEDBACK` means an answer already exists and also locks as submitted.
- `EXPIRED` produces a terminal expired card; retry is not offered.
- Rejection, send failure, disconnect or the existing 10-second acknowledgement timeout produces `FAILED` with retained answers and explicit retry. No automatic replay occurs.
- One pending response per message/render ID; repeated taps cannot submit concurrently. Session and operation checks discard obsolete callbacks.

Unsent native feedback drafts are held by the handle-owned view model without submitting/rebuilding the transcript on every keystroke. They survive normal scrolling/cache eviction, minimize/reopen and theme/string changes. Within the same retained session, history refresh preserves response state for matching server IDs and unchanged feedback payloads. A changed template does not inherit a different template's answers. Ending/changing the session clears retained drafts and pending operation bindings. Full process death does not persist unsent answers or local acknowledgement state to disk; a fresh session uses server history and server duplicate handling.

Transferred-agent surveys use their separate `agent_transfer_survey.submit` event and acknowledgement.

## Host APIs

Existing `handle.submitFeedback(messageId, type, rating, comments)` remains available. It accepts local message IDs and resolves the server ID. Use the typed response method for a Runtime-authored advanced template:

```kotlin
handle.submitFeedbackResponse(
    messageId = message.messageId,
    renderId = null, // use the template's action render ID when present
    draft = FeedbackDraft(
        rating = 2,
        comments = "Please make the answer clearer",
        followUpRating = 4,
    ),
)
```

The method looks up the current template, validates the primary score and strips answers the template does not ask. `message.feedbackResponses` in the host `state` exposes response status, submitted/pending/failed answer snapshots and error codes, keyed by action render ID (empty string for the default card). Unsubmitted keystrokes stay in retained UI draft storage and are not published as transcript mutations.

The socket adds an overload with optional `followUpRating`, `followUpText` and `amendsRating`, using the same existing send/timeout/ack machinery. Old overloads omit these fields. The native UI sends a single explicit final response and does not emit amendments on a keystroke or rating selection while additional answers are being requested. The socket independently rejects conflicting follow-up answer types and text over 5,000 characters.
