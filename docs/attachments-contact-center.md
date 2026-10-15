# Attachments and transferred-agent chat: delivery steps 1–3

This increment implements the first three items of the next-action plan: baseline protection, attachments, and transferred-agent text/forms/surveys. Original roadmap items 7–10, agent audio/video/screen sharing, omnichannel and outbound calling remain separate work.

## Compatibility and release gate

The existing API-key/bootstrap handshake, text-only send overloads, streaming, basic feedback, connection defaults and retained minimize/reopen lifecycle remain in place. Both new UI features default to **false**. The sample explicitly enables them so it can be used for live acceptance.

Both artifacts take their version from the same root `sdkReleaseVersion` (`VERSION` for JitPack, otherwise `ui-0.0.1`). The UI depends on the local matching socket project; publish and consume the two generated artifacts together. This work does not tag or publish a release. Retain the last verified dependency version for rollback; disabling the two feature switches provides an integration-level fallback.

Run the complete repeatable gate:

```shell
bash gradlew verifyParityBaseline
```

It runs socket/UI/sample tests, builds both release AARs and the sample debug APK, checks both release lint tasks, and verifies that the UI/socket versions match. To review publication metadata before a release:

```shell
bash gradlew :artemis_ui_sdk:generatePomFileForReleasePublication :artemis_socket_sdk:generatePomFileForReleasePublication
```

The Web protocol reference is the supplied local checkout at `1d200440ee4c4a89ee3d421aae84a76b3e771d6d`, specifically `ChatClient`, attachment utilities, contact-center survey contracts, and Runtime transfer event/history contracts. This records the reference revision; it does not imply that the checkout has no local modifications.

## Enable the increment

```kotlin
val config = ArtemisUIConfig(
    connection = connectionConfig,
    features = ArtemisFeatureConfig(
        attachments = true,
        contactCenter = true,
    ),
    attachment = ArtemisAttachmentConfig(
        maxFileSizeBytes = 20L * 1024 * 1024,
        allowedMimeTypes = setOf("application/pdf", "image/jpeg", "image/png"),
    ),
)
```

Equivalent YAML keys:

```yaml
artemis_ui_sdk:
  # Keep your existing connection/channel configuration here.
  features:
    enable_attachments: true
    enable_contact_center: true
  attachments:
    max_file_size_bytes: 20971520
    allowed_mime_types:
      - application/pdf
      - image/jpeg
      - image/png
```

The default attachment policy accepts common images, PDF, plain text/CSV, Office documents, audio and video. MIME inference for common extensions covers content providers that report `application/octet-stream`; unknown types must be allowed explicitly by the host. The client ceiling is 20 MiB per nonempty file; unknown-length streams are counted and bounded while reading. The Runtime's project policy remains authoritative and may reject a client-approved file or apply a lower limit. The native composer supports multiple-document selection and incremental additions, with a hard ceiling of five files per message. Queued, uploading, ready, failed and cancelled files each occupy one slot until removed or sent. The lower-level send overload also rejects more than five attachment IDs. An oversized selection is rejected as a whole before file metadata is queried or any new upload starts; existing selections and typed text are retained.

The current session capability contract does not advertise a file-upload or transferred-text feature bit. The upload composer requires host opt-in plus an explicit `widgetConfig.theme.attachmentButtonEnabled: true` in the Runtime SDK init/refresh branding response and the `attachment:write` permission. It is hidden before that response, and remains hidden when branding is absent, false or malformed. Transferred-agent UI still uses explicit host opt-in. Transfer events arriving over the authenticated socket are authoritative; `capabilities.contactCenter` describes media support and is not incorrectly used to block transferred text. An absent legacy capability object remains compatible. A missing upload route produces a recoverable error and hides the attachment picker for that session while text chat remains available.

## Attachment behavior

- File metadata queries, content-provider reads and HTTP transfer run off the UI thread. Uploads stream with bounded buffers on separate workers; they do not wait on or occupy the credential-refresh worker.
- The system document picker requires no broad storage permission. Temporary persisted URI access acquired by the SDK is released after removal/send/close; grants already owned by the host are retained.
- The composer shows upload progress when the file length is known, and an indeterminate upload state otherwise. Cancel, retry and remove are separate controls.
- Failed upload/cancel retains the selected file for explicit retry and retains typed text. Remove restores text-only sending. A rejected message send retains the staged uploaded attachment.
- Draft text, staged attachments and survey drafts remain on the handle-owned view model through minimize/reopen. Ending or changing the Runtime session clears attachment state and invalidates old callbacks; unsent text remains available while the handle stays open.
- Upload success supplies a scoped attachment ID; sends use `attachmentIds` and durable filename/MIME/size metadata. No signed download URL is persisted.
- Attachment cards resolve a fresh authenticated `/attachments/{id}/url?disposition=attachment` URL on click. Relative web links resolve against the Runtime endpoint. For server-local `file://` storage, the SDK uses the existing authenticated `POST /attachments/{id}/delivery-url` route to obtain a short-lived web delivery URL. It never opens a server filesystem path. Only HTTP(S) URLs without embedded credentials are opened. SDK credentials are never passed to the browser, and authenticated file requests do not follow redirects. Pending scan/readiness and unsupported delivery routes return recoverable errors.
- Provider-marked failed delivery attachments remain visible but disabled. Upload failure, Runtime rejection, cancellation and disconnect complete callbacks once and leave chat usable. Uploads and surveys are never silently replayed.

