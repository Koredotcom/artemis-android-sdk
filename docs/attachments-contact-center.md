# Attachments and transferred-agent chat

This guide covers optional file attachments and transferred-agent chat in the Android SDK.

## Enable the features

Both features are opt-in and default to disabled. Configure them when initializing the SDK:

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

Equivalent YAML:

```yaml
artemis_ui_sdk:
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

`maxFileSizeBytes` defaults to 20 MiB and can be lowered. The host allowlist and size limit are client-side checks; Runtime project policy remains authoritative and can reject a file or impose a lower limit.

## Attachment behavior

The attachment picker is available only when the host enables attachments, Runtime branding sets `widgetConfig.theme.attachmentButtonEnabled` to `true`, the session grants `attachment:write`, and upload is available. A connected socket alone does not enable the picker.

The picker and message API support up to five files per message. The composer shows upload progress and provides cancel, retry, and remove actions. Users can send files with text or as an attachment-only message. Text and selected files remain available through a normal minimize/reopen cycle; ending or changing the Runtime session clears attachment state.

Received attachment links are resolved through the SDK's authenticated download flow. The SDK opens supported HTTP(S) links without passing connection credentials to the browser.

## Transferred-agent chat

When `contactCenter` is enabled, Runtime transfer events can show queue and connection status, agent messages and typing, external forms, and post-transfer surveys. Forms open in the device browser; returning to the app leaves the chat session open.

Supported transfer survey ratings include NPS (0–10), CSAT (1–5), and like/dislike (0/1). A matching Runtime acknowledgement updates the submission state. A survey needs to be delivered by Runtime to be displayed. If the device is offline when a survey is created, Runtime must replay its event or provide the definition in history for the SDK to show it after reconnecting.
