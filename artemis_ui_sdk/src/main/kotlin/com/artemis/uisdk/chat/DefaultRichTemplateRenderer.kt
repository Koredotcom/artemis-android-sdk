package com.artemis.uisdk.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.text.Html
import android.text.Spanned
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
import android.widget.TextView
import android.widget.VideoView
import androidx.core.net.toUri
import androidx.core.view.isNotEmpty
import androidx.core.view.setPadding
import com.artemis.uisdk.ArtemisFeatureConfig
import com.artemis.uisdk.ArtemisThemeConfig
import com.artemis.uisdk.R
import com.artemis.uisdk.hostSnapshot
import com.artemis.uisdk.artemisColor
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI

/** Built-in rich-content rendering. Layouts live in res/layout/artemis_template_*.xml. */
internal class DefaultRichTemplateRenderer(
    private val features: ArtemisFeatureConfig,
    private var theme: ArtemisThemeConfig,
    private val callbacks: ChatMessageAdapter.Callbacks,
    private val handle: com.artemis.uisdk.ArtemisUiHandle? = null,
) {
    private fun label(context: Context, id: Int, vararg args: Any) =
        handle?.presentation?.value?.strings?.text(context, id, *args) ?: context.getString(
            id,
            *args
        )

    fun updateTheme(value: ArtemisThemeConfig) {
        theme = value; markdownCache.evictAll()
    }

    private fun custom(
        context: Context,
        message: ChatMessage,
        parent: LinearLayout,
        name: String,
        payload: JsonElement
    ): Boolean {
        val owner = handle ?: return false
        val renderer = owner.renderer(name) ?: return false
        val owningModel = owner.chatViewModel
        val detachedMessage = message.hostSnapshot()
        val detachedPayload = payload.deepCopy()
        val payloadObject = detachedPayload.takeIf { it.isJsonObject }?.asJsonObject
        val renderId = payloadObject?.string("actionRenderId", "renderId")
            ?: message.actions?.string("renderId")
        val draftSignature = payloadObject?.deepCopy() ?: JsonObject().apply { add("payload", detachedPayload.deepCopy()) }
        val draftScope = "custom:$name"
        val submitId = payloadObject?.string("submitId", "submit_id") ?: "form-submit"
        val draftValues = (if (owningModel != null && payloadObject != null)
            owningModel.templateDraft(message, renderId, draftScope, draftSignature)
        else emptyMap()).toMutableMap()
        val feedback = payloadObject?.let { callbacks.feedbackState(message, it) }
            ?: message.feedbackResponses[renderId.orEmpty()]
            ?: FeedbackResponseState()
        val actionIds = collectActionIds(detachedPayload)
        val lockedIds = actionIds.filterTo(linkedSetOf()) { id ->
            callbacks.isTemplateLocked(message, id, renderId)
        }
        val view = runCatching {
            renderer.render(
                com.artemis.uisdk.ArtemisTemplateRequest(
                    context = context,
                    message = detachedMessage,
                    templateName = name,
                    payload = detachedPayload,
                    theme = theme,
                    actionLocked = { owner.chatViewModel !== owningModel || callbacks.isTemplateLocked(message, it, renderId) },
                    typography = owner.presentation.value.typography,
                    usedQuickReplyKeys = if (name == "quick_replies") lockedIds else emptySet(),
                    usedActionButtonIds = if (name == "actions") lockedIds else emptySet(),
                    formDraftValues = draftValues,
                    isFormSubmitted = name == "form" && callbacks.isTemplateLocked(message, submitId, renderId),
                    onFormFieldChanged = { fieldId, value ->
                        if (owningModel != null && payloadObject != null) {
                            owningModel.updateTemplateDraft(
                                message,
                                renderId,
                                draftScope,
                                draftSignature,
                                draftValues.apply { put(fieldId, value.take(10_000)) }.toMap(),
                            )
                        }
                    },
                    onFormSubmit = { values ->
                        if (owner.chatViewModel === owningModel && !owner.isClosed()) {
                            if (owningModel != null && payloadObject != null) {
                                owningModel.updateTemplateDraft(
                                    message,
                                    renderId,
                                    draftScope,
                                    draftSignature,
                                    values.mapValues { (_, value) -> value.take(10_000) },
                                )
                            }
                            owner.submitAction(message.messageId, submitId, Gson().toJson(values), values, renderId)
                        }
                    },
                    feedbackState = feedback,
                    isFeedbackSubmitted = feedback.status in setOf(
                        FeedbackSubmissionStatus.PENDING,
                        FeedbackSubmissionStatus.SUBMITTED,
                        FeedbackSubmissionStatus.EXPIRED,
                    ),
                    submittedFeedbackRating = feedback.draft.rating.takeIf {
                        feedback.status == FeedbackSubmissionStatus.SUBMITTED
                    },
                    onFeedbackDraft = { draft ->
                        if (payloadObject != null) callbacks.onFeedbackDraft(message, payloadObject, draft)
                    },
                    onFeedbackSubmit = { draft ->
                        if (payloadObject != null && owner.chatViewModel === owningModel && !owner.isClosed()) {
                            callbacks.onFeedbackResponse(message, payloadObject, draft)
                        }
                    },
                    imageRequest = { url, onLoaded ->
                        imageUrl(url)?.let { images.load(it, onLoaded) } ?: onLoaded(null)
                    },
                    linkRequest = { url -> openUrl(context, url) },
                    action = { id, value, data, actionRenderId ->
                        if (owner.chatViewModel === owningModel && !owner.isClosed()) owner.submitAction(
                            message.messageId,
                            id,
                            value,
                            data,
                            actionRenderId
                        )
                    },
                )
            )
        }.getOrNull() ?: return false
        if (view.parent != null) return false
        parent.addView(view)
        return true
    }

    private fun collectActionIds(value: JsonElement): Set<String> = buildSet {
        fun visit(element: JsonElement) {
            when {
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, child) ->
                    if (key in setOf("id", "actionId", "action_id") && child.isJsonPrimitive && child.asJsonPrimitive.isString) {
                        child.asString.trim().takeIf(String::isNotEmpty)?.let(::add)
                    }
                    visit(child)
                }
                element.isJsonArray -> element.asJsonArray.forEach(::visit)
            }
        }
        visit(value)
    }

    private val images = TemplateImageLoader()
    private val markdownCache = object : android.util.LruCache<String, Spanned>(64 * 1024) {
        override fun sizeOf(key: String, value: Spanned) =
            (key.length + value.length).coerceAtLeast(1)
    }

    fun clearCaches() {
        images.clear(); markdownCache.evictAll()
    }


    fun renderRich(
        context: Context,
        messageViewHolder: ChatMessageAdapter.ChatMessageViewHolder,
        message: ChatMessage,
        parent: LinearLayout,
        richContentObject: JsonObject
    ) {
        val templateRenderOrder =
            (com.artemis.uisdk.templates.ArtemisDefaultTemplates.types + handle?.rendererNames()
                .orEmpty()).distinct()
        for (templateType in templateRenderOrder) {
            val templatePayload = richContentObject.get(templateType) ?: continue
            if (templatePayload.isJsonNull) continue
            if (templateType == "html" && richContentObject.get("markdown")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.isNotBlank() == true) continue
            if (custom(context, message, parent, templateType, templatePayload)) continue

            val templateTitle = templateType.replace('_', ' ').replaceFirstChar { it.uppercase() }
            val templateContainer =
                if (templateType == "markdown" || templateType == "quick_replies") LinearLayout(context).apply { orientation = LinearLayout.VERTICAL } else
                    (android.view.LayoutInflater.from(context)
                        .inflate(R.layout.artemis_template_card, parent, false) as LinearLayout)
            if (templateContainer !== parent) {
                (templateContainer.background?.mutate() as? android.graphics.drawable.GradientDrawable)?.let { background ->
                    background.setColor(context.artemisColor(theme, R.color.artemis_surface))
                    background.setStroke(
                        context.densityIndependentPixelsToPixels(1),
                        context.artemisColor(theme, R.color.artemis_border)
                    )
                }
            }
            runCatching {
                when (templateType) {
                    "markdown" -> if (features.markdown) renderMarkdown(context, templateContainer, templatePayload.asString, message) else addText(context, templateContainer, templatePayload.asString)

                    "carousel" -> carousel(
                        context,
                        message,
                        templateContainer,
                        templatePayload.asJsonObject
                    )

                    "image" -> image(context, templateContainer, templatePayload.asJsonObject)
                    "html" -> html(context, templateContainer, templatePayload.asString)
                    "video" -> video(
                        context,
                        messageViewHolder,
                        templateContainer,
                        templatePayload.asJsonObject
                    )

                    "audio" -> audio(
                        context,
                        messageViewHolder,
                        templateContainer,
                        templatePayload.asJsonObject
                    )

                    "file" -> file(context, templateContainer, templatePayload.asJsonObject)
                    "list" -> renderList(context, templateContainer, templatePayload.asJsonObject)
                    "kpi" -> kpi(context, templateContainer, templatePayload.asJsonObject)
                    "table" -> table(context, templateContainer, templatePayload.asJsonObject, message)
                    "chart" -> chart(context, templateContainer, templatePayload.asJsonObject)
                    "form" -> form(
                        context,
                        message,
                        templateContainer,
                        templatePayload.asJsonObject
                    )

                    "progress" -> progress(context, templateContainer, templatePayload.asJsonObject)
                    "feedback" -> feedback(
                        context,
                        message,
                        templateContainer,
                        templatePayload.asJsonObject
                    )

                    "quick_replies" -> quickReplies(
                        context,
                        message,
                        templateContainer,
                        templatePayload.asJsonArray,
                        richContentObject.string("_artemisRenderId")
                    )

                    else -> fallback(context, templateContainer, templateTitle, templatePayload)
                }
            }.onFailure {
                templateContainer.removeAllViews()
                addText(
                    context,
                    templateContainer,
                    label(context, R.string.artemis_template_unavailable, templateTitle)
                )
            }
            if (templateContainer !== parent && templateContainer.isNotEmpty()) parent.addView(
                templateContainer
            )
        }
    }

    fun renderActions(
        context: Context,
        message: ChatMessage,
        parent: LinearLayout,
        actions: JsonObject
    ) {
        val controls = TemplateControlsView(context, message, actions, false, theme,
            handle?.presentation?.value?.strings ?: com.artemis.uisdk.ArtemisStrings(), callbacks, ::navigationUrl) { openActionUrl(context, it) }
        while (controls.childCount > 0) {
            val child = controls.getChildAt(0); controls.removeViewAt(0)
            child.tag = NativeTemplateDraftOwner
            parent.addView(child)
        }
    }

    private fun actionButton(
        context: Context,
        message: ChatMessage,
        parent: LinearLayout,
        actionIdentifier: String,
        actionLabel: String,
        actionValue: String?,
        renderIdentifier: String?,
        url: String? = null,
        onClick: (() -> Unit)? = null
    ) {
        val actionButtonView = templateButton(context, parent).apply {
            text = actionLabel
            isEnabled = navigationUrl(url) != null || !callbacks.isTemplateLocked(message, actionIdentifier, renderIdentifier)
        }
        actionButtonView.setOnClickListener {
            val link = navigationUrl(url)
            if (link != null) openActionUrl(context, link)
            else if (onClick != null) onClick()
            else {
                actionButtonView.isEnabled = false
                callbacks.onAction(message, actionIdentifier, actionValue, null, renderIdentifier)
            }
        }
        parent.addView(actionButtonView, createMatchParentWrapContentLayoutParams(context))
    }

    private fun carousel(
        context: Context,
        message: ChatMessage,
        parent: LinearLayout,
        data: JsonObject
    ) {
        val cards = data.readArray("cards")
        if (!features.carousel) {
            cards.objects().take(50)
                .forEach { addText(context, parent, it.readString("title").orEmpty()) }; return
        }
        if (cards.size() > 50) addText(context, parent, label(context, R.string.artemis_template_item_limit, 50))
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (card in cards.objects().take(50)) {
            val carouselCardLayout = android.view.LayoutInflater.from(context)
                .inflate(R.layout.artemis_template_card, strip, false) as LinearLayout
            addText(context, carouselCardLayout, card.readString("title").orEmpty(), bold = true)
            card.readString("subtitle")
                ?.let { subtitle -> addText(context, carouselCardLayout, subtitle) }
            card.readString("image_url")?.let { imageUrl ->
                image(
                    context,
                    carouselCardLayout,
                    JsonObject().apply { addProperty("url", imageUrl) })
            }
            card.readArray("buttons").objects().forEach { button ->
                val actionIdentifier = button.readString("id") ?: return@forEach
                actionButton(
                    context,
                    message,
                    carouselCardLayout,
                    actionIdentifier,
                    button.readString("label") ?: actionIdentifier,
                    TemplateContracts.actionValue(button),
                    data.string("actionRenderId", "renderId") ?: message.actions?.string("renderId"),
                    url = button.readString("url")
                )
            }
            card.readString("default_action_url")?.let { actionUrl ->
                carouselCardLayout.setOnClickListener { openUrl(context, actionUrl) }
            }
            strip.addView(
                carouselCardLayout,
                LinearLayout.LayoutParams(
                    context.densityIndependentPixelsToPixels(260),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = context.densityIndependentPixelsToPixels(8) })
        }
        scroll.addView(strip); parent.addView(
            scroll,
            createMatchParentWrapContentLayoutParams(context)
        )
    }

    private fun smallImage(context: Context, url: String?, size: Int, description: String? = null): ImageView? {
        val source = imageUrl(url) ?: return null
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(context.densityIndependentPixelsToPixels(size), context.densityIndependentPixelsToPixels(size)).apply { marginEnd = context.densityIndependentPixelsToPixels(8) }
            contentDescription = description
            if (description == null) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            loadImage(source) { bitmap -> if (bitmap != null) setImageBitmap(bitmap) else visibility = View.GONE }
        }
    }

    private fun image(context: Context, parent: LinearLayout, data: JsonObject) {
        val url = imageUrl(data.string("url")) ?: run { addText(context, parent, label(context, R.string.artemis_template_media_unavailable)); return }
        val templateImageView = (android.view.LayoutInflater.from(context).inflate(
            R.layout.artemis_template_image,
            parent,
            false
        ) as ImageView).apply {
            contentDescription = data.readString("alt") ?: data.readString("caption") ?: "Image"
        }
        loadImage(url) { decodedBitmap ->
            if (decodedBitmap != null) templateImageView.setImageBitmap(decodedBitmap)
            else { templateImageView.visibility = View.GONE; addText(context, parent, data.string("alt") ?: label(context, R.string.artemis_template_media_unavailable)) }
        }
        parent.addView(
            templateImageView,
            LinearLayout.LayoutParams(-1, context.densityIndependentPixelsToPixels(190))
        )
        data.readString("caption")?.let { addText(context, parent, it) }
    }

    private fun html(context: Context, parent: LinearLayout, source: String) {
        val sanitized = source.replace(Regex("(?is)<(script|style|iframe|object|embed)\\b[^>]*>.*?</\\1\\s*>"), "")
        val styled = android.text.SpannableStringBuilder(Html.fromHtml(sanitized, Html.FROM_HTML_MODE_COMPACT))
        styled.getSpans(0, styled.length, android.text.style.URLSpan::class.java).forEach { span ->
            val start = styled.getSpanStart(span); val end = styled.getSpanEnd(span)
            styled.removeSpan(span)
            navigationUrl(span.url)?.let { url -> styled.setSpan(android.text.style.URLSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        parent.addView(TextView(context).apply {
            text = styled; textSize = 14f; setTextColor(context.artemisColor(theme, R.color.artemis_text))
            setLinkTextColor(context.artemisColor(theme, R.color.artemis_primary)); movementMethod = LinkMovementMethod.getInstance()
        }, createMatchParentWrapContentLayoutParams(context))
    }

    private fun video(
        context: Context,
        holder: ChatMessageAdapter.ChatMessageViewHolder,
        parent: LinearLayout,
        data: JsonObject
    ) {
        val url = navigationUrl(data.string("url")) ?: return
        if (!safeUrl(url)) {
            addText(context, parent, "Invalid video link"); return
        }
        val videoPlayerView = VideoView(context).apply {
            setOnPreparedListener { mediaPlayer -> mediaPlayer.isLooping = false }
            setOnErrorListener { _, _, _ ->
                addText(
                    context,
                    parent,
                    "Video could not be played"
                ); true
            }
        }
        val videoPlaybackButton = templateButton(context, parent).apply {
            text = label(context, R.string.play_video)
            setOnClickListener { videoPlayerView.setVideoURI(url.toUri()); videoPlayerView.start() }
        }
        val poster = smallImage(context, data.string("thumbnail_url"), 160, data.string("alt") ?: data.string("caption"))
        val mediaFrame = android.widget.FrameLayout(context)
        mediaFrame.addView(videoPlayerView, android.widget.FrameLayout.LayoutParams(-1, -1))
        poster?.let { mediaFrame.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        videoPlayerView.setOnPreparedListener { player -> player.isLooping = false; poster?.visibility = View.GONE }
        videoPlayerView.setMediaController(android.widget.MediaController(context).apply { setAnchorView(videoPlayerView) })
        videoPlayerView.contentDescription = data.string("alt") ?: data.string("caption")
        val previousRelease = holder.onMediaReleased
        holder.onMediaReleased = { previousRelease?.invoke(); poster?.visibility = View.VISIBLE }
        holder.videoViews.add(videoPlayerView)
        parent.addView(
            mediaFrame,
            LinearLayout.LayoutParams(-1, context.densityIndependentPixelsToPixels(190))
        )
        parent.addView(videoPlaybackButton, createMatchParentWrapContentLayoutParams(context))
        data.string("caption")?.let { addText(context, parent, it) }
    }

    private fun audio(
        context: Context,
        messageViewHolder: ChatMessageAdapter.ChatMessageViewHolder,
        parent: LinearLayout,
        data: JsonObject
    ) {
        val url = navigationUrl(data.string("url")) ?: return
        smallImage(context, data.string("thumbnail_url"), 56, data.string("alt"))?.let(parent::addView)
        val button = templateButton(context, parent).apply {
            text = label(context, R.string.play_audio)
            contentDescription = data.string("alt") ?: data.string("caption") ?: text
        }
        var ownedPlayer: MediaPlayer? = null
        val previousRelease = messageViewHolder.onMediaReleased
        messageViewHolder.onMediaReleased = { previousRelease?.invoke(); ownedPlayer = null; button.text = label(context, R.string.play_audio); button.isEnabled = true }
        button.setOnClickListener {
            if (!safeUrl(url)) {
                button.text = label(context, R.string.invalid_audio_link); return@setOnClickListener
            }
            val current = messageViewHolder.mediaPlayer
            if (current != null && current === ownedPlayer) {
                if (current.isPlaying) { current.pause(); button.text = label(context, R.string.artemis_template_resume_audio) }
                else { current.start(); button.text = label(context, R.string.playing_audio) }
                return@setOnClickListener
            }
            runCatching {
                messageViewHolder.releaseMediaPlayer()
                val mediaPlayer = MediaPlayer(); messageViewHolder.mediaPlayer = mediaPlayer; ownedPlayer = mediaPlayer; button.isEnabled = false
                mediaPlayer.setDataSource(url); mediaPlayer.setOnPreparedListener { preparedMediaPlayer ->
                if (messageViewHolder.mediaPlayer !== preparedMediaPlayer) return@setOnPreparedListener
                preparedMediaPlayer.start(); button.isEnabled = true; button.text =
                label(context, R.string.playing_audio)
            }
                mediaPlayer.setOnCompletionListener {
                    button.text =
                        label(context, R.string.play_audio)
                }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    button.isEnabled = true; ownedPlayer = null; button.text = label(context, R.string.audio_unavailable); true
                }
                mediaPlayer.prepareAsync()
            }.onFailure { button.isEnabled = true; ownedPlayer = null; button.text = label(context, R.string.audio_unavailable) }
        }
        parent.addView(
            button,
            createMatchParentWrapContentLayoutParams(context)
        ); data.readString("caption")?.let { addText(context, parent, it) }
    }

    private fun file(context: Context, parent: LinearLayout, data: JsonObject) {
        val fileName = data.readString("filename")?.takeIf(String::isNotBlank) ?: "File"
        val fileRowLayout = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL
        }
        val fileType = AttachmentFileTypes.resolve(fileName, data.string("mime_type").orEmpty())
        fileRowLayout.addView(ImageView(context).apply {
            setImageResource(fileType.icon); imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(fileType.color))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.densityIndependentPixelsToPixels(20), context.densityIndependentPixelsToPixels(20)).apply { marginEnd = context.densityIndependentPixelsToPixels(8) })
        fileRowLayout.addView(TextView(context).apply {
            text = fileName; textSize = 14f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.artemisColor(theme, R.color.artemis_text))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val openFileButton = templateButton(context, fileRowLayout).apply {
            text = label(context, R.string.open)
            setOnClickListener { openUrl(context, data.readString("url").orEmpty()) }
        }
        openFileButton.isEnabled = safeUrl(data.string("url").orEmpty())
        data.string("mime_type")?.takeIf(String::isNotBlank)?.let { addText(context, parent, it) }
        // A full-width button in this horizontal row leaves the filename at zero width,
        // causing extreme text wrapping and a near-screen-height attachment card.
        fileRowLayout.addView(
            openFileButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        parent.addView(fileRowLayout, createMatchParentWrapContentLayoutParams(context))
        data.readLong("size_bytes")?.takeIf { it >= 0 }?.let {
            addText(
                context,
                parent,
                android.text.format.Formatter.formatShortFileSize(context, it)
            )
        }
    }

    private fun renderList(context: Context, parent: LinearLayout, data: JsonObject) {
        data.readString("title")?.let { addText(context, parent, it, bold = true) }
        if (data.readArray("items").size() > 100) addText(context, parent, label(context, R.string.artemis_template_item_limit, 100))
        data.readArray("items").objects().take(100).forEach { item ->
            val title = item.readString("title")?.takeIf(String::isNotBlank) ?: return@forEach
            val listItemView = android.view.LayoutInflater.from(context)
                .inflate(R.layout.artemis_template_list_item, parent, false)
            listItemView.findViewById<TextView>(R.id.artemis_list_title).text = title
            listItemView.findViewById<TextView>(R.id.artemis_list_subtitle).apply {
                text = item.readString("subtitle")
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            if (item.readString("default_action_url") != null) listItemView.setOnClickListener {
                openUrl(
                    context,
                    item.readString("default_action_url").orEmpty()
                )
            }
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            smallImage(context, item.string("image_url"), 48)?.let(row::addView)
            row.addView(listItemView, LinearLayout.LayoutParams(0, -2, 1f))
            if (item.string("default_action_url") != null) row.setOnClickListener { listItemView.performClick() }
            parent.addView(row, createMatchParentWrapContentLayoutParams(context))
        }
    }

    private fun kpi(context: Context, parent: LinearLayout, data: JsonObject) {
        val label = data.readString("label") ?: return
        val metricValue =
            data.get("value")?.takeIf { jsonValue -> jsonValue.isJsonPrimitive }?.asString ?: return
        val card = android.view.LayoutInflater.from(context)
            .inflate(R.layout.artemis_template_kpi, parent, false)
        card.findViewById<TextView>(R.id.artemis_kpi_label).text = label
        card.findViewById<TextView>(R.id.artemis_kpi_value).text =
            buildString {
                append(metricValue)
                append((data.readString("unit")?.let { unit -> " $unit" } ?: ""))
            }
        card.findViewById<TextView>(R.id.artemis_kpi_trend).apply {
            text = when (data.string("trend")) { "up" -> "↑"; "down" -> "↓"; "flat" -> "→"; else -> data.string("trend").orEmpty() }
            contentDescription = data.string("trend")?.let { label(context, R.string.artemis_template_trend, it) }
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        smallImage(context, data.string("icon_url"), 24)?.let(parent::addView)
        parent.addView(card)
    }

    @android.annotation.SuppressLint("RtlHardcoded") // Payload align values match explicit CSS left/right, not relative start/end.
    private fun table(context: Context, parent: LinearLayout, data: JsonObject, message: ChatMessage? = null) {
        val columns = data.readArray("columns").objects().filter { it.readString("key") != null }
        val rows = data.readArray("rows").objects().take(500)
        if (columns.isEmpty()) return
        val maximumVisibleRows =
            (data.readInteger("max_visible_rows") ?: 10).coerceAtLeast(1).coerceAtMost(100)
        val tableScope = "table:${data.hashCode()}"
        val renderId = data.string("actionRenderId", "renderId") ?: message?.actions?.string("renderId")
        var showAllRows = message?.let { callbacks.templateDraft(it, renderId, tableScope, data)["expanded"] == "true" } ?: false
        val tableContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun drawTableRows() {
            tableContainer.removeAllViews()
            val tableHorizontalScrollView = HorizontalScrollView(context)
            val tableLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun addTableRow(cellValues: List<String>, isHeaderRow: Boolean) {
                val tableRowLayout =
                    LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                cellValues.forEachIndexed { columnIndex, cellValue ->
                    val tableCell = TextView(context).apply {
                        text = if (data.flag("markdown_cells")) parseMarkdown(context, cellValue) else cellValue
                        movementMethod = if (data.flag("markdown_cells")) LinkMovementMethod.getInstance() else null
                        gravity = when (columns[columnIndex].string("align")) { "right" -> Gravity.RIGHT; "center" -> Gravity.CENTER; else -> Gravity.LEFT }
                        textSize = 13f
                        setTextColor(context.artemisColor(theme, R.color.artemis_text))
                        setPadding(context.densityIndependentPixelsToPixels(12))
                        if (isHeaderRow) {
                            setTypeface(typeface, Typeface.BOLD)
                            setBackgroundColor(context.artemisColor(theme, R.color.artemis_surface))
                        }
                    }
                    tableRowLayout.addView(
                        tableCell,
                        LinearLayout.LayoutParams(
                            context.densityIndependentPixelsToPixels(120),
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
                tableLayout.addView(tableRowLayout)
            }
            addTableRow(columns.map { column -> column.readString("header").orEmpty() }, true)
            rows.take(if (showAllRows) 500 else maximumVisibleRows).forEach { rowData ->
                addTableRow(columns.map { column ->
                    rowData.get(column.readString("key"))?.let(::scalar) ?: ""
                }, false)
            }
            tableHorizontalScrollView.addView(tableLayout)
            tableContainer.addView(tableHorizontalScrollView)
            if (rows.size > maximumVisibleRows) {
                tableContainer.addView(templateButton(context, tableContainer).apply {
                    text = if (showAllRows) label(context, R.string.artemis_show_less) else label(
                        context,
                        R.string.artemis_show_more
                    )
                    setOnClickListener {
                        showAllRows = !showAllRows
                        message?.let { callbacks.onTemplateDraft(it, renderId, tableScope, data, mapOf("expanded" to showAllRows.toString())) }
                        drawTableRows()
                    }
                })
            }
        }
        drawTableRows()
        if ((data.array("rows")?.size() ?: 0) > 500) addText(context, parent, label(context, R.string.artemis_template_item_limit, 500))
        parent.addView(tableContainer, createMatchParentWrapContentLayoutParams(context))
    }

    private fun chart(context: Context, parent: LinearLayout, data: JsonObject) {
        val type = data.string("type")?.takeIf { it in setOf("bar", "line", "pie") }
        if (type == null) { addText(context, parent, label(context, R.string.artemis_template_chart_unavailable)); return }
        if ((data.array("data")?.size() ?: 0) > 100) addText(context, parent, label(context, R.string.artemis_template_item_limit, 100))
        val points = data.array("data")?.take(100)?.mapNotNull { value ->
            val point = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val number = point.get("value")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf(Double::isFinite) ?: return@mapNotNull null
            TemplateChartPoint(point.string("label").orEmpty(), number, point.string("color"))
        }.orEmpty()
        if (points.isEmpty()) { addText(context, parent, label(context, R.string.artemis_template_no_chart_data)); return }
        data.string("title")?.let { addText(context, parent, it, bold = true) }
        parent.addView(NativeTemplateChart(context, type, points, theme), LinearLayout.LayoutParams(-1, context.densityIndependentPixelsToPixels(180)))
        addText(context, parent, points.joinToString(" · ") { "${it.label}: ${it.value}" })
    }

    private fun form(
        context: Context,
        message: ChatMessage,
        parent: LinearLayout,
        data: JsonObject
    ) {
        parent.addView(TemplateControlsView(context, message, data, true, theme,
            handle?.presentation?.value?.strings ?: com.artemis.uisdk.ArtemisStrings(), callbacks, ::navigationUrl) { openActionUrl(context, it) })
    }

    private fun progress(context: Context, parent: LinearLayout, data: JsonObject) {
        val progressValue = data.readDouble("value") ?: return
        val maxValue = data.readDouble("max") ?: 100.0
        if (!progressValue.isFinite() || maxValue <= 0 || !maxValue.isFinite()) {
            addText(context, parent, "Progress unavailable"); return
        }
        data.readString("label")?.let { addText(context, parent, it) }
        val progressBarLayoutResource =
            if (data.readString("variant") == "circle") R.layout.artemis_template_progress_circle
            else R.layout.artemis_template_progress
        val progressBar = android.view.LayoutInflater.from(context)
            .inflate(progressBarLayoutResource, parent, false) as ProgressBar
        val completionPercentage = kotlin.math.floor((progressValue / maxValue).coerceIn(0.0, 1.0) * 100 + .5).toInt()
        if (progressBar is com.google.android.material.progressindicator.BaseProgressIndicator<*>) {
            progressBar.setIndicatorColor(context.artemisColor(theme, R.color.artemis_primary))
            progressBar.trackColor = context.artemisColor(theme, R.color.artemis_border)
        }
        progressBar.max = 100
        progressBar.progress = completionPercentage
        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(
            color(
                theme.primary, context.artemisColor(
                    theme,
                    R.color.artemis_primary
                )
            )
        )
        progressBar.contentDescription =
            "${data.readString("label") ?: "Progress"}: $completionPercentage%"
        parent.addView(progressBar)
        addText(context, parent, "$completionPercentage%")
    }

    private fun feedback(
        context: Context,
        message: ChatMessage,
        parent: LinearLayout,
        data: JsonObject
    ) {
        if (!features.advancedFeedback) { legacyFeedback(context, message, parent, data); return }
        parent.addView(FeedbackCardView(context, message, data, theme, handle?.presentation?.value?.strings ?: com.artemis.uisdk.ArtemisStrings(), callbacks))
    }

    private fun legacyFeedback(context: Context, message: ChatMessage, parent: LinearLayout, data: JsonObject) {
        addText(context, parent, data.readString("prompt") ?: return)
        val feedbackType = data.readString("type") ?: "stars"
        val feedbackChipGroup = com.google.android.material.chip.ChipGroup(context)
        if (feedbackType !in setOf("thumbs", "star", "stars")) {
            // Keep the existing basic presentation until the host enables advanced feedback.
            addText(context, parent, "This feedback type is not available yet")
            return
        }
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

    private fun quickReplies(
        context: Context,
        message: ChatMessage,
        parent: LinearLayout,
        items: JsonArray,
        renderIdentifier: String? = null
    ) {
        if (items.size() > 100) addText(context, parent, label(context, R.string.artemis_template_item_limit, 100))
        val quickReplyChipGroup = com.google.android.material.chip.ChipGroup(context)
        items.objects().take(100).forEach { item ->
            val quickReplyIdentifier = item.readString("id") ?: return@forEach
            val quickReplyLabel = item.readString("label") ?: return@forEach
            quickReplyChipGroup.addView(templateChip(context, parent).apply {
                text = quickReplyLabel
                imageUrl(item.string("icon_url"))?.let { source -> loadImage(source) { bitmap -> if (bitmap != null) {
                    chipIcon = android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
                    chipIconSize = context.densityIndependentPixelsToPixels(16).toFloat(); isChipIconVisible = true
                } } }
                isEnabled = !callbacks.isTemplateLocked(message, "quick_replies", renderIdentifier ?: message.actions?.string("renderId"))
                setOnClickListener {
                    callbacks.onAction(message, quickReplyIdentifier, quickReplyLabel, null, renderIdentifier ?: message.actions?.string("renderId"))
                    quickReplyChipGroup.children()
                        .forEach { quickReplyChip -> quickReplyChip.isEnabled = false }
                }
            })
        }
        parent.addView(quickReplyChipGroup, createMatchParentWrapContentLayoutParams(context))
    }

    private fun fallback(
        context: Context,
        parent: LinearLayout,
        title: String,
        payload: JsonElement
    ) {
        val parts = mutableListOf<String>()
        var nodes = 0
        fun collect(node: JsonElement, depth: Int, plain: Boolean = false) {
            if (++nodes > 500 || depth > 8 || parts.size >= 12 || parts.sumOf(String::length) >= 2000) return
            when {
                node.isJsonPrimitive && node.asJsonPrimitive.isString -> {
                    val value = node.asString.trim()
                    if (value.startsWith("{") || value.startsWith("[")) {
                        runCatching { com.google.gson.JsonParser.parseString(value) }.getOrNull()?.let { collect(it, depth + 1) }
                    } else if (plain && value.isNotBlank()) parts.add(value.take(2000))
                }
                node.isJsonObject -> node.asJsonObject.entrySet().forEach { (key, value) ->
                    collect(value, depth + 1, key in setOf("text", "title", "subtitle", "label", "prompt", "description", "alt", "altText", "speak", "content"))
                }
                node.isJsonArray -> node.asJsonArray.forEach { collect(it, depth + 1, plain) }
            }
        }
        collect(payload, 0, plain = payload.isJsonPrimitive)
        addText(context, parent, title, bold = true)
        addText(context, parent, parts.distinct().joinToString("\n").take(2000).ifBlank { label(context, R.string.artemis_template_channel_fallback) })
    }

    private fun addText(
        context: Context,
        parent: LinearLayout,
        text: CharSequence,
        bold: Boolean = false
    ) {
        parent.addView(TextView(context).apply {
            this.text = text; if (text is Spanned) movementMethod = LinkMovementMethod.getInstance(); textSize = 14f; setTextColor(
            color(
                theme.text, context.artemisColor(
                    theme,
                    R.color.artemis_text
                )
            )
        ); if (bold) setTypeface(
            typeface,
            Typeface.BOLD
        ); setPadding(
            context.densityIndependentPixelsToPixels(4),
            context.densityIndependentPixelsToPixels(3),
            context.densityIndependentPixelsToPixels(4),
            context.densityIndependentPixelsToPixels(3)
        )
        }, createMatchParentWrapContentLayoutParams(context))
    }

    fun renderRenderables(
        context: Context,
        holder: ChatMessageAdapter.ChatMessageViewHolder,
        message: ChatMessage,
        parent: LinearLayout
    ) {
        val hasMarkdown = message.richContent?.string("markdown")?.isNotBlank() == true || message.renderables?.any { it.isJsonObject && it.asJsonObject.string("name") == "markdown" && it.asJsonObject.get("payload")?.takeIf { p -> p.isJsonPrimitive && p.asJsonPrimitive.isString }?.asString?.isNotBlank() == true } == true
        message.renderables?.forEach { entry ->
            val item = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val name = item.string("name") ?: return@forEach
            if (name == "html" && hasMarkdown) return@forEach
            if (name in com.artemis.uisdk.templates.ArtemisDefaultTemplates.types && message.richContent?.has(
                    name
                ) != true
            ) {
                renderRich(
                    context,
                    holder,
                    message,
                    parent,
                    JsonObject().apply { add(name, item.get("payload")) })
            } else if (name !in com.artemis.uisdk.templates.ArtemisDefaultTemplates.types && message.richContent?.has(
                    name
                ) != true
            ) {
                if (custom(
                        context,
                        message,
                        parent,
                        name,
                        item.get("payload") ?: com.google.gson.JsonNull.INSTANCE
                    )
                ) return@forEach
                item.string("fallbackText")?.takeIf { it.isNotBlank() && it != message.text }
                    ?.let { addText(context, parent, it) }
            }
        }
    }

    private fun openActionUrl(context: Context, url: String) {
        val resolved = navigationUrl(url) ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, resolved.toUri()))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun openUrl(context: Context, url: String) {
        val resolved = navigationUrl(url)?.takeIf { safeUrl(it) } ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, resolved.toUri()))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun navigationUrl(value: String?) = TemplateContracts.actionUrl(value, handle?.config?.connection?.endpoint)
    private fun imageUrl(value: String?): String? {
        val source = value?.trim() ?: return null
        if (source.startsWith("data:", true)) return source.takeIf(TemplateImageLoader::allowedUrl)
        return navigationUrl(source)?.takeIf { URI(it).scheme.lowercase() in setOf("http", "https") }
    }
    private fun safeUrl(url: String): Boolean = navigationUrl(url)?.let { URI(it).scheme.lowercase() in setOf("http", "https") } == true
    private fun loadImage(url: String, done: (android.graphics.Bitmap?) -> Unit) {
        val source = imageUrl(url)
        if (source == null) done(null) else images.load(source, done)
    }

    private fun color(colorValue: String?, fallbackColor: Int) =
        runCatching { Color.parseColor(colorValue) }.getOrDefault(fallbackColor)

    private fun parseColor(colorValue: String?, fallbackColor: Int) =
        color(colorValue, fallbackColor)

    private fun createMatchParentWrapContentLayoutParams(context: Context) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = context.densityIndependentPixelsToPixels(3); bottomMargin =
            context.densityIndependentPixelsToPixels(3)
        }

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
        get(jsonPropertyName)?.takeIf { jsonValue -> jsonValue.isJsonArray }?.asJsonArray
            ?: JsonArray()

    private fun JsonElement?.objects() =
        this?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            ?: emptyList()

    private fun scalar(jsonValue: JsonElement) =
        if (jsonValue.isJsonNull) "" else if (jsonValue.isJsonPrimitive) jsonValue.asString else jsonValue.toString()

    private fun Context.densityIndependentPixelsToPixels(densityIndependentPixels: Int) =
        (densityIndependentPixels * resources.displayMetrics.density).toInt()

    private fun expandMarkdownReferences(source: String): String {
        val definition = Regex("""^ {0,3}\[([^]]+)]:\s*<?([^ >]+)>?(?:\s+["'].*["'])?\s*$""")
        fun key(value: String) = value.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
        val definitions = mutableMapOf<String, String>()
        val lines = source.replace("\r\n", "\n").split('\n')
        var fence: Char? = null
        fun fenced(line: String): Boolean {
            val marker = Regex("^ {0,3}(`{3,}|~{3,}).*$").find(line)?.groupValues?.get(1)?.first()
            if (marker != null) { if (fence == null) fence = marker else if (fence == marker) fence = null; return true }
            return fence != null
        }
        lines.forEach { line -> if (!fenced(line)) definition.matchEntire(line)?.let { match ->
            navigationUrl(match.groupValues[2])?.let { definitions.putIfAbsent(key(match.groupValues[1]), it) }
        } }
        fence = null
        return lines.joinToString("\n") { line ->
            if (fenced(line)) line else if (definition.matchEntire(line)?.groupValues?.get(1)?.let { key(it) in definitions } == true) ""
            else Regex("`+[^`]*`+|!?\\[([^]]+)](?:\\[([^]]*)])?").replace(line) { match ->
                if (match.value.startsWith('`') || line.getOrNull(match.range.last + 1) == '(') match.value else {
                    val name = match.groups[2]?.value?.takeIf(String::isNotBlank) ?: match.groupValues[1]
                    definitions[key(name)]?.let { url ->
                        (if (match.value.startsWith('!')) "!" else "") + "[${match.groupValues[1]}](${url.replace("(", "%28").replace(")", "%29")})"
                    } ?: match.value
                }
            }
        }
    }

    private fun markdownCells(line: String): List<String> {
        val cells = mutableListOf<String>(); val cell = StringBuilder(); var code = false; var escaped = false
        line.trim().removePrefix("|").removeSuffix("|").forEach { ch ->
            when {
                escaped -> { cell.append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == '`' -> { code = !code; cell.append(ch) }
                ch == '|' && !code -> { cells.add(cell.toString().trim()); cell.setLength(0) }
                else -> cell.append(ch)
            }
        }
        if (escaped) cell.append('\\')
        cells.add(cell.toString().trim()); return cells
    }
    private fun renderMarkdown(context: Context, parent: LinearLayout, text: String, message: ChatMessage? = null) {
        val lines = expandMarkdownReferences(text).replace("\r\n", "\n").split('\n'); val prose = StringBuilder(); var index = 0; var fence: String? = null
        fun flush() { if (prose.isNotBlank()) addText(context, parent, parseMarkdown(context, prose.toString())); prose.setLength(0) }
        while (index < lines.size) {
            val line = lines[index]
            val marker = Regex("^ {0,3}(`{3,}|~{3,}).*$").find(line)?.groupValues?.get(1)
            if (marker != null) {
                if (fence == null) fence = marker else if (marker.first() == fence.first() && marker.length >= fence.length) fence = null
            }
            val inlineImages = if (fence == null) Regex("!\\[([^]]*)]\\(([^ )]+)(?:\\s+[\"'][^\"']*[\"'])?\\)").findAll(line).toList() else emptyList()
            if (inlineImages.isNotEmpty()) {
                var offset = 0
                inlineImages.forEach { match ->
                    prose.append(line.substring(offset, match.range.first)); flush()
                    image(context, parent, JsonObject().apply { addProperty("url", match.groupValues[2]); addProperty("alt", match.groupValues[1]) })
                    offset = match.range.last + 1
                }
                prose.append(line.substring(offset)).append('\n'); index++; continue
            }
            val separators = lines.getOrNull(index + 1)?.let(::markdownCells)
            if (fence == null && line.contains('|') && separators != null && separators.size > 1 && separators.all { it.matches(Regex(":?-{3,}:?")) }) {
                val headers = markdownCells(line)
                if (headers.size == separators.size) {
                    flush()
                    val columns = JsonArray().apply { headers.forEachIndexed { column, header -> add(JsonObject().apply {
                        addProperty("key", column.toString()); addProperty("header", header)
                        addProperty("align", if (separators[column].startsWith(':') && separators[column].endsWith(':')) "center" else if (separators[column].endsWith(':')) "right" else "left")
                    }) } }
                    val rows = JsonArray(); index += 2
                    while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                        val cells = markdownCells(lines[index++]); rows.add(JsonObject().apply { headers.indices.forEach { addProperty(it.toString(), cells.getOrNull(it).orEmpty()) } })
                    }
                    table(context, parent, JsonObject().apply { add("columns", columns); add("rows", rows); addProperty("markdown_cells", true) }, message)
                    continue
                }
            }
            prose.append(line).append('\n'); index++
        }
        flush()
    }

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
                html.append("<p>").append(paragraph.joinToString("<br>") { markdownInline(it) })
                    .append("</p>")
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

        expandMarkdownReferences(text).replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
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
                else "" to unordered!!.groupValues[1].replace(Regex("^\\[([ xX])]\\s+")) { if (it.groupValues[1].equals("x", true)) "☑ " else "☐ " }
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
        markdownCache.get(text)?.let { return it }
        val accent = color(theme.primary, context.artemisColor(theme, R.color.artemis_primary))
        return Html.fromHtml(
            markdownHtml(text),
            Html.FROM_HTML_MODE_COMPACT,
            null,
            MarkdownTagHandler(context, accent),
        ).also { markdownCache.put(text, it) }
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
        line =
            Regex("!?\\[([^]]+)]\\(([^ )]+)(?:\\s+[\"']([^\"']*)[\"'])?\\)").replace(line) { match ->
                val label = Html.escapeHtml(match.groupValues[1])
                val destination = match.groupValues[2]
                val uri = runCatching { URI(destination) }.getOrNull()
                if (match.value.startsWith("!")) {
                    protect(label)
                } else if (navigationUrl(destination) != null) {
                    val href = Html.escapeHtml(navigationUrl(destination)!!.replace("\"", "%22"))
                    protect("<a href=\"$href\">$label</a>")
                } else {
                    protect(label)
                }
            }
        line = Regex("<(https?://[^ >]+|mailto:[^ >]+)>").replace(line) { match ->
            navigationUrl(match.groupValues[1])?.let { protect("<a href=\"${Html.escapeHtml(it)}\">${Html.escapeHtml(match.groupValues[1].removePrefix("mailto:"))}</a>") } ?: match.value
        }
        line = Regex("(?<![\\w\"'=])(https?://[^\\s<>]+)").replace(line) { match ->
            val value = match.value.trimEnd('.', ',', ';', '!', '?')
            navigationUrl(value)?.let { protect("<a href=\"${Html.escapeHtml(it)}\">${Html.escapeHtml(value)}</a>") + match.value.removePrefix(value) } ?: match.value
        }
        var escaped = Html.escapeHtml(line)
        escaped = escaped
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("__(.+?)__"), "<b>$1</b>")
            .replace(Regex("(?<!\\*)\\*([^*]+?)\\*(?!\\*)"), "<i>$1</i>")
            .replace(Regex("(?<!_)_([^_]+?)_(?!_)"), "<i>$1</i>")
            .replace(Regex("~~(.+?)~~"), "<del>$1</del>")
        placeholders.forEachIndexed { index, replacement ->
            escaped = escaped.replace("${'$'}ARTEMIS$index$", replacement)
        }
        return escaped
    }

    private fun ViewGroup.children() = (0 until childCount).map { getChildAt(it) }


    private fun templateChip(
        context: Context,
        parent: ViewGroup
    ): com.google.android.material.chip.Chip =
        (android.view.LayoutInflater.from(context).inflate(
            R.layout.artemis_template_chip,
            parent,
            false
        ) as com.google.android.material.chip.Chip).apply {
            val accent = android.content.res.ColorStateList.valueOf(
                color(
                    theme.primary, context.artemisColor(
                        theme,
                        R.color.artemis_primary
                    )
                )
            )
            setTextColor(accent)
            chipStrokeColor = accent
        }

    private fun templateButton(context: Context, parent: ViewGroup): Button =
        (android.view.LayoutInflater.from(context)
            .inflate(R.layout.artemis_template_action, parent, false) as Button).apply {
            setTextColor(color(theme.primary, context.artemisColor(theme, R.color.artemis_primary)))
        }

}