Lower-level socket APIs remain available for hosts with their own picker/UI:

```java
ArtemisAttachmentUpload upload = client.uploadAttachment(
    filename, mimeType, sizeBytes, maxBytes,
    () -> contentResolver.openInputStream(uri), callback);
// sizeBytes may be -1. callback includes onProgress, onSuccess and onFailure.
upload.cancel();

client.sendMessage(text, messageId, metadata, interactionContext, attachmentIds);
client.resolveAttachmentDownloadUrl(attachmentId, downloadCallback);
```

## Transferred-agent behavior

Queue, connection, waiting, text, typing, form, scoped attachment and disconnect events are parsed centrally. Runtime conversation IDs and transfer IDs are intentionally distinct; the authenticated socket generation establishes conversation ownership. Stale socket callbacks and older transfer transitions cannot replace the active state. Duplicate provider messages reconcile with durable history IDs, including redelivery after backfill.

Typing is ephemeral and creates no transcript row. Entering transfer stops the previous assistant stream and suppresses its late chunks. Sending while queued or connected to a human does not invent assistant typing. Normal assistant conversation remains usable after disconnect.

Forms from either supported legacy form metadata or webhook-channel secure-link metadata open in the browser. No WebView JavaScript bridge or embedded provider page is added to chat. Opening and returning from a form does not end the chat.

Post-transfer surveys support NPS (0–10), CSAT (1–5) and like/dislike (0/1), authored prompts/status labels, conditional comments, bounded comments and acknowledgement-driven ready/pending/submitted/failed states. Only an explicit eligible close event creates a live survey. Submission uses `agent_transfer_survey.submit` and its own matching transfer acknowledgement; generic `feedback.ack` cannot complete it. Duplicate taps and submitted cards do not submit again. Provider diagnostics are excluded from the native survey state and error copy.

During reconnect, existing survey/draft/submission state is retained and history can restore transfer state, messages, attachment delivery status and safe form links. If the Runtime supplies a survey definition in canonical history metadata, it is normalized and reconciled too. **The supplied Runtime persistence source currently does not include the full close-event survey definition in durable history. A survey never received while offline requires Runtime event replay/support; Android cannot reconstruct an absent definition.** Acknowledged state is retained for the active handle, not stored as a new cross-process survey cache.

## Automated checks and live acceptance

Automated fixtures intercept every Runtime request and socket; no human agent, provider survey or real project is contacted. They cover legacy behavior, new feature defaults, multipart/auth/wire contracts, size/type checks, streaming bounds, progress, cancellation, retry, refresh during blocked file reads, stale sessions, unsafe URLs, survey ack separation, transfer/history reconciliation, draft retention and native controls on API 24/API 28.

Attachment device verification is recorded below. Human-agent/provider acceptance remains with the user. Use the sample, which enables both features, and a controlled test deployment:

| Scenario | Expected result |
| --- | --- |
| Open, send ordinary text and receive streaming | Existing behavior; no attachment fields on text-only sends. |
| Existing URL/carousel/action/feedback templates | Navigation and submitted values remain correct; failures can retry. |
| Scroll a long mixed transcript | No forced jump while reading older content; recent template state remains stable. |
| Minimize/reopen repeatedly, then close/reopen | Minimize preserves the conversation; close follows configured session policy; no crash. |
| Select an allowed file; watch upload; send with and without text | Progress/ready states, correct card and provider delivery. |
| Select a disallowed/oversized file | Recoverable message, retained text and usable remove/retry controls. |
| Cancel upload; retry; disconnect during upload | No duplicate callback/send; draft retained; text/connection remain usable. |
| Open received and sent attachment cards; expire a download URL | Fresh authenticated URL each click; errors remain recoverable. |
| Trigger human-agent transfer | Queue/connected notices and agent text/typing display once. |
| Open secure form and return | Browser form works; chat/session remain open. |
| Disconnect/reconnect during transfer | Missed durable messages reconcile once; transfer state recovers. |
| Agent ends transfer and emits each survey type | Correct options/comments/labels; only matching ack marks success. |
| Reject/timeout a survey submission, then retry | Failed state keeps the draft; repeated taps do not duplicate pending submissions. |
| Close transfer while customer is offline | Confirm deployed Runtime replay; record the survey-history limitation above if replay is absent. |
| Return to assistant conversation | Ordinary sending, streaming, templates and feedback still function. |

