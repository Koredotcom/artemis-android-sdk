package com.artemis.uisdk.sample

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.artemis.uisdk.ArtemisTemplateRenderer
import com.artemis.uisdk.ArtemisTemplateRequest
import com.artemis.uisdk.ArtemisUiHandle
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** App-owned template replacements selected by richContent template type. */
internal object CustomTemplateInjection {
    fun register(handle: ArtemisUiHandle) {
        handle.registerTemplateRenderer("carousel", ArtemisTemplateRenderer(::renderCarousel))
        handle.registerTemplateRenderer("list", ArtemisTemplateRenderer(::renderList))
        handle.registerTemplateRenderer("table", ArtemisTemplateRenderer(::renderTable))
    }

    private fun renderCarousel(request: ArtemisTemplateRequest): View? {
        val payload = request.payload.objectValue() ?: return null
        val cards = payload.arrayValue("cards")?.objects()?.take(20).orEmpty()
        if (cards.isEmpty()) return null
        val context = request.context
        val root = templateRoot(context)
        root.addView(sectionTitle(context, payload.stringValue("title") ?: "Explore your options"))
        root.addView(text(context, "Swipe to compare stays", 13f, MUTED))

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(context, 12), 0, dp(context, 4))
        }
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        cards.forEachIndexed { index, card ->
            val hotelCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(context, Color.WHITE, BORDER, 18)
                clipToOutline = true
                elevation = dp(context, 2).toFloat()
            }
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = card.stringValue("title") ?: "Hotel image"
                setBackgroundColor(if (index % 2 == 0) Color.rgb(220, 239, 242) else Color.rgb(226, 234, 247))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, 138),
                )
            }
            hotelCard.addView(image)
            card.stringValue("image_url")?.let { imageUrl ->
                image.tag = imageUrl
                request.loadImage(imageUrl) { bitmap ->
                    if (image.tag == imageUrl && bitmap != null) image.setImageBitmap(bitmap)
                }
            }

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 14))
            }
            content.addView(text(context, "HOTEL OPTION  ${index + 1}", 10f, TEAL, bold = true))
            content.addView(text(
                context,
                card.stringValue("title") ?: "Stay option",
                18f,
                INK,
                bold = true,
            ).apply { setPadding(0, dp(context, 5), 0, dp(context, 4)) })
            card.stringValue("subtitle")?.takeIf(String::isNotBlank)?.let {
                content.addView(text(context, it, 13f, MUTED).apply {
                    setLineSpacing(dp(context, 2).toFloat(), 1f)
                })
            }
            addActionButtons(request, payload, card, content)
            hotelCard.addView(content)
            strip.addView(hotelCard, LinearLayout.LayoutParams(
                dp(context, 268),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(context, 12) })
        }
        scroll.addView(strip)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return root
    }

    private fun renderList(request: ArtemisTemplateRequest): View? {
        val payload = request.payload.objectValue() ?: return null
        val items = payload.arrayValue("items")?.objects()?.take(50).orEmpty()
        if (items.isEmpty()) return null
        val context = request.context
        val root = templateRoot(context)
        root.addView(sectionTitle(context, payload.stringValue("title") ?: "Recommended options"))
        items.forEachIndexed { index, item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(context, Color.WHITE, BORDER, 14)
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            }
            item.stringValue("image_url")?.let { imageUrl ->
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.rgb(226, 239, 242))
                    layoutParams = LinearLayout.LayoutParams(dp(context, 56), dp(context, 56)).apply {
                        marginEnd = dp(context, 12)
                    }
                }
                image.tag = imageUrl
                request.loadImage(imageUrl) { bitmap ->
                    if (image.tag == imageUrl && bitmap != null) image.setImageBitmap(bitmap)
                }
                row.addView(image)
            }
            val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            details.addView(text(context, item.stringValue("title") ?: "Option ${index + 1}", 15f, INK, bold = true))
            item.stringValue("subtitle")?.takeIf(String::isNotBlank)?.let {
                details.addView(text(context, it, 12f, MUTED).apply { setPadding(0, dp(context, 3), 0, 0) })
            }
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row, verticalSpacing(context))
            addActionButtons(request, payload, item, root)
            item.stringValue("default_action_url")?.let { url ->
                row.isClickable = true
                row.setOnClickListener { request.openUrl(url) }
            }
        }
        return root
    }

    private fun renderTable(request: ArtemisTemplateRequest): View? {
        val payload = request.payload.objectValue() ?: return null
        val columns = payload.arrayValue("columns")?.objects()?.take(12).orEmpty()
        val rows = payload.arrayValue("rows")?.objects()?.take(100).orEmpty()
        if (columns.isEmpty()) return null
        val context = request.context
        val root = templateRoot(context)
        root.addView(sectionTitle(context, payload.stringValue("title") ?: "Details"))
        val horizontalScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            setPadding(0, dp(context, 10), 0, dp(context, 4))
        }
        val table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun addRow(values: List<String>, header: Boolean, rowIndex: Int) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            values.forEach { value ->
                val cell = text(
                    context,
                    value,
                    if (header) 12f else 13f,
                    if (header) Color.WHITE else INK,
                    bold = header,
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11))
                    setBackgroundColor(when {
                        header -> TEAL
                        rowIndex % 2 == 0 -> Color.WHITE
                        else -> PALE
                    })
                }
                row.addView(cell, LinearLayout.LayoutParams(dp(context, 148), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            table.addView(row)
        }
        addRow(columns.map { it.stringValue("header") ?: it.stringValue("label") ?: it.stringValue("key").orEmpty() }, true, 0)
        rows.forEachIndexed { index, row ->
            addRow(columns.map { column ->
                val key = column.stringValue("key") ?: column.stringValue("id")
                row.get(key)?.displayValue().orEmpty()
            }, false, index)
        }
        horizontalScroll.addView(table)
        root.addView(horizontalScroll)
        return root
    }

    private fun addActionButtons(
        request: ArtemisTemplateRequest,
        templatePayload: JsonObject,
        item: JsonObject,
        parent: LinearLayout,
    ) {
        val buttons = item.arrayValue("buttons")?.objects()?.take(8).orEmpty()
        if (buttons.isEmpty()) return
        val renderId = templatePayload.stringValue("actionRenderId")
            ?: templatePayload.stringValue("renderId")
            ?: request.message.actions?.stringValue("renderId")
        val actions = LinearLayout(request.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(request.context, 10), 0, 0)
        }
        buttons.forEach { button ->
            val id = button.stringValue("id") ?: return@forEach
            val label = button.stringValue("label") ?: id
            val url = button.stringValue("url")
            val value = button.get("value") ?: button.get("payload")
            val actionValue = when {
                value == null || value.isJsonNull -> id
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                else -> value.toString()
            }
            actions.addView(Button(request.context).apply {
                text = label
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = rounded(request.context, TEAL, Color.TRANSPARENT, 12)
                isEnabled = url != null || !request.actionLocked(id)
                setOnClickListener {
                    if (url != null) request.openUrl(url)
                    else {
                        isEnabled = false
                        request.submitAction(id, value = actionValue, renderId = renderId)
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(request.context, 44)).apply {
                marginStart = dp(request.context, 6)
            })
        }
        if (actions.childCount > 0) parent.addView(actions, verticalSpacing(request.context))
    }

    private fun templateRoot(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 15), dp(context, 16), dp(context, 14))
        background = rounded(context, PALE, BORDER, 20)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, dp(context, 5), 0, dp(context, 8)) }
    }

    private fun sectionTitle(context: Context, value: String) =
        text(context, value, 19f, INK, bold = true)

    private fun text(context: Context, value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun verticalSpacing(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(context, 8) }

    private fun rounded(context: Context, fill: Int, stroke: Int, radiusDp: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(dp(context, 1), stroke)
        }

    private fun JsonObject.stringValue(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(String::isNotBlank)

    private fun JsonObject.arrayValue(key: String): JsonArray? =
        get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray

    private fun JsonArray.objects() = mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }

    private fun JsonElement?.objectValue() = this?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonElement.displayValue(): String = when {
        isJsonNull -> ""
        isJsonPrimitive -> asString
        else -> toString()
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    private val INK = Color.rgb(16, 42, 67)
    private val MUTED = Color.rgb(82, 101, 121)
    private val TEAL = Color.rgb(15, 118, 110)
    private val BORDER = Color.rgb(216, 229, 239)
    private val PALE = Color.rgb(247, 250, 252)
}
