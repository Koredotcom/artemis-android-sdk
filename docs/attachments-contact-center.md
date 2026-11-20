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

### Agent lifecycle events

Alongside the raw `AgentJoined`, `AgentExited` and `AgentNotice` events, the SDK raises the Web SDK's typed lifecycle events. Each carries the Runtime session ID from `session_start`, never the internal transfer key:

| Event | Web equivalent | Fields |
| --- | --- | --- |
| `ArtemisUiEvent.AgentConnected` | `agentConnected` | `transferred`, `agentName`, `agentAvatarUrl` (absolute http(s) only) |
| `ArtemisUiEvent.AgentTransferred` | `agentTransferred` | `initiatedBy` (`agent` or `unknown`) |
| `ArtemisUiEvent.AgentDisconnected` | `agentDisconnected` | `reason` (for example `agent_closed`, `no_agents`; `unknown` when absent) |

A connect rebuilt from an agent message (`syntheticConnected`) raises no event. These value sets can grow, so handle unknown values with a default branch.

### Screen share and cobrowse

Android has no screen-share or cobrowse surface. When an agent invites the customer to either, the SDK declines it right away so the agent desk is not left waiting, using the Web SDK's frame. Only invites are answered; end and reconnect frames are ignored.

```json
{ "type": "agent_media_control", "sessionId": "…", "value": { "type": "webrtc_screenshare_reject", "conversationId": "…", "mediaSessionId": "…", "reason": "unsupported_platform" } }
```

Cobrowse uses `"type": "cobrowse_rejected"`. The decline is sent whether or not `contactCenter` is enabled.

### Agent media invites

With `contactCenter` enabled, an `agent_transfer_event` containing an `agent:media` WebRTC invite opens a native Accept/Reject dialog in the SDK chat. The decision uses the iOS/Web SDK wire contract:

```json
{
  "type": "contact_center.call_response",
  "sessionId": "…",
  "callId": "…",
  "mediaSessionId": "…",
  "decision": "accepted",
  "callType": "audio"
}
```

Reject uses `"decision":"rejected"` and `"reason":"user_rejected"`; an ended call uses `"decision":"terminated"`. Runtime may return `contact_center.sbc_details_response` when the session starts and releases temporary SIP/WebRTC endpoint credentials in a follow-up `agent:media` event after acceptance. The SDK then opens the AudioCodes WebRTC call surface. The same invite, endpoint, SBC, and termination states are exposed as `ArtemisUiEvent.AgentMediaInvite`, `AgentMediaEndpointReleased`, `AgentMediaSbcDetailsChanged`, and `AgentMediaCallTerminated` to listeners registered with `ArtemisUiHandle.addListener`.

The default AudioCodes bundle is loaded from `<connection.endpoint>/api/sdk/assets/contact-center/ac_webrtc-1.19.0.min.js`. Override it with `contact_center.sip_stack_url` in YAML or `ArtemisContactCenterConfig(sipStackUrl = …)`. The host application must declare microphone permission; video calls also require camera permission. HTTPS is required for Runtime and the media bundle, except loopback HTTP development hosts.

```kotlin
val listener = ArtemisUiListener { event ->
    if (event is ArtemisUiEvent.AgentMediaInvite) {
        // Route the invite to the host app's configured call provider.
    }
}
val registration = uiHandle.addListener(listener)
```
