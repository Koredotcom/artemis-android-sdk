package com.artemis.uisdk.chat

import com.artemis.uisdk.R
import org.junit.Assert.*
import org.junit.Test

class AttachmentFileTypesTest {
    @Test fun fiveRequestedExtensionsHaveTypeSpecificIconsAndLabels() {
        listOf(
            Triple("sample.txt", "TXT", R.drawable.artemis_ic_file_text),
            Triple("sample.pdf", "PDF", R.drawable.artemis_ic_file_pdf),
            Triple("sample.jpg", "JPG", R.drawable.artemis_ic_file_image),
            Triple("sample.png", "PNG", R.drawable.artemis_ic_file_image),
            Triple("sample.doc", "DOC", R.drawable.artemis_ic_file_word),
        ).forEach { (filename, label, icon) ->
            val type = AttachmentFileTypes.resolve(filename, "application/octet-stream")
            assertEquals(label, type.label); assertEquals(icon, type.icon)
        }
        assertNotEquals(AttachmentFileTypes.resolve("sample.pdf", "").color, AttachmentFileTypes.resolve("sample.doc", "").color)
    }
    @Test fun extensionMatchingIsCaseInsensitiveAndSupportsModernWordDocuments() {
        assertEquals("JPG", AttachmentFileTypes.resolve("PHOTO.JPG", "image/jpeg").label)
        assertEquals(R.drawable.artemis_ic_file_word, AttachmentFileTypes.resolve("report.DOCX", "").icon)
    }
    @Test fun missingOrUnknownExtensionsFallBackToMimeThenGenericFile() {
        assertEquals("PDF", AttachmentFileTypes.resolve("download", "Application/PDF; version=1.7").label)
        assertEquals(R.drawable.artemis_ic_file_image, AttachmentFileTypes.resolve("download.unknown", "image/png").icon)
        val file = AttachmentFileTypes.resolve("download.unknown", "application/octet-stream")
        assertEquals("FILE", file.label); assertEquals(R.drawable.artemis_ic_file, file.icon)
    }
}
