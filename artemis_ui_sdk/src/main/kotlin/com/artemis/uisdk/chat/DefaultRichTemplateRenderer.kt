package com.artemis.uisdk.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.MediaPlayer
import android.text.Html
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.VideoView
import androidx.core.net.toUri
import androidx.core.view.isNotEmpty
import androidx.core.view.setPadding
import com.artemis.uisdk.ArtemisFeatureConfig
import com.artemis.uisdk.ArtemisThemeConfig
import com.artemis.uisdk.R
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

/** Built-in rich-content rendering. Layouts live in res/layout/artemis_template_*.xml. */
internal class DefaultRichTemplateRenderer(
    private val features: ArtemisFeatureConfig,
    private val theme: ArtemisThemeConfig,
    private val callbacks: ChatMessageAdapter.Callbacks,
) {
    private data class ChartPoint(val label: String, val value: Double, val color: String?)

    fun renderRich(context: Context, messageViewHolder: ChatMessageAdapter.ChatMessageViewHolder, message: ChatMessage, parent: LinearLayout, richContentObject: JsonObject) {
        val templateRenderOrder = com.artemis.uisdk.templates.ArtemisDefaultTemplates.types
        for (templateType in templateRenderOrder) {
            val templatePayload = richContentObject.get(templateType) ?: continue
            if (templateType == "markdown" && templatePayload.isJsonPrimitive && templatePayload.asString.trim() == message.text.trim()) continue
            val templateTitle = templateType.replace('_', ' ').replaceFirstChar { it.uppercase() }
            val templateContainer = if (templateType == "markdown" || templateType == "quick_replies") parent else
                (android.view.LayoutInflater.from(context).inflate(R.layout.artemis_template_card, parent, false) as LinearLayout)
            runCatching {
                when (templateType) {
                    "markdown" -> addText(context, templateContainer, parseMarkdown(context, templatePayload.asString))
                    "carousel" -> carousel(context, message, templateContainer, templatePayload.asJsonObject)
                    "image" -> image(context, templateContainer, templatePayload.asJsonObject)
                    "html" -> html(context, templateContainer, templatePayload.asString)
                    "video" -> video(context, templateContainer, templatePayload.asJsonObject)
                    "audio" -> audio(context, messageViewHolder, templateContainer, templatePayload.asJsonObject)
                    "file" -> file(context, templateContainer, templatePayload.asJsonObject)
                    "list" -> renderList(context, templateContainer, templatePayload.asJsonObject)
                    "kpi" -> kpi(context, templateContainer, templatePayload.asJsonObject)
                    "table" -> table(context, templateContainer, templatePayload.asJsonObject)
                    "chart" -> chart(context, templateContainer, templatePayload.asJsonObject)
                    "form" -> form(context, message, templateContainer, templatePayload.asJsonObject)
                    "progress" -> progress(context, templateContainer, templatePayload.asJsonObject)
                    "feedback" -> feedback(context, message, templateContainer, templatePayload.asJsonObject)
                    "quick_replies" -> quickReplies(context, message, templateContainer, templatePayload.asJsonArray)
                    else -> fallback(context, templateContainer, templateTitle, templatePayload)
                }
            }.onFailure {
                templateContainer.removeAllViews()
                addText(context, templateContainer, "$templateTitle is unavailable")
            }
            if (templateContainer !== parent && templateContainer.isNotEmpty()) parent.addView(templateContainer)
        }
    }

    fun renderActions(context: Context, message: ChatMessage, parent: LinearLayout, actions: JsonObject) {
        val actionElements = actions.getAsJsonArray("elements") ?: return
        val renderIdentifier = actions.readString("renderId")
        val formValues = LinkedHashMap<String, String>()
        val pendingInputs = mutableListOf<Pair<JsonObject, EditText>>()
        val pendingSelects = mutableListOf<Pair<JsonObject, Spinner>>()
        for (actionElement in actionElements.objects()) {
            val actionIdentifier = actionElement.readString("id") ?: continue
            val actionLabel = actionElement.readString("label") ?: continue
            when (actionElement.readString("type") ?: "button") {
                "button" -> actionButton(context, message, parent, actionIdentifier, actionLabel,
                    actionElement.readString("value") ?: actionElement.readString("payload") ?: actionElement.readString("url") ?: actionIdentifier,
                    renderIdentifier)
                "input" -> {
                    val messageInput = (android.view.LayoutInflater.from(context).inflate(R.layout.artemis_template_field, parent, false) as EditText).apply {
                        hint = actionElement.readString("placeholder") ?: actionLabel
                        setSingleLine()
                        setText(actionElement.readString("value"))
                    }
                    parent.addView(messageInput, createMatchParentWrapContentLayoutParams(context))
                    if (actions.readString("submit_id")?.isNotBlank() == true) pendingInputs.add(actionElement to messageInput)
                    else messageInput.setOnEditorActionListener { _, _, _ ->
                        val enteredActionValue = messageInput.text.toString().trim()
                        if (enteredActionValue.isNotEmpty() || !actionElement.readBoolean("required")) {
                            callbacks.onAction(message, actionIdentifier, enteredActionValue, null, renderIdentifier)
                        }
                        true
                    }
                }
                "select" -> {
                    val selectionOptions = actionElement.readArray("options").objects().toList()
                    val selectionSpinner = Spinner(context).apply {
                        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, selectionOptions.map { option ->
                            option.readString("label") ?: option.readString("id").orEmpty()
                        })
                    }
                    parent.addView(selectionSpinner, createMatchParentWrapContentLayoutParams(context))
                    if (actions.readString("submit_id")?.isNotBlank() == true) pendingSelects.add(actionElement to selectionSpinner)
                    else selectionSpinner.onItemSelectedListener = SimpleSelectionListener { selectedIndex ->
                        selectionOptions.getOrNull(selectedIndex)?.readString("id")?.let { selectedOptionId ->
                            callbacks.onAction(message, actionIdentifier, selectedOptionId, null, renderIdentifier)
                        }
                    }
                }
            }
        }
        val submitActionIdentifier = actions.readString("submit_id")
        if (!submitActionIdentifier.isNullOrBlank()) {
            val submitButtonLabel = actions.readString("submit_label") ?: "Submit"
            actionButton(context, message, parent, submitActionIdentifier, submitButtonLabel, null, renderIdentifier) {
                pendingInputs.forEach { (formField, formInput) ->
                    val formFieldIdentifier = formField.readString("id") ?: return@forEach
                    val enteredFieldValue = formInput.text.toString().trim()
                    if (formField.readBoolean("required") && enteredFieldValue.isEmpty()) {
                        formInput.error = "Required"
                        return@actionButton
                    }
                    formValues[formFieldIdentifier] = enteredFieldValue
                }
                pendingSelects.forEach { (formField, selectionSpinner) ->
                    val selectionOptions = formField.readArray("options").objects().toList()
                    val formFieldIdentifier = formField.readString("id") ?: return@forEach
                    formValues[formFieldIdentifier] = selectionOptions.getOrNull(selectionSpinner.selectedItemPosition)
                        ?.readString("id").orEmpty()
                }
                callbacks.onAction(message, submitActionIdentifier, encodeFormValues(formValues), formValues, renderIdentifier)
            }
        }
    }

    private fun actionButton(context: Context, message: ChatMessage, parent: LinearLayout, actionIdentifier: String,
                             actionLabel: String, actionValue: String?, renderIdentifier: String?, onClick: (() -> Unit)? = null) {
        val actionButtonView = templateButton(context, parent).apply {
            text = actionLabel
            isEnabled = !callbacks.isLocked(message, actionIdentifier)
        }
        actionButtonView.setOnClickListener {
            if (onClick != null) onClick()
            else {
                actionButtonView.isEnabled = false
                callbacks.onAction(message, actionIdentifier, actionValue, null, renderIdentifier)
            }
        }
        parent.addView(actionButtonView, createMatchParentWrapContentLayoutParams(context))
    }

    private fun carousel(context: Context, message: ChatMessage, parent: LinearLayout, data: JsonObject) {
        val cards = data.readArray("cards")
        if (!features.carousel) { cards.objects().forEach { addText(context, parent, it.readString("title").orEmpty()) }; return }
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (card in cards.objects()) {
            val carouselCardLayout = android.view.LayoutInflater.from(context).inflate(R.layout.artemis_template_card, strip, false) as LinearLayout
            addText(context, carouselCardLayout, card.readString("title").orEmpty(), bold = true)
            card.readString("subtitle")?.let { subtitle -> addText(context, carouselCardLayout, subtitle) }
            card.readString("image_url")?.let { imageUrl -> image(context, carouselCardLayout, JsonObject().apply { addProperty("url", imageUrl) }) }
            card.readArray("buttons").objects().forEach { button ->
                val actionIdentifier = button.readString("id") ?: return@forEach
                actionButton(context, message, carouselCardLayout, actionIdentifier,
                    button.readString("label") ?: actionIdentifier, button.readString("label") ?: actionIdentifier, null)
            }
            card.readString("default_action_url")?.let { actionUrl ->
                carouselCardLayout.setOnClickListener { openUrl(context, actionUrl) }
            }
            strip.addView(carouselCardLayout, LinearLayout.LayoutParams(context.densityIndependentPixelsToPixels(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.densityIndependentPixelsToPixels(8) })
        }
        scroll.addView(strip); parent.addView(scroll, createMatchParentWrapContentLayoutParams(context))
    }

    private fun image(context: Context, parent: LinearLayout, data: JsonObject) {
        val url = data.readString("url") ?: return
        val templateImageView = (android.view.LayoutInflater.from(context).inflate(R.layout.artemis_template_image, parent, false) as ImageView).apply { contentDescription = data.readString("alt") ?: data.readString("caption") ?: "Image" }
        loadImage(url) { decodedBitmap ->
            if (decodedBitmap != null) templateImageView.setImageBitmap(decodedBitmap)
            else templateImageView.contentDescription = "Image could not be loaded"
        }
        parent.addView(templateImageView, LinearLayout.LayoutParams(-1, context.densityIndependentPixelsToPixels(190)))
        data.readString("caption")?.let { addText(context, parent, it) }
    }

    private fun html(context: Context, parent: LinearLayout, source: String) {
        val sanitizedHtml = source.replace(Regex("(?is)<script.*?>.*?</script>"), "").replace(Regex("(?i)on[a-z]+\\s*=\\s*(['\"]).*?\\1"), "")
        val htmlTextView = TextView(context).apply {
            text = Html.fromHtml(sanitizedHtml, Html.FROM_HTML_MODE_COMPACT)
            movementMethod = LinkMovementMethod.getInstance()
        }
        parent.addView(htmlTextView, createMatchParentWrapContentLayoutParams(context))
    }

    private fun video(context: Context, parent: LinearLayout, data: JsonObject) {
        val url = data.readString("url") ?: return
        if (!safeUrl(url)) { addText(context, parent, "Invalid video link"); return }
        val videoPlayerView = VideoView(context).apply {
            setVideoURI(url.toUri())
            setOnPreparedListener { mediaPlayer -> mediaPlayer.isLooping = false }
            setOnErrorListener { _, _, _ -> addText(context, parent, "Video could not be played"); true }
        }
        val videoPlaybackButton = templateButton(context, parent).apply {
            text = context.getString(R.string.play_video)
            setOnClickListener { videoPlayerView.start() }
        }
        parent.addView(videoPlayerView, LinearLayout.LayoutParams(-1, context.densityIndependentPixelsToPixels(190)))
        parent.addView(videoPlaybackButton, createMatchParentWrapContentLayoutParams(context))
    }

    private fun audio(context: Context, messageViewHolder: ChatMessageAdapter.ChatMessageViewHolder, parent: LinearLayout, data: JsonObject) {
        val url = data.readString("url") ?: return
        val button = templateButton(context, parent).apply { text = context.getString(R.string.play_audio) }
        button.setOnClickListener {
            if (!safeUrl(url)) { button.text = context.getString(R.string.invalid_audio_link); return@setOnClickListener }
            runCatching {
                messageViewHolder.releaseMediaPlayer()
                val mediaPlayer = MediaPlayer(); messageViewHolder.mediaPlayer = mediaPlayer
                mediaPlayer.setDataSource(url); mediaPlayer.setOnPreparedListener { preparedMediaPlayer -> preparedMediaPlayer.start(); button.text =
                context.getString(
                    R.string.playing_audio
                ) }
                mediaPlayer.setOnCompletionListener { button.text =
                    context.getString(R.string.play_audio) }
                mediaPlayer.setOnErrorListener { _, _, _ -> button.text = context.getString(R.string.audio_unavailable); true }
                mediaPlayer.prepareAsync()
            }.onFailure { button.text = context.getString(R.string.audio_unavailable) }
        }
        parent.addView(button, createMatchParentWrapContentLayoutParams(context)); data.readString("caption")?.let { addText(context, parent, it) }
    }

    private fun file(context: Context, parent: LinearLayout, data: JsonObject) {
        val fileName = data.readString("filename")?.takeIf(String::isNotBlank) ?: "File"
        val fileRowLayout = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        fileRowLayout.addView(TextView(context).apply {
            text = fileName; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.artemis_text))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val openFileButton = templateButton(context, fileRowLayout).apply {
            text = context.getString(R.string.open)
            setOnClickListener { openUrl(context, data.readString("url").orEmpty()) }
        }
        fileRowLayout.addView(openFileButton)
        parent.addView(fileRowLayout, createMatchParentWrapContentLayoutParams(context))
        data.readLong("size_bytes")?.takeIf { it >= 0 }?.let { addText(context, parent, android.text.format.Formatter.formatShortFileSize(context, it)) }
    }

    private fun renderList(context: Context, parent: LinearLayout, data: JsonObject) {
        data.readString("title")?.let { addText(context, parent, it, bold = true) }
        data.readArray("items").objects().take(100).forEach { item ->
            val title = item.readString("title")?.takeIf(String::isNotBlank) ?: return@forEach
            val listItemView = android.view.LayoutInflater.from(context).inflate(R.layout.artemis_template_list_item, parent, false)
            listItemView.findViewById<TextView>(R.id.artemis_list_title).text = title
            listItemView.findViewById<TextView>(R.id.artemis_list_subtitle).apply {
                text = item.readString("subtitle")
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            if (item.readString("default_action_url") != null) listItemView.setOnClickListener { openUrl(context, item.readString("default_action_url").orEmpty()) }
            parent.addView(listItemView, createMatchParentWrapContentLayoutParams(context))
        }
    }

    private fun kpi(context: Context, parent: LinearLayout, data: JsonObject) {
        val label = data.readString("label") ?: return
        val metricValue = data.get("value")?.takeIf { jsonValue -> jsonValue.isJsonPrimitive }?.asString ?: return
        val card = android.view.LayoutInflater.from(context).inflate(R.layout.artemis_template_kpi, parent, false)
        card.findViewById<TextView>(R.id.artemis_kpi_label).text = label
        card.findViewById<TextView>(R.id.artemis_kpi_value).text =
            buildString {
                append(metricValue)
                append((data.readString("unit")?.let { unit -> " $unit" } ?: ""))
            }
        card.findViewById<TextView>(R.id.artemis_kpi_trend).apply {
            text = data.readString("trend")
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        parent.addView(card)
    }

    private fun table(context: Context, parent: LinearLayout, data: JsonObject) {
        val columns = data.readArray("columns").objects().filter { it.readString("key") != null }
        val rows = data.readArray("rows").objects()
        if (columns.isEmpty() || rows.isEmpty()) return
        val maximumVisibleRows = (data.readInteger("max_visible_rows") ?: 10).coerceAtLeast(1).coerceAtMost(100)
        var showAllRows = false
        val tableContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun drawTableRows() {
            tableContainer.removeAllViews()
            val tableHorizontalScrollView = HorizontalScrollView(context)
            val tableLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun addTableRow(cellValues: List<String>, isHeaderRow: Boolean) {
                val tableRowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                cellValues.forEach { cellValue ->
                    val tableCell = TextView(context).apply {
                        text = cellValue
                        textSize = 13f
                        setTextColor(context.getColor(R.color.artemis_text))
                        setPadding(context.densityIndependentPixelsToPixels(12))
                        if (isHeaderRow) {
                            setTypeface(typeface, Typeface.BOLD)
                            setBackgroundColor(context.getColor(R.color.artemis_surface))
                        }
                    }
                    tableRowLayout.addView(tableCell, LinearLayout.LayoutParams(context.densityIndependentPixelsToPixels(120), ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                tableLayout.addView(tableRowLayout)
            }
            addTableRow(columns.map { column -> column.readString("header").orEmpty() }, true)
            rows.take(if (showAllRows) 100 else maximumVisibleRows).forEach { rowData ->
                addTableRow(columns.map { column -> rowData.get(column.readString("key"))?.let(::scalar) ?: "" }, false)
            }
            tableHorizontalScrollView.addView(tableLayout)
            tableContainer.addView(tableHorizontalScrollView)
            if (rows.size > maximumVisibleRows) {
                tableContainer.addView(templateButton(context, tableContainer).apply {
                    text = if (showAllRows) "Show less" else "Show more"
                    setOnClickListener {
                        showAllRows = !showAllRows
                        drawTableRows()
                    }
                })
            }
        }
        drawTableRows()
        parent.addView(tableContainer, createMatchParentWrapContentLayoutParams(context))
    }

    private fun chart(context: Context, parent: LinearLayout, data: JsonObject) {
        val chartPoints = data.readArray("data").objects().take(100).mapNotNull { chartDataPoint ->
            val chartPointValue = chartDataPoint.get("value")?.takeIf { jsonValue -> jsonValue.isJsonPrimitive }?.asDouble
                ?: return@mapNotNull null
            ChartPoint(
                label = chartDataPoint.readString("label").orEmpty(),
                value = chartPointValue,
                color = chartDataPoint.readString("color"),
            )
        }
        if (chartPoints.isEmpty()) { addText(context, parent, "No chart data"); return }
        data.readString("title")?.let { addText(context, parent, it, bold = true) }
        val chartType = data.readString("type")
        parent.addView(object : View(context) {
            private val chartPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val maximumChartValue = chartPoints.maxOf { chartPoint -> chartPoint.value }
                    .let { value -> if (value == 0.0) 1.0 else value }
                val chartSlotWidth = width.toFloat() / chartPoints.size
                if (chartType == "line") {
                    chartPaint.color = color(theme.primary, 0xFF2563EB.toInt())
                    chartPaint.strokeWidth = context.densityIndependentPixelsToPixels(3).toFloat()
                    chartPaint.style = Paint.Style.STROKE
                    val chartPath = Path()
                    chartPoints.forEachIndexed { pointIndex, chartPoint ->
                        val chartXPosition = chartSlotWidth * (pointIndex + .5f)
                        val chartYPosition = height - (chartPoint.value / maximumChartValue * (height - context.densityIndependentPixelsToPixels(24))).toFloat()
                        if (pointIndex == 0) chartPath.moveTo(chartXPosition, chartYPosition)
                        else chartPath.lineTo(chartXPosition, chartYPosition)
                    }
                    canvas.drawPath(chartPath, chartPaint)
                } else {
                    chartPaint.style = Paint.Style.FILL
                    chartPoints.forEachIndexed { pointIndex, chartPoint ->
                        chartPaint.color = parseColor(chartPoint.color, color(theme.primary, 0xFF2563EB.toInt()))
                        val barHeight = (chartPoint.value / maximumChartValue * (height - context.densityIndependentPixelsToPixels(24))).toFloat()
                        canvas.drawRect(
                            chartSlotWidth * pointIndex + context.densityIndependentPixelsToPixels(5),
                            height - barHeight,
                            chartSlotWidth * (pointIndex + 1) - context.densityIndependentPixelsToPixels(5),
                            height.toFloat(),
                            chartPaint,
                        )
                    }
                }
            }
        }, LinearLayout.LayoutParams(-1, context.densityIndependentPixelsToPixels(160)))
        addText(context, parent, chartPoints.joinToString(" · ") { chartPoint -> "${chartPoint.label}: ${chartPoint.value}" })
    }

    private fun form(context: Context, message: ChatMessage, parent: LinearLayout, data: JsonObject) {
        data.readString("title")?.let { addText(context, parent, it, bold = true) }
        val fields = data.readArray("fields").objects().take(50)
        val textInputsByIdentifier = linkedMapOf<String, EditText>()
        val selectionSpinnersByIdentifier = linkedMapOf<String, Pair<Spinner, List<JsonObject>>>()
        var containsUnsupportedFields = false
        fields.forEach { field ->
            val fieldIdentifier = field.readString("id") ?: return@forEach
            if (textInputsByIdentifier.containsKey(fieldIdentifier) || selectionSpinnersByIdentifier.containsKey(fieldIdentifier)) {
                containsUnsupportedFields = true
            }
            val fieldLabel = field.readString("label") ?: fieldIdentifier
            addText(context, parent, fieldLabel + if (field.readBoolean("required")) " *" else "")
            if (field.readString("type") == "select") {
                val fieldOptions = field.readArray("options").objects().toList()
                val selectionSpinner = Spinner(context).apply {
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, fieldOptions.map { option ->
                        option.readString("label") ?: option.readString("id").orEmpty()
                    })
                }
                parent.addView(selectionSpinner, createMatchParentWrapContentLayoutParams(context))
                selectionSpinnersByIdentifier[fieldIdentifier] = selectionSpinner to fieldOptions
            } else {
                val fieldTextInput = (android.view.LayoutInflater.from(context).inflate(R.layout.artemis_template_field, parent, false) as EditText).apply {
                    hint = field.readString("placeholder") ?: fieldLabel
                    setSingleLine()
                    inputType = when (field.readString("input_type") ?: field.readString("type")) {
                        "email" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        "number" -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        "tel", "phone" -> android.text.InputType.TYPE_CLASS_PHONE
                        else -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    }
                    setText(field.readString("value"))
                    if ((field.readString("input_type") ?: field.readString("type")) == "date") {
                        isFocusable = false
                        setOnClickListener {
                            val currentDate = java.util.Calendar.getInstance()
                            android.app.DatePickerDialog(context, { _, year, month, dayOfMonth ->
                                setText(String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", year, month + 1, dayOfMonth))
                            }, currentDate.get(java.util.Calendar.YEAR), currentDate.get(java.util.Calendar.MONTH), currentDate.get(java.util.Calendar.DAY_OF_MONTH)).show()
                        }
                    }
                }
                parent.addView(fieldTextInput, createMatchParentWrapContentLayoutParams(context))
                textInputsByIdentifier[fieldIdentifier] = fieldTextInput
            }
        }
        if (containsUnsupportedFields || fields.isEmpty()) {
            addText(context, parent, "This form contains unsupported fields")
            return
        }
        val submitFormButton = Button(context).apply {
            text = data.readString("submit_label") ?: "Submit"
            isEnabled = message.serverMessageId != null && !callbacks.isLocked(message, "form-submit")
            setOnClickListener {
                val submittedFormValues = linkedMapOf<String, String>()
                textInputsByIdentifier.forEach { (fieldIdentifier, fieldTextInput) ->
                    val isFieldRequired = fields.firstOrNull { it.readString("id") == fieldIdentifier }?.readBoolean("required") == true
                    val enteredFieldValue = fieldTextInput.text.toString().trim()
                    if (isFieldRequired && enteredFieldValue.isEmpty()) {
                        fieldTextInput.error = "Required"
                        return@setOnClickListener
                    }
                    submittedFormValues[fieldIdentifier] = enteredFieldValue
                }
                selectionSpinnersByIdentifier.forEach { (fieldIdentifier, spinnerAndOptions) ->
                    val (selectionSpinner, fieldOptions) = spinnerAndOptions
                    submittedFormValues[fieldIdentifier] = fieldOptions
                        .getOrNull(selectionSpinner.selectedItemPosition)?.readString("id").orEmpty()
                }
                callbacks.onAction(message, "form-submit", encodeFormValues(submittedFormValues), submittedFormValues, null)
                isEnabled = false
            }
        }
        parent.addView(submitFormButton, createMatchParentWrapContentLayoutParams(context))
    }

    private fun progress(context: Context, parent: LinearLayout, data: JsonObject) {
        val progressValue = data.readDouble("value") ?: return
        val maxValue = data.readDouble("max") ?: 100.0
        if (maxValue <= 0 || !maxValue.isFinite()) { addText(context, parent, "Progress unavailable"); return }
        data.readString("label")?.let { addText(context, parent, it) }
        val progressBarLayoutResource = if (data.readString("variant") == "circle") R.layout.artemis_template_progress_circle
            else R.layout.artemis_template_progress
        val progressBar = android.view.LayoutInflater.from(context).inflate(progressBarLayoutResource, parent, false) as ProgressBar
        val completionPercentage = (progressValue / maxValue * 100).toInt().coerceIn(0, 100)
        progressBar.max = 100
        progressBar.progress = completionPercentage
        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(color(theme.primary, context.getColor(
            R.color.artemis_primary)))
        progressBar.contentDescription = "${data.readString("label") ?: "Progress"}: $completionPercentage%"
        parent.addView(progressBar)
        addText(context, parent, "$completionPercentage%")
    }

    private fun feedback(context: Context, message: ChatMessage, parent: LinearLayout, data: JsonObject) {
        addText(context, parent, data.readString("prompt") ?: return)
        val feedbackType = data.readString("type") ?: "stars"
        val feedbackChipGroup = com.google.android.material.chip.ChipGroup(context)
        if (feedbackType == "thumbs") {
            listOf("👍" to 1, "👎" to 0).forEach { (feedbackLabel, ratingValue) ->
                feedbackChipGroup.addView(templateChip(context, parent).apply {
                    text = feedbackLabel; isEnabled = message.serverMessageId != null && !callbacks.isLocked(message, "feedback")
                    setOnClickListener {
                        callbacks.onFeedback(message, "thumbs", ratingValue, null)
                        feedbackChipGroup.children().forEach { feedbackChip -> feedbackChip.isEnabled = false }
                    }
                })
            }
        } else {
            val maximumRating = (data.readInteger("max") ?: 5).coerceIn(1, 10)
            for (ratingValue in 1..maximumRating) feedbackChipGroup.addView(Button(context).apply {
                text = "★ $ratingValue"; isEnabled = message.serverMessageId != null && !callbacks.isLocked(message, "feedback")
                setOnClickListener {
                    callbacks.onFeedback(message, "star", ratingValue, null)
                    feedbackChipGroup.children().forEach { feedbackChip -> feedbackChip.isEnabled = false }
                }
            })
        }
        parent.addView(feedbackChipGroup)
    }

    private fun quickReplies(context: Context, message: ChatMessage, parent: LinearLayout, items: JsonArray) {
        val quickReplyChipGroup = com.google.android.material.chip.ChipGroup(context)
        items.objects().take(20).forEach { item ->
            val quickReplyIdentifier = item.readString("id") ?: return@forEach
            val quickReplyLabel = item.readString("label") ?: return@forEach
            quickReplyChipGroup.addView(templateChip(context, parent).apply {
                text = quickReplyLabel; isEnabled = !callbacks.isLocked(message, "quick_replies")
                setOnClickListener {
                    callbacks.onAction(message, quickReplyIdentifier, quickReplyLabel, null, null)
                    quickReplyChipGroup.children().forEach { quickReplyChip -> quickReplyChip.isEnabled = false }
                }
            })
        }
        parent.addView(quickReplyChipGroup, createMatchParentWrapContentLayoutParams(context))
    }

    private fun fallback(context: Context, parent: LinearLayout, title: String, payload: JsonElement) {
        val extractedTextParts = mutableListOf<String>()
        fun collectText(node: JsonElement, nestingDepth: Int) {
            if (nestingDepth > 8 || extractedTextParts.sumOf(String::length) > 2000) return
            when {
                node.isJsonObject -> node.asJsonObject.entrySet().forEach { (propertyName, propertyValue) ->
                    if (propertyName in setOf("text", "title", "label", "description", "content") && propertyValue.isJsonPrimitive && propertyValue.asJsonPrimitive.isString) {
                        extractedTextParts.add(propertyValue.asString)
                    } else {
                        collectText(propertyValue, nestingDepth + 1)
                    }
                }
                node.isJsonArray -> node.asJsonArray.forEach { arrayValue -> collectText(arrayValue, nestingDepth + 1) }
            }
        }
        collectText(payload, 0)
        addText(context, parent, title, bold = true)
        addText(context, parent, extractedTextParts.joinToString("\n").take(2000).ifBlank { "Content is available in another channel" })
    }

    private fun addText(context: Context, parent: LinearLayout, text: CharSequence, bold: Boolean = false) {
        parent.addView(TextView(context).apply { this.text = text; textSize = 14f; setTextColor(context.getColor(
            R.color.artemis_text)); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(context.densityIndependentPixelsToPixels(4), context.densityIndependentPixelsToPixels(3), context.densityIndependentPixelsToPixels(4), context.densityIndependentPixelsToPixels(3)) }, createMatchParentWrapContentLayoutParams(context))
    }

    private fun openUrl(context: Context, url: String) {
        if (!safeUrl(url)) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } catch (_: ActivityNotFoundException) { }
    }

    private fun safeUrl(url: String): Boolean = runCatching { url.toUri().scheme.equals("https", true) && url.toUri().host != null }.getOrDefault(false)

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

    private fun color(colorValue: String?, fallbackColor: Int) =
        runCatching { Color.parseColor(colorValue) }.getOrDefault(fallbackColor)
    private fun parseColor(colorValue: String?, fallbackColor: Int) = color(colorValue, fallbackColor)
    private fun createMatchParentWrapContentLayoutParams(context: Context) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.densityIndependentPixelsToPixels(3); bottomMargin = context.densityIndependentPixelsToPixels(3) }
    private fun encodeFormValues(formValues: Any) = com.google.gson.Gson().toJson(formValues)
    private fun JsonObject.readString(jsonPropertyName: String) =
        get(jsonPropertyName)?.takeIf { jsonValue -> jsonValue.isJsonPrimitive && jsonValue.asJsonPrimitive.isString }?.asString
    private fun JsonObject.readInteger(jsonPropertyName: String) =
        get(jsonPropertyName)?.takeIf { jsonValue -> jsonValue.isJsonPrimitive }?.asInt
    private fun JsonObject.readLong(jsonPropertyName: String) =
        get(jsonPropertyName)?.takeIf { jsonValue -> jsonValue.isJsonPrimitive }?.asLong
    private fun JsonObject.readDouble(jsonPropertyName: String) =
        get(jsonPropertyName)?.takeIf { jsonValue -> jsonValue.isJsonPrimitive }?.asDouble
    private fun JsonObject.readBoolean(jsonPropertyName: String) =
        get(jsonPropertyName)?.takeIf { jsonValue -> jsonValue.isJsonPrimitive }?.asBoolean ?: false
    private fun JsonObject.readArray(jsonPropertyName: String) =
        get(jsonPropertyName)?.takeIf { jsonValue -> jsonValue.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonElement?.objects() = this?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject } ?: emptyList()
    private fun scalar(jsonValue: JsonElement) = if (jsonValue.isJsonPrimitive) jsonValue.asString else jsonValue.toString()
    private fun Context.densityIndependentPixelsToPixels(densityIndependentPixels: Int) =
        (densityIndependentPixels * resources.displayMetrics.density).toInt()

    fun markdownHtml(text: String): String {
        val html = StringBuilder()
        val paragraph = mutableListOf<String>()
        val markdownListItems = mutableListOf<Pair<String, String>>()
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
            if (markdownListItems.isNotEmpty()) {
                html.append("<p>")
                markdownListItems.forEachIndexed { index, item ->
                    if (index > 0) html.append("<br>")
                    html.append("<artemis-list-item>")
                        .append("&nbsp;&nbsp;&nbsp;&nbsp;")
                        .append(if (listType == 'o') "${item.first}. " else "• ")
                        .append(markdownInline(item.second))
                        .append("</artemis-list-item>")
                }
                html.append("</p>")
                markdownListItems.clear()
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
                        .append(code.joinToString("\n") { Html.escapeHtml(it) })
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
                markdownListItems += if (ordered != null) ordered.groupValues[1] to ordered.groupValues[2]
                    else "" to unordered!!.groupValues[1]
                return@forEach
            }
            flushList()
            paragraph += line
        }
        if (inCode) {
            html.append("<p><artemis-code-block>")
                .append(code.joinToString("\n") { Html.escapeHtml(it) })
                .append("</artemis-code-block></p>")
        }
        flushBlocks()
        return html.toString()
    }

    fun parseMarkdown(context: Context, text: String): Spanned {
        val accent = color(theme.primary, context.getColor(R.color.artemis_primary))
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
            val token = "${'$'}ARTEMIS${placeholders.size}$"
            placeholders += value
            return token
        }
        var line = source
        line = Regex("`([^`]+)`").replace(line) { match ->
            protect("<artemis-code>${Html.escapeHtml(match.groupValues[1])}</artemis-code>")
        }
        line = Regex("!?\\[([^]]+)]\\(([^ )]+)(?:\\s+[\"']([^\"']*)[\"'])?\\)").replace(line) { match ->
            val label = Html.escapeHtml(match.groupValues[1])
            val destination = match.groupValues[2]
            val uri = runCatching { URI(destination) }.getOrNull()
            if (match.value.startsWith("!")) {
                protect(label)
            } else if (uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) {
                val href = Html.escapeHtml(destination.replace("\"", "%22"))
                protect("<a href=\"$href\">$label</a>")
            } else {
                protect(label)
            }
        }
        var escaped = Html.escapeHtml(line)
        escaped = escaped
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("(?<!\\*)\\*([^*]+?)\\*(?!\\*)"), "<i>$1</i>")
            .replace(Regex("(?<!_)_([^_]+?)_(?!_)"), "<i>$1</i>")
            .replace(Regex("~~(.+?)~~"), "<del>$1</del>")
        placeholders.forEachIndexed { index, replacement ->
            escaped = escaped.replace("${'$'}ARTEMIS$index$", replacement)
        }
        return escaped
    }

    private class SimpleSelectionListener(private val selected: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun ViewGroup.children() = (0 until childCount).map { getChildAt(it) }


    private fun templateChip(context: Context, parent: ViewGroup): com.google.android.material.chip.Chip =
        (android.view.LayoutInflater.from(context).inflate(R.layout.artemis_template_chip, parent, false) as com.google.android.material.chip.Chip).apply {
            val accent = android.content.res.ColorStateList.valueOf(color(theme.primary, context.getColor(
                R.color.artemis_primary)))
            setTextColor(accent)
            chipStrokeColor = accent
        }

    private fun templateButton(context: Context, parent: ViewGroup): Button =
        (android.view.LayoutInflater.from(context).inflate(R.layout.artemis_template_action, parent, false) as Button).apply {
            setTextColor(color(theme.primary, context.getColor(R.color.artemis_primary)))
        }

    companion object {
        private val imageExecutor = Executors.newFixedThreadPool(2)
    }
}
