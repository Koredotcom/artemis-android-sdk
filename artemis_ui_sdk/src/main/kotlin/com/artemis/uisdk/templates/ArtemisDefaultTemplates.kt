package com.artemis.uisdk.templates

/** Built-in richContent keys, in rendering order. Channel-specific payloads use text fallbacks. */
object ArtemisDefaultTemplates {
    @JvmField
    val types: List<String> = listOf(
        "markdown", "carousel", "image", "html", "video", "audio", "file",
        "list", "kpi", "table", "chart", "form", "progress", "feedback",
        "quick_replies", "adaptive_card", "slack", "ag_ui", "whatsapp",
    )
}
