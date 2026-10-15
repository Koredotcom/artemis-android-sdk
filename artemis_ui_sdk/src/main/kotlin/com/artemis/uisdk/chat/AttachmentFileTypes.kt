package com.artemis.uisdk.chat

import com.artemis.uisdk.R
import java.util.Locale

/** Display hints only: upload validation and server MIME/scan policy remain authoritative. */
internal object AttachmentFileTypes {
    data class Presentation(val label: String, val icon: Int, val color: Int)
    private enum class Kind(val label: String, val icon: Int, val color: Int) {
        TEXT("TXT", R.drawable.artemis_ic_file_text, R.color.artemis_file_text),
        PDF("PDF", R.drawable.artemis_ic_file_pdf, R.color.artemis_file_pdf),
        WORD("DOC", R.drawable.artemis_ic_file_word, R.color.artemis_file_word),
        IMAGE("IMG", R.drawable.artemis_ic_file_image, R.color.artemis_file_image),
        AUDIO("AUD", R.drawable.artemis_ic_file_audio, R.color.artemis_file_media),
        VIDEO("VID", R.drawable.artemis_ic_file_video, R.color.artemis_file_media),
        SHEET("XLS", R.drawable.artemis_ic_file_sheet, R.color.artemis_file_sheet),
        SLIDES("PPT", R.drawable.artemis_ic_file_slides, R.color.artemis_file_slides),
        FILE("FILE", R.drawable.artemis_ic_file, R.color.artemis_muted),
    }
    private val extensions = mapOf(
        "txt" to Kind.TEXT, "csv" to Kind.SHEET, "pdf" to Kind.PDF,
        "doc" to Kind.WORD, "docx" to Kind.WORD,
        "jpg" to Kind.IMAGE, "jpeg" to Kind.IMAGE, "png" to Kind.IMAGE, "gif" to Kind.IMAGE, "webp" to Kind.IMAGE,
        "mp3" to Kind.AUDIO, "wav" to Kind.AUDIO, "m4a" to Kind.AUDIO, "ogg" to Kind.AUDIO,
        "mp4" to Kind.VIDEO, "mov" to Kind.VIDEO, "webm" to Kind.VIDEO,
        "xls" to Kind.SHEET, "xlsx" to Kind.SHEET, "ppt" to Kind.SLIDES, "pptx" to Kind.SLIDES,
    )
    fun resolve(filename: String, mimeType: String): Presentation {
        val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val known = extensions[extension]
        val kind = known ?: when {
            mime == "application/pdf" -> Kind.PDF
            mime == "application/msword" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Kind.WORD
            mime.startsWith("image/") -> Kind.IMAGE
            mime.startsWith("audio/") -> Kind.AUDIO
            mime.startsWith("video/") -> Kind.VIDEO
            mime == "text/csv" || mime == "application/vnd.ms-excel" || mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Kind.SHEET
            mime == "application/vnd.ms-powerpoint" || mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> Kind.SLIDES
            mime.startsWith("text/") -> Kind.TEXT
            else -> Kind.FILE
        }
        return Presentation(if (known != null) extension.uppercase(Locale.ROOT) else kind.label, kind.icon, kind.color)
    }
}