The sample APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. The debug-only `TemplatePreviewActivity` also accepts the Boolean intent extra `contactCenter=true` to show offline transferred-agent/form/attachment/survey fixtures. Offline survey taps show preview confirmation and do not submit live feedback.

## Verification result — 15 October 2026

`verifyParityBaseline` passed with **123 tests, zero failures and zero errors**: 46 socket/configuration,
76 UI/protocol/native checks (including API 24/API 28 cases and Java constructor compatibility), and one sample
minimize/reopen regression. This retains the previous 71 tests and adds 52 checks/cases. Both release AARs and
the sample debug APK built. Socket and UI release lint reported zero errors (one and 22 warnings respectively).
Generated publication metadata gives both modules `ui-0.0.1` locally and references `com.artemis:socket:ui-0.0.1`
from the UI artifact. No release has been published. Live Runtime/provider/device acceptance is assigned to the user.

## Live attachment device verification — 15 October 2026

Installed and ran the sample on the connected Samsung SM-M325F (Android 13 / API 33), against its configured dev Runtime. Only synthetic attachment-check files were selected/uploaded; no human-agent transfer or provider survey was triggered.

| Check | Observed result |
| --- | --- |
| Select/upload a 71-byte text file | Ready-to-send state and enabled Send control. |
| Send attachment only | User attachment card and assistant reply received. |
| Send text with attachment | Combined user text/card and assistant reply observed. |
| Upload a 5.4 MB file | Percentage progress visible, including 6%; reached ready state. |
| Cancel an upload | Returned to retry/remove state; chat stayed Connected. |
| Retry cancelled upload | Progress resumed and reached ready state. |
| Select a 21 MiB file | Rejected before upload; clear size error, retry/remove controls available. |
| Remove rejected attachment | Ordinary text-only sending resumed with the typed draft retained. |
| Picker file-type filter | Unsupported APK entries were disabled; they were not opened or uploaded. |
| Minimize/reopen with staged attachment | Staged file and Connected state retained in the same process. |
| Open uploaded attachment | Chrome downloaded 71 bytes from the Runtime; SHA-256 matched the original synthetic file. |
| Return from browser | Chat remained Connected with the attachment card visible; no AndroidRuntime crash was logged. |

The check exposed two issues, both repaired and rechecked:

1. The legacy rich file row gave its Open button full width, collapsing the filename and making the card nearly screen-height. Open now wraps its content; the filename is visible and the card is compact. A native measurement regression covers it.
2. The dev Runtime's URL endpoint returned a server-local `file://` URL. The SDK now obtains a scoped, short-lived HTTP delivery URL using the existing authenticated delivery mint route. The actual Chrome download and matching file hash verify the complete path. Unsupported/missing delivery routes still return a clear recoverable error; server paths and signed URLs are excluded from diagnostics.

After these fixes, **128 tests passed** (50 socket/configuration, 77 UI/protocol/native, one sample lifecycle), both release AARs and the debug APK built, and release lint retained zero errors. The updated APK remains installed and chat was left Connected. Large synthetic source fixtures were removed from the device; the small verification text file and downloaded copy remain available for inspection. No Runtime code or deployment configuration was changed.


## Mobile design alignment — 15 October 2026

The attachment composer now follows the supplied Web SDK reference: a bordered paperclip control, a separate rounded text field, an outlined paper-plane send icon, and a top divider. Header and attachment-management controls retain 48 dp targets; the attachment and send composer buttons are now 36 dp with 16 dp glyphs at the user’s request. Connection status appears below the header title.

Staged files use a compact preview with a single-line filename, upload status/progress, and explicit cancel/retry/remove actions. Sent files use an accessible file row with filename, type, size, and an open action; long names are shortened visually while the full name remains available to accessibility services. Failed provider deliveries remain disabled. Light/dark resource colors and the existing configured send/header primary color are retained.

This update changes presentation and view bindings. Socket/upload/session behavior was not changed in this design pass. The existing failed-delivery UI assertion was adapted to the accessible file row. The debug APK builds successfully; the automated suite was not rerun for this styling pass. On the connected Samsung Android 13 device, synthetic file upload and send, the assistant response, and minimize/reopen were observed successfully.


