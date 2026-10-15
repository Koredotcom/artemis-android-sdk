package com.artemis.uisdk.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

internal object AttachmentMime {
    private val common = mapOf("pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp", "mp3" to "audio/mpeg", "mp4" to "video/mp4",
        "doc" to "application/msword", "xls" to "application/vnd.ms-excel", "ppt" to "application/vnd.ms-powerpoint",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    fun resolve(filename: String, declared: String?): String {
        val mime = declared?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return if (mime.isNullOrBlank() || mime == "application/octet-stream") common[filename.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream" else mime
    }
    fun metadata(attachment: AttachmentRef) = metadata(listOf(attachment))
    fun metadata(attachments: List<AttachmentRef>) = JsonObject().apply {
        add("attachmentIds", JsonArray().apply { attachments.forEach { add(it.id) } })
        add("attachmentFilenames", JsonArray().apply { attachments.forEach { add(it.filename) } })
        add("attachmentMimeTypes", JsonArray().apply { attachments.forEach { add(it.mimeType) } })
        add("attachmentSizeBytes", JsonArray().apply { attachments.forEach { add(it.sizeBytes) } })
    }
}
