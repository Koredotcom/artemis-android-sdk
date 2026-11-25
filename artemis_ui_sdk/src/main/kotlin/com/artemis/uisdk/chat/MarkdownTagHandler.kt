package com.artemis.uisdk.chat

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.Html
import android.text.Spanned
import android.graphics.Paint
import android.text.style.BackgroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import java.util.ArrayDeque
import org.xml.sax.XMLReader

/** Styles generated Markdown block tags to match Web rich-text typography. */
internal class MarkdownTagHandler(
    context: Context,
    private val accent: Int,
) : Html.TagHandler {
    private val density = context.resources.displayMetrics.density
    private val codeSurface = context.getColor(com.artemis.uisdk.R.color.artemis_surface)
    private val tagStartIndices = mutableMapOf<String, ArrayDeque<Int>>()

    /** Offsets of the newline that ends each Markdown block (heading, paragraph, list, quote). */
    val blockEnds = mutableListOf<Int>()

    override fun handleTag(opening: Boolean, tag: String, output: Editable, xmlReader: XMLReader?) {
        val tagName = tag.lowercase()
        if (tagName == "artemis-gap") {
            if (!opening) blockEnds += output.length
            return
        }
        if (tagName !in supportedTags) return
        if (opening) {
            tagStartIndices.getOrPut(tagName) { ArrayDeque() }.addLast(output.length)
            return
        }
        val startIndices = tagStartIndices[tagName] ?: return
        if (startIndices.isEmpty()) return
        val spanStartIndex = startIndices.removeLast()
        val spanEndIndex = output.length
        if (spanStartIndex >= spanEndIndex) return
        val styleSpans: List<Any> = when (tagName) {
            "artemis-code", "artemis-code-block" -> listOf(
                BackgroundColorSpan(codeSurface),
                TypefaceSpan("monospace"),
                RelativeSizeSpan(0.875f),
            )
            "artemis-blockquote" -> listOf(StyleSpan(Typeface.ITALIC))
            "artemis-list-item" -> emptyList()
            else -> {
                val headingLevel = tagName.removePrefix("artemis-h").toIntOrNull() ?: return
                val headingScale = when (headingLevel) {
                    1 -> 1.4f
                    2 -> 1.25f
                    3 -> 1.1f
                    4 -> 1f
                    5 -> .83f
                    else -> .67f
                }
                listOf(RelativeSizeSpan(headingScale), StyleSpan(Typeface.BOLD))
            }
        }
        styleSpans.forEach { styleSpan ->
            output.setSpan(styleSpan, spanStartIndex, spanEndIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        val supportedTags = setOf(
            "artemis-code", "artemis-code-block", "artemis-blockquote", "artemis-list-item",
            "artemis-h1", "artemis-h2", "artemis-h3", "artemis-h4", "artemis-h5", "artemis-h6",
        )
    }
}

/** Adds space above the first line of a paragraph, like a CSS block margin. */
internal class ParagraphTopSpacingSpan(private val spacingPx: Int) : LineHeightSpan {
    private var originalTop = 0
    private var originalAscent = 0

    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        if (text !is Spanned) return
        if (start == text.getSpanStart(this)) {
            originalTop = fm.top
            originalAscent = fm.ascent
            fm.top -= spacingPx
            fm.ascent -= spacingPx
        } else {
            // StaticLayout reuses the same metrics for every line of a paragraph, so undo the gap on wrapped lines.
            fm.top = originalTop
            fm.ascent = originalAscent
        }
    }
}