## Multiple-file flow from the Web recording — 15 October 2026

The supplied recording shows files accumulating above the composer, followed by a single message for the batch. Mobile now uses `OpenMultipleDocuments` and a compact horizontal strip with a count out of five. Users can add documents across picker visits, remove a particular file, cancel its upload, or explicitly retry that file. Re-selecting the same document URI does not add a duplicate slot. File icons remain 20 dp; header/card actions are 18 dp, while the composer uses 36 dp buttons with 16 dp glyphs, independent of screenshot dimensions.

Uploads run sequentially on the existing attachment worker; each file keeps an independent revision and Runtime session binding. Sending waits until every selected file is ready, then submits one message with all attachment IDs and matching filename/MIME/size arrays, with optional typed text. Upload/cancel failures block that batch until retried or removed. A rejected send keeps the full ready batch for retry. Legacy single-document and no-argument retry/cancel/remove methods remain callable, and `pendingAttachment` continues to expose the first ready attachment for existing observers. New observers should use `attachmentDrafts`.

Sent batches appear as a compact attachment count that can be expanded to open individual files. This summary is a presentation of the selected batch, not a server delivery acknowledgement. Individual delivery failures remain disabled and are indicated in the group summary.

The debug APK and release AARs build, release lint passes with zero errors, and 134 automated tests pass. The file-type follow-up adds three presentation checks and three batch/limit checks. Physical Samsung Android 13 checks observed six-file rejection, a five-file ready batch, removal followed by an incremental replacement, draft retention through minimize/reopen, one attachment-only batch send, and an assistant reply.


## File-type icons and five-format acceptance — 15 October 2026

Type presentation now uses the filename extension, with MIME fallback when a known extension is absent. This is a display hint and does not replace upload MIME/size validation or server policy.

| Extension | Icon | Composer/assistant accent |
| --- | --- | --- |
| TXT | Lined text document | Slate |
| PDF | PDF document | Red |
| JPG | Image | Teal, with JPG label |
| PNG | Image | Teal, with PNG label |
| DOC / DOCX | Word document with W | Blue |

User-bubble icons use white for contrast; extension labels stay visible. Spreadsheet, presentation, audio and video hints and a generic-file fallback are also included. File icons remain 20 dp and header/card action icons 18 dp. Attachment/send composer buttons are 36 dp with 16 dp glyphs; other action targets retain 48 dp.

The physical device check uses five real, synthetic files: ASCII text, a one-page PDF, a JPEG, a PNG, and a legacy Word compound document generated by macOS textutil. All five uploaded to ready state in one selection on the configured dev Runtime. The five-file ceiling counts every staged item, and the socket API independently rejects a sixth ID.

`verifyParityBaseline` passes: 50 socket/configuration tests, 83 UI/protocol/attachment tests, and one sample lifecycle test (134 total, no failures/skips); release AARs, sample APK and release lint are included in this gate.


The composer single-line input now shares the 36 dp minimum height of its attachment/send buttons, with 4 dp vertical padding and font padding disabled. Longer messages can still expand to five lines. The updated debug APK builds and the matching heights were visually checked on the connected phone.

Final composer sizing: the shared button/input dimension is now 40 dp at the user’s request. Both attachment/send buttons are 40 × 40 dp, and the single-line input uses the same 40 dp minimum height; multiline expansion remains available.


## Branding-controlled attachment visibility

The attachment button starts hidden; a successful socket connection alone does not enable its visibility. Mobile reads `widgetConfig.theme.attachmentButtonEnabled` from the existing `/api/v1/sdk/init` response (also used by the Web SDK to load branding), with `config.theme` as the legacy response alias. Boolean `true` and the normalized string `"true"` enable it only when the host attachment feature is enabled, the session permits `attachment:write`, and uploads are available. Missing/false/invalid values leave it hidden. There is no second branding request on the connection path.

A new default `ArtemisSocketListener.onSessionScopeChanged` callback delivers sanitized init/refresh policy to the UI, including branding changes while connected. Revoking upload permission or branding cancels queued/active uploads, keeps selected files removable, and prevents the batch from being sent. Text-only chat and opening already received attachments do not depend on the upload button’s branding flag. An omitted branding value during refresh retains the last resolved branding; explicit null/false disables it.

For this branding correction, the debug APK, both release AARs, existing UI test sources, and release lint compile/pass; automated tests were not run. On the connected phone the current dev branding response resolves `attachmentButtonEnabled=true` with `attachment:write`, so the icon appears after configuration loads and chat remains Connected without a fatal exception. The dev branding configuration was not changed.
