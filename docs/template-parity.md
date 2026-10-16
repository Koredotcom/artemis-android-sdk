# Native template parity increment

## Scope and reference

This increment compares the Android native templates to the supplied local Web SDK's `src/core/types.ts`, `src/templates/support.ts` and template renderer implementations. It closes native field/submission gaps; it does not claim identical browser layout or a full browser HTML/CSS engine.

Connection/bootstrap/refresh, socket dependencies, the 40dp composer, five-file attachment limit, branding/permission attachment gate and transferred-agent survey lane remain unchanged by this increment. Existing template actions use the existing `action_submit` frame. New optional visual assets use the existing bounded image loader; no server endpoint or authentication flow is added. Asset requests carry no SDK credentials.

## Coverage

| Payload | Native handling in this increment |
| --- | --- |
| `actions` | Buttons, URL buttons, input/select fields, descriptions, typed validation, deferred `submit_id`/`submit_label`, initial values and render-scoped locks |
| `form` | Title, input/select/button fields, required validation, placeholder selection, input types, descriptions, authored submit label, complete string-valued `formData` and JSON action value |
| `carousel` | Existing title/subtitle/image/default link/buttons; action values preserved and render IDs propagated |
| `quick_replies` | Existing semantic ID + label value, optional compact icon, canonical/legacy group locks and one user-label echo |
| `list` | Title, item title/subtitle, optional thumbnail and default link on the row/image/text area |
| `image` | URL, alt/caption, visible load fallback; HTTP(S) and bounded inert raster data URLs |
| `video` | Existing native playback, thumbnail poster overlay, alt/caption, native media controls, release on detach |
| `audio` | Existing native playback, thumbnail/alt/caption, pause/resume, release/reset on detach |
| `file` | URL, filename, extension/MIME icon, MIME metadata and formatted size; invalid links disable Open |
| `kpi` | Label/value/unit, optional icon and up/down/flat arrows; legacy custom trend strings remain readable |
| `table` | Column keys/headers, explicit left/center/right alignment, null-safe cells, headers for empty tables, complete bounded expansion, retained expansion state |
| `chart` | Bar/line/pie, title, labels, values and per-point colors; signed/zero domains, large finite values, zero-only pie fallback and accessible native legend |
| `progress` | Label/value/max, bar/circle variants, finite validation, 0–100 clamping and Web-style percentage rounding |
| `markdown` | Existing headings/emphasis/strikethrough/code/lists/quotes, plus reference/autolinks, task markers, native images and horizontally scrollable rich Markdown tables |
| `html` | Basic native formatted text with sanitized navigation spans; active content does not execute |
| `adaptive_card`, `slack`, `ag_ui`, `whatsapp` | Readable structured text previews, including JSON-encoded strings; no foreign-channel actions are invented |
| `feedback` | Advanced feedback increment remains opt-in; its separate response state/acknowledgement behavior is preserved |

Markdown takes precedence over HTML when both text variants are present, and a valid preferred text template replaces the plain bubble instead of duplicating the response. Canonical `renderables` retain their render IDs for forms, carousel buttons and quick replies. A malformed template renders its own unavailable/fallback state and cannot remove earlier templates in the same message.

## Form and action semantics

- Inputs support canonical `text`, `email`, `number`, `date` and `time`; legacy direct input type aliases and telephone fields remain usable. Date/time controls use native dialogs. Confirming a standalone date/time action submits that value; a dialog inside a form/deferred action set only updates its draft.
- Required selects start with a placeholder, not an implicit first answer. An authored `value` or retained selection is honored. Placeholder initialization/restoration never submits an action. Option descriptions appear in the native chooser.
- Empty required fields and malformed typed values prevent submission. Field IDs must be nonempty/unique. Unsupported/malformed field sets do not submit a partial form.
- Select values are option IDs, not labels. Inputs preserve their entered string values. Form/deferred-action submissions send a complete `Map<String,String>` as `formData`, and its JSON as the action value, with the correct render ID.
- URL buttons navigate; postback buttons use their semantic value/payload or ID fallback. Action set and form buttons honor render-scoped locks. Synchronous send rejection leaves the action recoverable through the existing error path.
- Form submissions do not create user bubbles. Button/quick-reply display labels remain separate from semantic values, including for canonical renderables.
- Editing retains drafts in the existing handle-owned view model without transcript updates or network sends per keystroke. Matching drafts survive ordinary scroll/cache eviction, minimize/reopen and appearance changes. Changed template signatures do not reuse different fields' drafts; session close/change clears them.

