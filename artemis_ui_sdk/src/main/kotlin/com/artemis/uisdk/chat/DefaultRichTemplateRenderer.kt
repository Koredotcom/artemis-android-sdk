package com.artemis.uisdk.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.text.Html
import android.text.Editable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.setPadding
import com.artemis.uisdk.ArtemisFeatureConfig
import com.artemis.uisdk.ArtemisThemeConfig
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URL
import java.net.URI
import java.util.concurrent.Executors

/** Built-in rich-content rendering. Layouts live in res/layout/artemis_template_*.xml. */
internal class DefaultRichTemplateRenderer(
    private val features: ArtemisFeatureConfig,
    private val theme: ArtemisThemeConfig,
    private val callbacks: ChatMessageAdapter.Callbacks,
) {
    fun renderRich(c: Context, holder: ChatMessageAdapter.Holder, message: ChatMessage, parent: LinearLayout, rich: JsonObject) {
        val order = com.artemis.uisdk.templates.ArtemisDefaultTemplates.types
        for (key in order) {
            val value = rich.get(key) ?: continue
            if (key == "markdown" && value.isJsonPrimitive && value.asString.trim() == message.text.trim()) continue
            val title = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
            val target = if (key == "markdown" || key == "quick_replies") parent else
                (android.view.LayoutInflater.from(c).inflate(com.artemis.uisdk.R.layout.artemis_template_card, parent, false) as LinearLayout)
            runCatching {
                when (key) {
                    "markdown" -> addText(c, target, parseMarkdown(c, value.asString))
                    "carousel" -> carousel(c, message, target, value.asJsonObject)
                    "image" -> image(c, target, value.asJsonObject)
                    "html" -> html(c, target, value.asString)
                    "video" -> video(c, target, value.asJsonObject)
                    "audio" -> audio(c, holder, target, value.asJsonObject)
                    "file" -> file(c, target, value.asJsonObject)
                    "list" -> list(c, target, value.asJsonObject)
                    "kpi" -> kpi(c, target, value.asJsonObject)
                    "table" -> table(c, target, value.asJsonObject)
                    "chart" -> chart(c, target, value.asJsonObject)
                    "form" -> form(c, message, target, value.asJsonObject)
                    "progress" -> progress(c, target, value.asJsonObject)
                    "feedback" -> feedback(c, message, target, value.asJsonObject)
                    "quick_replies" -> quickReplies(c, message, target, value.asJsonArray)
                    else -> fallback(c, target, title, value)
                }
            }.onFailure { target.removeAllViews(); addText(c, target, "$title is unavailable") }
            if (target !== parent && target.childCount > 0) parent.addView(target)
        }
    }

    fun renderActions(c: Context, message: ChatMessage, parent: LinearLayout, actions: JsonObject) {
        val elements = actions.getAsJsonArray("elements") ?: return
        val renderId = actions.str("renderId")
        val form = LinkedHashMap<String, String>()
        val pendingInputs = mutableListOf<Pair<JsonObject, EditText>>()
        val pendingSelects = mutableListOf<Pair<JsonObject, Spinner>>()
        for (element in elements.objects()) {
            val id = element.str("id") ?: continue
            val label = element.str("label") ?: continue
            when (element.str("type") ?: "button") {
                "button" -> actionButton(c, message, parent, id, label, element.str("value") ?: element.str("payload") ?: element.str("url") ?: id, renderId)
                "input" -> {
                    val edit = (android.view.LayoutInflater.from(c).inflate(com.artemis.uisdk.R.layout.artemis_template_field, parent, false) as EditText).apply { hint = element.str("placeholder") ?: label; setSingleLine(); setText(element.str("value")) }
                    parent.addView(edit, matchWrap(c))
                    if (actions.str("submit_id")?.isNotBlank() == true) pendingInputs.add(element to edit)
                    else edit.setOnEditorActionListener { _, _, _ ->
                        val value = edit.text.toString().trim()
                        if (value.isNotEmpty() || !element.bool("required")) callbacks.onAction(message, id, value, null, renderId)
                        true
                    }
                }
                "select" -> {
                    val options = element.array("options").objects().toList()
                    val spinner = Spinner(c).apply {
                        adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, options.map { it.str("label") ?: it.str("id").orEmpty() })
                    }
                    parent.addView(spinner, matchWrap(c))
                    if (actions.str("submit_id")?.isNotBlank() == true) pendingSelects.add(element to spinner)
                    else spinner.onItemSelectedListener = SimpleSelectionListener { index -> options.getOrNull(index)?.str("id")?.let { callbacks.onAction(message, id, it, null, renderId) } }
                }
            }
        }
        val submitId = actions.str("submit_id")
        if (!submitId.isNullOrBlank()) {
            val label = actions.str("submit_label") ?: "Submit"
            actionButton(c, message, parent, submitId, label, null, renderId) {
                pendingInputs.forEach { (field, edit) ->
                    val id = field.str("id") ?: return@forEach
                    val value = edit.text.toString().trim()
                    if (field.bool("required") && value.isEmpty()) { edit.error = "Required"; return@actionButton }
                    form[id] = value
                }
                pendingSelects.forEach { (field, spinner) ->
                    val opts = field.array("options").objects().toList()
                    val id = field.str("id") ?: return@forEach
                    form[id] = opts.getOrNull(spinner.selectedItemPosition)?.str("id").orEmpty()
                }
                callbacks.onAction(message, submitId, gsonEncode(form), form, renderId)
            }
        }
    }

    private fun actionButton(c: Context, message: ChatMessage, parent: LinearLayout, id: String,
                             label: String, value: String?, renderId: String?, onClick: (() -> Unit)? = null) {
        val button = templateButton(c, parent).apply { text = label; isEnabled = !callbacks.isLocked(message, id) }
        button.setOnClickListener { if (onClick != null) onClick() else { button.isEnabled = false; callbacks.onAction(message, id, value, null, renderId) } }
        parent.addView(button, matchWrap(c))
    }

    private fun carousel(c: Context, message: ChatMessage, parent: LinearLayout, data: JsonObject) {
        val cards = data.array("cards")
        if (!features.carousel) { cards.objects().forEach { addText(c, parent, it.str("title").orEmpty()) }; return }
        val scroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
        val strip = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for (card in cards.objects()) {
            val col = android.view.LayoutInflater.from(c).inflate(com.artemis.uisdk.R.layout.artemis_template_card, strip, false) as LinearLayout
            addText(c, col, card.str("title").orEmpty(), bold = true)
            card.str("subtitle")?.let { addText(c, col, it) }
            card.str("image_url")?.let { image(c, col, JsonObject().apply { addProperty("url", it) }) }
            card.array("buttons").objects().forEach { button ->
                val id = button.str("id") ?: return@forEach
                actionButton(c, message, col, id, button.str("label") ?: id, button.str("label") ?: id, null)
            }
            card.str("default_action_url")?.let { url -> col.setOnClickListener { openUrl(c, url) } }
            strip.addView(col, LinearLayout.LayoutParams(c.dp(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = c.dp(8) })
        }
        scroll.addView(strip); parent.addView(scroll, matchWrap(c))
    }

    private fun image(c: Context, parent: LinearLayout, data: JsonObject) {
        val url = data.str("url") ?: return
        val view = (android.view.LayoutInflater.from(c).inflate(com.artemis.uisdk.R.layout.artemis_template_image, parent, false) as ImageView).apply { contentDescription = data.str("alt") ?: data.str("caption") ?: "Image" }
        loadImage(url) { bitmap -> if (bitmap != null) view.setImageBitmap(bitmap) else view.contentDescription = "Image could not be loaded" }
        parent.addView(view, LinearLayout.LayoutParams(-1, c.dp(190)))
        data.str("caption")?.let { addText(c, parent, it) }
    }

    private fun html(c: Context, parent: LinearLayout, source: String) {
        val safe = source.replace(Regex("(?is)<script.*?>.*?</script>"), "").replace(Regex("(?i)on[a-z]+\\s*=\\s*(['\"]).*?\\1"), "")
        val text = TextView(c).apply { this.text = Html.fromHtml(safe, Html.FROM_HTML_MODE_COMPACT); movementMethod = LinkMovementMethod.getInstance() }
        parent.addView(text, matchWrap(c))
    }

    private fun video(c: Context, parent: LinearLayout, data: JsonObject) {
        val url = data.str("url") ?: return
        if (!safeUrl(url)) { addText(c, parent, "Invalid video link"); return }
        val video = VideoView(c).apply { setVideoURI(Uri.parse(url)); setOnPreparedListener { it.isLooping = false }; setOnErrorListener { _, _, _ -> addText(c, parent, "Video could not be played"); true } }
        val control = templateButton(c, parent).apply { text = "Play video"; setOnClickListener { video.start() } }
        parent.addView(video, LinearLayout.LayoutParams(-1, c.dp(190))); parent.addView(control, matchWrap(c))
    }

    private fun audio(c: Context, holder: ChatMessageAdapter.Holder, parent: LinearLayout, data: JsonObject) {
        val url = data.str("url") ?: return
        val button = templateButton(c, parent).apply { text = "Play audio" }
        button.setOnClickListener {
            if (!safeUrl(url)) { button.text = "Invalid audio link"; return@setOnClickListener }
            runCatching {
                holder.release()
                val player = MediaPlayer(); holder.player = player
                player.setDataSource(url); player.setOnPreparedListener { it.start(); button.text = "Playing audio…" }
                player.setOnCompletionListener { button.text = "Play audio" }
                player.setOnErrorListener { _, _, _ -> button.text = "Audio unavailable"; true }
                player.prepareAsync()
            }.onFailure { button.text = "Audio unavailable" }
        }
        parent.addView(button, matchWrap(c)); data.str("caption")?.let { addText(c, parent, it) }
    }

    private fun file(c: Context, parent: LinearLayout, data: JsonObject) {
        val name = data.str("filename")?.takeIf(String::isNotBlank) ?: "File"
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(c).apply {
            text = name; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(c.getColor(com.artemis.uisdk.R.color.artemis_text))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val open = templateButton(c, row).apply { text = "Open"; setOnClickListener { openUrl(c, data.str("url").orEmpty()) } }
        row.addView(open)
        parent.addView(row, matchWrap(c))
        data.long("size_bytes")?.takeIf { it >= 0 }?.let { addText(c, parent, android.text.format.Formatter.formatShortFileSize(c, it)) }
    }

    private fun list(c: Context, parent: LinearLayout, data: JsonObject) {
        data.str("title")?.let { addText(c, parent, it, bold = true) }
        data.array("items").objects().take(100).forEach { item ->
            val title = item.str("title")?.takeIf(String::isNotBlank) ?: return@forEach
            val row = android.view.LayoutInflater.from(c).inflate(com.artemis.uisdk.R.layout.artemis_template_list_item, parent, false)
            row.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_list_title).text = title
            row.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_list_subtitle).apply {
                text = item.str("subtitle")
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            if (item.str("default_action_url") != null) row.setOnClickListener { openUrl(c, item.str("default_action_url").orEmpty()) }
            parent.addView(row, matchWrap(c))
        }
    }

    private fun kpi(c: Context, parent: LinearLayout, data: JsonObject) {
        val label = data.str("label") ?: return
        val value = data.get("value")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val card = android.view.LayoutInflater.from(c).inflate(com.artemis.uisdk.R.layout.artemis_template_kpi, parent, false)
        card.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_kpi_label).text = label
        card.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_kpi_value).text = value + (data.str("unit")?.let { " $it" } ?: "")
        card.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_kpi_trend).apply {
            text = data.str("trend")
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        parent.addView(card)
    }

    private fun table(c: Context, parent: LinearLayout, data: JsonObject) {
        val columns = data.array("columns").objects().filter { it.str("key") != null }
        val rows = data.array("rows").objects()
        if (columns.isEmpty() || rows.isEmpty()) return
        val limit = (data.int("max_visible_rows") ?: 10).coerceAtLeast(1).coerceAtMost(100)
        var expanded = false
        val wrapper = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        fun drawRows() {
            wrapper.removeAllViews()
            val horizontal = HorizontalScrollView(c)
            val table = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            fun row(values: List<String>, bold: Boolean) {
                val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                values.forEach { value ->
                    val cell = TextView(c).apply { text = value; textSize = 13f; setTextColor(c.getColor(com.artemis.uisdk.R.color.artemis_text)); setPadding(c.dp(12)); if (bold) { setTypeface(typeface, Typeface.BOLD); setBackgroundColor(c.getColor(com.artemis.uisdk.R.color.artemis_surface)) } }
                    line.addView(cell, LinearLayout.LayoutParams(c.dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                table.addView(line)
            }
            row(columns.map { it.str("header").orEmpty() }, true)
            rows.take(if (expanded) 100 else limit).forEach { r -> row(columns.map { col -> r.get(col.str("key"))?.let(::scalar) ?: "" }, false) }
            horizontal.addView(table); wrapper.addView(horizontal)
            if (rows.size > limit) wrapper.addView(templateButton(c, wrapper).apply { text = if (expanded) "Show less" else "Show more"; setOnClickListener { expanded = !expanded; drawRows() } })
        }
        drawRows(); parent.addView(wrapper, matchWrap(c))
    }

    private fun chart(c: Context, parent: LinearLayout, data: JsonObject) {
        val points = data.array("data").objects().take(100).mapNotNull { p ->
            val value = p.get("value")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return@mapNotNull null
            Triple(p.str("label") ?: "", value, p.str("color"))
        }
        if (points.isEmpty()) { addText(c, parent, "No chart data"); return }
        data.str("title")?.let { addText(c, parent, it, bold = true) }
        val type = data.str("type")
        parent.addView(object : View(c) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val max = points.maxOf { it.second }.let { if (it == 0.0) 1.0 else it }
                val each = width.toFloat() / points.size
                if (type == "line") {
                    paint.color = color(theme.primary, 0xFF2563EB.toInt()); paint.strokeWidth = c.dp(3).toFloat(); paint.style = Paint.Style.STROKE
                    val path = android.graphics.Path()
                    points.forEachIndexed { i, p -> val x = each * (i + .5f); val y = height - (p.second / max * (height - c.dp(24))).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                    canvas.drawPath(path, paint)
                } else {
                    paint.style = Paint.Style.FILL
                    points.forEachIndexed { i, p -> paint.color = parseColor(p.third, color(theme.primary, 0xFF2563EB.toInt())); val barHeight = (p.second / max * (height - c.dp(24))).toFloat(); canvas.drawRect(each * i + c.dp(5), height - barHeight, each * (i + 1) - c.dp(5), height.toFloat(), paint) }
                }
            }
        }, LinearLayout.LayoutParams(-1, c.dp(160)))
        addText(c, parent, points.joinToString(" · ") { "${it.first}: ${it.second}" })
    }

    private fun form(c: Context, message: ChatMessage, parent: LinearLayout, data: JsonObject) {
        data.str("title")?.let { addText(c, parent, it, bold = true) }
        val fields = data.array("fields").objects().take(50)
        val inputs = linkedMapOf<String, EditText>()
        val selects = linkedMapOf<String, Pair<Spinner, List<JsonObject>>>()
        var invalid = false
        fields.forEach { field ->
            val id = field.str("id") ?: return@forEach
            if (inputs.containsKey(id) || selects.containsKey(id)) invalid = true
            val label = field.str("label") ?: id
            addText(c, parent, label + if (field.bool("required")) " *" else "")
            if (field.str("type") == "select") {
                val opts = field.array("options").objects().toList()
                val spinner = Spinner(c).apply { adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, opts.map { it.str("label") ?: it.str("id").orEmpty() }) }
                parent.addView(spinner, matchWrap(c)); selects[id] = spinner to opts
            } else {
                val edit = (android.view.LayoutInflater.from(c).inflate(com.artemis.uisdk.R.layout.artemis_template_field, parent, false) as EditText).apply {
                    hint = field.str("placeholder") ?: label
                    setSingleLine()
                    inputType = when (field.str("input_type") ?: field.str("type")) {
                        "email" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        "number" -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        "tel", "phone" -> android.text.InputType.TYPE_CLASS_PHONE
                        else -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    }
                    setText(field.str("value"))
                    if ((field.str("input_type") ?: field.str("type")) == "date") {
                        isFocusable = false
                        setOnClickListener {
                            val today = java.util.Calendar.getInstance()
                            android.app.DatePickerDialog(c, { _, year, month, day ->
                                setText(String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day))
                            }, today.get(java.util.Calendar.YEAR), today.get(java.util.Calendar.MONTH), today.get(java.util.Calendar.DAY_OF_MONTH)).show()
                        }
                    }
                }
                parent.addView(edit, matchWrap(c)); inputs[id] = edit
            }
        }
        if (invalid || fields.isEmpty()) { addText(c, parent, "This form contains unsupported fields"); return }
        val button = Button(c).apply {
            text = data.str("submit_label") ?: "Submit"
            isEnabled = message.serverId != null && !callbacks.isLocked(message, "form-submit")
            setOnClickListener {
                val values = linkedMapOf<String, String>()
                inputs.forEach { (id, edit) ->
                    val required = fields.firstOrNull { it.str("id") == id }?.bool("required") == true
                    val value = edit.text.toString().trim()
                    if (required && value.isEmpty()) { edit.error = "Required"; return@setOnClickListener }
                    values[id] = value
                }
                selects.forEach { (id, pair) -> values[id] = pair.second.getOrNull(pair.first.selectedItemPosition)?.str("id").orEmpty() }
                callbacks.onAction(message, "form-submit", gsonEncode(values), values, null)
                isEnabled = false
            }
        }
        parent.addView(button, matchWrap(c))
    }

    private fun progress(c: Context, parent: LinearLayout, data: JsonObject) {
        val value = data.double("value") ?: return
        val maxValue = data.double("max") ?: 100.0
        if (maxValue <= 0 || !maxValue.isFinite()) { addText(c, parent, "Progress unavailable"); return }
        data.str("label")?.let { addText(c, parent, it) }
        val layout = if (data.str("variant") == "circle") com.artemis.uisdk.R.layout.artemis_template_progress_circle
            else com.artemis.uisdk.R.layout.artemis_template_progress
        val progress = android.view.LayoutInflater.from(c).inflate(layout, parent, false) as ProgressBar
        val percent = (value / maxValue * 100).toInt().coerceIn(0, 100)
        progress.max = 100
        progress.progress = percent
        progress.progressTintList = android.content.res.ColorStateList.valueOf(color(theme.primary, c.getColor(com.artemis.uisdk.R.color.artemis_primary)))
        progress.contentDescription = "${data.str("label") ?: "Progress"}: $percent%"
        parent.addView(progress)
        addText(c, parent, "$percent%")
    }

    private fun feedback(c: Context, message: ChatMessage, parent: LinearLayout, data: JsonObject) {
        addText(c, parent, data.str("prompt") ?: return)
        val type = data.str("type") ?: "stars"
        val group = com.google.android.material.chip.ChipGroup(c)
        if (type == "thumbs") {
            listOf("👍" to 1, "👎" to 0).forEach { (label, rating) ->
                group.addView(templateChip(c, parent).apply {
                    text = label; isEnabled = message.serverId != null && !callbacks.isLocked(message, "feedback")
                    setOnClickListener { callbacks.onFeedback(message, "thumbs", rating, null); group.children().forEach { it.isEnabled = false } }
                })
            }
        } else {
            val max = (data.int("max") ?: 5).coerceIn(1, 10)
            for (rating in 1..max) group.addView(Button(c).apply {
                text = "★ $rating"; isEnabled = message.serverId != null && !callbacks.isLocked(message, "feedback")
                setOnClickListener { callbacks.onFeedback(message, "star", rating, null); group.children().forEach { it.isEnabled = false } }
            })
        }
        parent.addView(group)
    }

    private fun quickReplies(c: Context, message: ChatMessage, parent: LinearLayout, items: JsonArray) {
        val row = com.google.android.material.chip.ChipGroup(c)
        items.objects().take(20).forEach { item ->
            val id = item.str("id") ?: return@forEach
            val label = item.str("label") ?: return@forEach
            row.addView(templateChip(c, parent).apply {
                text = label; isEnabled = !callbacks.isLocked(message, "quick_replies")
                setOnClickListener { callbacks.onAction(message, id, label, null, null); row.children().forEach { it.isEnabled = false } }
            })
        }
        parent.addView(row, matchWrap(c))
    }

    private fun fallback(c: Context, parent: LinearLayout, title: String, value: JsonElement) {
        val text = mutableListOf<String>()
        fun walk(node: JsonElement, depth: Int) {
            if (depth > 8 || text.sumOf(String::length) > 2000) return
            when {
                node.isJsonObject -> node.asJsonObject.entrySet().forEach { (key, child) ->
                    if (key in setOf("text", "title", "label", "description", "content") && child.isJsonPrimitive && child.asJsonPrimitive.isString) text.add(child.asString)
                    else walk(child, depth + 1)
                }
                node.isJsonArray -> node.asJsonArray.forEach { walk(it, depth + 1) }
            }
        }
        walk(value, 0)
        addText(c, parent, title, bold = true)
        addText(c, parent, text.joinToString("\n").take(2000).ifBlank { "Content is available in another channel" })
    }

    private fun isCompact(message: ChatMessage): Boolean {
        val rich = message.richContent ?: return false
        return (rich.has("kpi") || rich.has("progress")) && rich.entrySet().all { it.key in setOf("kpi", "progress") }
    }

    private fun addText(c: Context, parent: LinearLayout, text: CharSequence, bold: Boolean = false) {
        parent.addView(TextView(c).apply { this.text = text; textSize = 14f; setTextColor(c.getColor(com.artemis.uisdk.R.color.artemis_text)); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(c.dp(4), c.dp(3), c.dp(4), c.dp(3)) }, matchWrap(c))
    }

    private fun openUrl(c: Context, url: String) {
        if (!safeUrl(url)) return
        try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: ActivityNotFoundException) { }
    }

    private fun safeUrl(url: String): Boolean = runCatching { Uri.parse(url).scheme.equals("https", true) && Uri.parse(url).host != null }.getOrDefault(false)

    private fun loadImage(url: String, done: (android.graphics.Bitmap?) -> Unit) {
        if (!safeUrl(url)) { done(null); return }
        imageExecutor.execute {
            val bitmap = runCatching {
                val connection = URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                connection.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(bitmap) }
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun color(value: String?, fallback: Int) = runCatching { Color.parseColor(value) }.getOrDefault(fallback)
    private fun parseColor(value: String?, fallback: Int) = color(value, fallback)
    private fun matchWrap(c: Context) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(3); bottomMargin = c.dp(3) }
    private fun gsonEncode(value: Any) = com.google.gson.Gson().toJson(value)
    private fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.int(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asInt
    private fun JsonObject.long(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asLong
    private fun JsonObject.double(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asDouble
    private fun JsonObject.bool(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    private fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonElement?.objects() = this?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject } ?: emptyList()
    private fun scalar(value: JsonElement) = if (value.isJsonPrimitive) value.asString else value.toString()
    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun Context.dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    fun markdownHtml(text: String): String {
        val html = StringBuilder()
        val paragraph = mutableListOf<String>()
        val list = mutableListOf<Pair<String, String>>()
        var listType: Char? = null
        val quote = mutableListOf<String>()
        val code = mutableListOf<String>()
        var inCode = false

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                html.append("<p>").append(paragraph.joinToString("<br>") { markdownInline(it) }).append("</p>")
                paragraph.clear()
            }
        }
        fun flushList() {
            if (list.isNotEmpty()) {
                html.append("<p>")
                list.forEachIndexed { index, item ->
                    if (index > 0) html.append("<br>")
                    html.append("<artemis-list-item>")
                        .append("&nbsp;&nbsp;&nbsp;&nbsp;")
                        .append(if (listType == 'o') "${item.first}. " else "• ")
                        .append(markdownInline(item.second))
                        .append("</artemis-list-item>")
                }
                html.append("</p>")
                list.clear()
            }
            listType = null
        }
        fun flushQuote() {
            if (quote.isNotEmpty()) {
                html.append("<p><artemis-blockquote>")
                    .append(quote.joinToString("<br>") { "┃&nbsp; " + markdownInline(it) })
                    .append("</artemis-blockquote></p>")
                quote.clear()
            }
        }
        fun flushBlocks() {
            flushParagraph()
            flushList()
            flushQuote()
        }

        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
                flushBlocks()
                if (inCode) {
                    html.append("<p><artemis-code-block>")
                        .append(code.joinToString("\n") { android.text.Html.escapeHtml(it) })
                        .append("</artemis-code-block></p>")
                    code.clear()
                }
                inCode = !inCode
                return@forEach
            }
            if (inCode) {
                code += line
                return@forEach
            }
            if (line.isBlank()) {
                flushBlocks()
                return@forEach
            }
            val heading = Regex("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$").matchEntire(line)
            if (heading != null) {
                flushBlocks()
                val level = heading.groupValues[1].length
                html.append("<p><artemis-h$level>").append(markdownInline(heading.groupValues[2]))
                    .append("</artemis-h$level></p>")
                return@forEach
            }
            if (line.trim().matches(Regex("([-*_])\\1{2,}"))) {
                flushBlocks()
                html.append("<hr>")
                return@forEach
            }
            val quoteMatch = Regex("^ {0,3}>\\s?(.*)$").matchEntire(line)
            if (quoteMatch != null) {
                flushParagraph()
                flushList()
                quote += quoteMatch.groupValues[1]
                return@forEach
            }
            flushQuote()
            val ordered = Regex("^\\s{0,3}(\\d{1,9})[.)]\\s+(.+)$").matchEntire(line)
            val unordered = Regex("^\\s{0,3}[-+*]\\s+(.+)$").matchEntire(line)
            if (ordered != null || unordered != null) {
                flushParagraph()
                val type = if (ordered != null) 'o' else 'u'
                if (listType != null && listType != type) flushList()
                listType = type
                list += if (ordered != null) ordered.groupValues[1] to ordered.groupValues[2]
                    else "" to unordered!!.groupValues[1]
                return@forEach
            }
            flushList()
            paragraph += line
        }
        if (inCode) {
            html.append("<p><artemis-code-block>")
                .append(code.joinToString("\n") { android.text.Html.escapeHtml(it) })
                .append("</artemis-code-block></p>")
        }
        flushBlocks()
        return html.toString()
    }

    fun parseMarkdown(context: Context, text: String): Spanned {
        val accent = color(theme.primary, context.getColor(com.artemis.uisdk.R.color.artemis_primary))
        return Html.fromHtml(
            markdownHtml(text),
            Html.FROM_HTML_MODE_COMPACT,
            null,
            MarkdownTagHandler(context, accent),
        )
    }

    private fun markdownInline(source: String): String {
        val placeholders = mutableListOf<String>()
        fun protect(value: String): String {
            val token = "${'$'}ARTEMIS${placeholders.size}${'$'}"
            placeholders += value
            return token
        }
        var line = source
        line = Regex("`([^`]+)`").replace(line) { match ->
            protect("<artemis-code>${android.text.Html.escapeHtml(match.groupValues[1])}</artemis-code>")
        }
        line = Regex("!?\\[([^]]+)]\\(([^ )]+)(?:\\s+[\"']([^\"']*)[\"'])?\\)").replace(line) { match ->
            val label = android.text.Html.escapeHtml(match.groupValues[1])
            val destination = match.groupValues[2]
            val uri = runCatching { URI(destination) }.getOrNull()
            if (match.value.startsWith("!")) {
                protect(label)
            } else if (uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) {
                val href = android.text.Html.escapeHtml(destination.replace("\"", "%22"))
                protect("<a href=\"$href\">$label</a>")
            } else {
                protect(label)
            }
        }
        var escaped = android.text.Html.escapeHtml(line)
        escaped = escaped
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("(?<!\\*)\\*([^*]+?)\\*(?!\\*)"), "<i>$1</i>")
            .replace(Regex("(?<!_)_([^_]+?)_(?!_)"), "<i>$1</i>")
            .replace(Regex("~~(.+?)~~"), "<del>$1</del>")
        placeholders.forEachIndexed { index, replacement ->
            escaped = escaped.replace("${'$'}ARTEMIS$index${'$'}", replacement)
        }
        return escaped
    }

    private class SimpleSelectionListener(private val selected: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun ViewGroup.children() = (0 until childCount).map { getChildAt(it) }


    private fun templateChip(c: Context, parent: ViewGroup): com.google.android.material.chip.Chip =
        (android.view.LayoutInflater.from(c).inflate(com.artemis.uisdk.R.layout.artemis_template_chip, parent, false) as com.google.android.material.chip.Chip).apply {
            val accent = android.content.res.ColorStateList.valueOf(color(theme.primary, c.getColor(com.artemis.uisdk.R.color.artemis_primary)))
            setTextColor(accent)
            chipStrokeColor = accent
        }

    private fun templateButton(c: Context, parent: ViewGroup): Button =
        (android.view.LayoutInflater.from(c).inflate(com.artemis.uisdk.R.layout.artemis_template_action, parent, false) as Button).apply {
            setTextColor(color(theme.primary, c.getColor(com.artemis.uisdk.R.color.artemis_primary)))
        }

    companion object {
        private val imageExecutor = Executors.newFixedThreadPool(2)
    }
}
