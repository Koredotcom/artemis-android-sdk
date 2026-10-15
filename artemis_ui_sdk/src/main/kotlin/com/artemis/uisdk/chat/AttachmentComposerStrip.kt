package com.artemis.uisdk.chat

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.artemis.uisdk.R

/** Bounded, horizontally scrolling drafts; progress updates never rebuild the transcript or other chips. */
internal class AttachmentComposerStrip(
    root: View,
    private val primaryColor: Int,
    private val cancel: (String) -> Unit,
    private val retry: (String) -> Unit,
    private val remove: (String) -> Unit,
) {
    private val preview = root.findViewById<View>(R.id.artemis_attachment_preview)
    private val count = root.findViewById<TextView>(R.id.artemis_attachment_count)
    private val items = root.findViewById<LinearLayout>(R.id.artemis_attachment_items)
    private val rows = linkedMapOf<String, Row>()
    private class Row(val view: View) {
        val icon = view.findViewById<android.widget.ImageView>(R.id.artemis_attachment_icon)
        val filename = view.findViewById<TextView>(R.id.artemis_attachment_filename)
        val status = view.findViewById<TextView>(R.id.artemis_attachment_status)
        val progress = view.findViewById<ProgressBar>(R.id.artemis_attachment_progress)
        val cancel = view.findViewById<Button>(R.id.artemis_attachment_cancel)
        val retry = view.findViewById<Button>(R.id.artemis_attachment_retry)
        val remove = view.findViewById<ImageButton>(R.id.artemis_attachment_remove)
        var draft: AttachmentDraft? = null
        var canRetry = false
    }
    fun render(drafts: List<AttachmentDraft>, canRetry: Boolean) {
        val context = items.context
        preview.visibility = if (drafts.isEmpty()) View.GONE else View.VISIBLE
        val label = context.getString(R.string.artemis_attachment_count, drafts.size, artemis.socket.ArtemisSocketClient.MAX_ATTACHMENTS_PER_MESSAGE)
        if (count.text != label) count.text = label
        val ids = drafts.map { it.localId }.toSet()
        rows.keys.filter { it !in ids }.forEach { id -> rows.remove(id)?.let { items.removeView(it.view) } }
        drafts.forEach { draft ->
            val row = rows.getOrPut(draft.localId) {
                Row(LayoutInflater.from(context).inflate(R.layout.artemis_attachment_draft, items, false)).also { row ->
                    items.addView(row.view)
                    row.progress.progressTintList = ColorStateList.valueOf(primaryColor)
                    row.progress.indeterminateTintList = ColorStateList.valueOf(primaryColor)
                    row.cancel.setTextColor(primaryColor); row.retry.setTextColor(primaryColor)
                    row.cancel.setOnClickListener { cancel(draft.localId) }
                    row.retry.setOnClickListener { retry(draft.localId) }
                    row.remove.setOnClickListener { remove(draft.localId) }
                }
            }
            if (row.draft == draft && row.canRetry == canRetry) return@forEach
            val uploading = draft.status == AttachmentDraftStatus.UPLOADING
            val failed = draft.status in setOf(AttachmentDraftStatus.FAILED, AttachmentDraftStatus.CANCELLED)
            val fileType = AttachmentFileTypes.resolve(draft.attachment.filename, draft.attachment.mimeType)
            row.icon.setImageResource(fileType.icon)
            row.icon.imageTintList = ColorStateList.valueOf(context.getColor(fileType.color))
            row.filename.text = draft.attachment.filename
            val status = when (draft.status) {
                AttachmentDraftStatus.QUEUED -> context.getString(R.string.artemis_attachment_queued)
                AttachmentDraftStatus.UPLOADING -> draft.progress?.let { context.getString(R.string.artemis_upload_progress, it) } ?: context.getString(R.string.artemis_upload_pending)
                AttachmentDraftStatus.READY -> context.getString(R.string.artemis_attachment_ready)
                AttachmentDraftStatus.FAILED -> context.getString(R.string.artemis_upload_failed)
                AttachmentDraftStatus.CANCELLED -> context.getString(R.string.artemis_attachment_cancelled)
            }
            row.status.text = context.getString(R.string.artemis_attachment_type_status, fileType.label, status)
            row.progress.visibility = if (uploading) View.VISIBLE else View.GONE
            row.progress.isIndeterminate = draft.progress == null
            row.progress.progress = draft.progress ?: 0
            row.cancel.visibility = if (uploading) View.VISIBLE else View.GONE
            row.retry.visibility = if (failed) View.VISIBLE else View.GONE
            row.retry.isEnabled = canRetry
            row.remove.visibility = if (uploading) View.GONE else View.VISIBLE
            row.cancel.contentDescription = "${context.getString(R.string.artemis_cancel_upload)}: ${draft.attachment.filename}"
            row.retry.contentDescription = "${context.getString(R.string.artemis_retry_upload)}: ${draft.attachment.filename}"
            row.remove.contentDescription = "${context.getString(R.string.artemis_remove_attachment)}: ${draft.attachment.filename}"
            row.status.contentDescription = draft.errorMessage ?: row.status.text
            row.draft = draft; row.canRetry = canRetry
        }
    }
}