## Native limits and deliberate presentation differences

These are mobile resource bounds, with a visible limit message where display data is capped: 50 carousel cards, 100 quick replies/list items/chart points, 500 table rows. Form/action sets accept at most 100 fields and 200 options per select; oversized or invalid field sets are unavailable rather than partially submitted. Individual native text fields are capped at 10,000 characters; the most recent 64 native form/table draft groups are retained in memory.

Explicit table `left`/`right` alignment follows payload CSS semantics, even in RTL; it is not automatically swapped. Rich Markdown tables use native horizontal scrolling. Plain message text still uses inline native spans; complex Markdown layout and arbitrary HTML/CSS/browser widgets are not a browser-equivalent surface. HTML scripts/styles/embedded objects are not executed, and channel-specific interactive layouts remain text previews, matching the Web SDK's declared channel fallback modes. Hosts can register custom renderers for additional presentation.

Image decoding is asynchronous, shared/cached and bounded (8 MiB source, downsampled to at most 1024 pixels per dimension). Raster data URLs are supported; SVG data URLs are not. Raster animation is represented by the decoded image, not browser animation. Relative asset/navigation paths resolve against the configured Runtime endpoint; use absolute URLs when the Web widget's asset origin differs. HTTP behavior follows the host app's Android cleartext policy; this increment does not relax that policy. Playback codecs likewise depend on Android's media support.

## Review examples

The existing debug-only `TemplatePreviewActivity` includes required select/email/date/time fields, a semantic button, signed charts, aligned expandable tables, Markdown tables/references/task markers, a JSON-encoded channel fallback and a canonical form with `renderId`. It never initializes the socket SDK; action toasts show the ID/value/render ID for local inspection and are not delivery acknowledgements. Release artifacts do not include the gallery.

A representative canonical form:

```json
{
  "name": "form",
  "renderId": "booking-details-v1",
  "payload": {
    "title": "Booking details",
    "fields": [
      {"id":"email","type":"input","label":"Email","input_type":"email","required":true},
      {"id":"time","type":"input","label":"Pickup time","input_type":"time","required":true},
      {"id":"team","type":"select","label":"Team","placeholder":"Choose a team","required":true,
       "options":[{"id":"sales","label":"Sales"},{"id":"support","label":"Support"}]}
    ],
    "submit_label": "Send details"
  }
}
```

The corresponding explicit submission uses `actionId: form-submit`, `renderId: booking-details-v1`, and both `formData` and a JSON action value containing `email`, `time` and the selected team ID. It does not send a user-text turn.

## Validation status

On 2026-10-15, the sample debug APK was built and installed on a Samsung SM-M325F running Android 13. The debug gallery was exercised with synthetic data: an empty form was blocked with a required-field error; native date/time dialogs populated the form; the selected team displayed correctly; the completed form reached the offline action callback. Signed bar and pie charts, an aligned table's expand/collapse controls, and Markdown tables/task markers/reference links were inspected on the device. Screenshots capture the representative rendered states. The gallery also supports `--es example template-12` (or another message ID) to focus one example without connecting to Runtime.

`bash gradlew verifyParityBaseline --console=plain` passed after the latest debug-gallery change: 50 socket, 83 UI and 1 sample tests, with zero failures/errors/skips (134 total). Its release builds and lint checks also succeeded. One existing file-card regression assertion was updated to locate the filename TextView by its text after the new file icon changed child order; its filename-width and compact-height checks remain intact. No new tests were added.

Screenshots from this device review are saved under `/Users/SudheerJampana/.codex/visualizations/2026/10/15/template-parity-demo`. These are offline rendering/local callback checks, not server delivery acknowledgements. No live Runtime form submissions were sent during this review.

Release acceptance still requires the remaining device cases: invalid typed values, date/time cancellation, semantic values vs display labels, canonical render IDs, rejected action retry, URL-only navigation, minimize/theme/scroll retention, icon/image failure, large/zero chart edge cases and malformed neighboring templates. Confirm live `action_submit` handling against the configured agent, then recheck ordinary chat/streaming, attachments and transferred-agent survey acknowledgements.
