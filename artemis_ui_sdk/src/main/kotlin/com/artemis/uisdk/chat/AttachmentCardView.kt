package com.artemis.uisdk.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.artemis.uisdk.R

/** File name and metadata have separate lines so long names cannot crowd out the action. */
internal fun createAttachmentCard(
    context: Context,
    attachment: AttachmentRef,
    isUser: Boolean,
    strings: com.artemis.uisdk.ArtemisStrings = com.artemis.uisdk.ArtemisStrings(),
    theme: com.artemis.uisdk.ArtemisThemeConfig = com.artemis.uisdk.ArtemisThemeConfig(),
    onOpen: () -> Unit
): View {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun color(value: String?, fallback: Int) =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    val foreground = if (isUser) color(theme.userBubbleText, Color.WHITE) else color(
        theme.text,
        context.getColor(R.color.artemis_text)
    )
    val secondary =
        if (isUser) foreground else color(theme.mutedText, context.getColor(R.color.artemis_muted))
    val accent =
        if (isUser) foreground else color(theme.primary, context.getColor(R.color.artemis_primary))
    val fileType = AttachmentFileTypes.resolve(attachment.filename, attachment.mimeType)
    val metadata =
        if (attachment.deliveryFailed) strings.text(context, R.string.artemis_attachment_not_sent)
        else listOfNotNull(fileType.label, attachment.sizeBytes.takeIf { it > 0 }?.let {
            android.text.format.Formatter.formatShortFileSize(context, it)
        }).joinToString(" · ")

    fun icon(resource: Int, sizeResource: Int) = ImageView(context).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(accent)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(
            context.resources.getDimensionPixelSize(sizeResource),
            context.resources.getDimensionPixelSize(sizeResource)
        )
    }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val surface = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(
                if (isUser) 0x29FFFFFF else color(
                    theme.surface,
                    context.getColor(R.color.artemis_surface)
                )
            )
            setStroke(
                dp(1).coerceAtLeast(1),
                if (isUser) 0x47FFFFFF else color(
                    theme.border,
                    context.getColor(R.color.artemis_border)
                )
            )
        }
        background = RippleDrawable(
            ColorStateList.valueOf(
                if (isUser) 0x33FFFFFF else color(
                    theme.border,
                    context.getColor(R.color.artemis_border)
                )
            ), surface, null
        )
        addView(icon(fileType.icon, R.dimen.artemis_icon_file).apply {
            imageTintList =
                ColorStateList.valueOf(if (isUser) Color.WHITE else context.getColor(fileType.color))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(
                TextView(context).apply {
                    text = attachment.filename
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(foreground)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                TextView(context).apply {
                    text = metadata
                    textSize = 12f
                    setTextColor(secondary)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10); marginEnd = dp(10)
        })
        if (!attachment.deliveryFailed) addView(
            icon(
                R.drawable.artemis_ic_download,
                R.dimen.artemis_icon_action
            )
        )
        setOnClickListener { onOpen() }
        isEnabled = !attachment.deliveryFailed
        isFocusable = !attachment.deliveryFailed
        alpha = if (attachment.deliveryFailed) .72f else 1f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = if (attachment.deliveryFailed) "${attachment.filename}. $metadata"
        else "${
            strings.text(
                context,
                R.string.artemis_open_attachment,
                attachment.filename
            )
        }. $metadata"
        // Expose the whole row as one action to TalkBack.
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: android.view.accessibility.AccessibilityNodeInfo
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
    }
}

/** A batch starts with one compact summary, then expands to individually openable files. */
internal fun createAttachmentGroup(
    context: Context,
    attachments: List<AttachmentRef>,
    isUser: Boolean,
    strings: com.artemis.uisdk.ArtemisStrings = com.artemis.uisdk.ArtemisStrings(),
    theme: com.artemis.uisdk.ArtemisThemeConfig = com.artemis.uisdk.ArtemisThemeConfig(),
    onOpen: (AttachmentRef) -> Unit
): View {
    if (attachments.size == 1) return createAttachmentCard(
        context,
        attachments.single(),
        isUser,
        strings,
        theme
    ) { onOpen(attachments.single()) }
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val files = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
        }
        val countLabel = strings.text(
            context,
            if (attachments.size == 1) R.string.artemis_attachment_count_one else R.string.artemis_attachment_count_other,
            attachments.size
        )
        val label = if (attachments.any { it.deliveryFailed }) strings.text(
            context,
            R.string.artemis_attachment_group_failed,
            countLabel
        ) else countLabel
        val summary = TextView(context).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (isUser) Color.WHITE else context.getColor(R.color.artemis_text))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(0, dp(6), 0, dp(6))
            isFocusable = true
            val ripple =
                context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ripple.getDrawable(0); ripple.recycle()
        }

        fun updateSummary() {
            val expanded = files.visibility == View.VISIBLE
            summary.text = label
            summary.contentDescription = strings.text(
                context,
                if (expanded) R.string.artemis_hide_attachments else R.string.artemis_show_attachments,
                label
            )
            fun icon(resource: Int) = context.getDrawable(resource)!!.mutate().apply {
                setTint(if (isUser) Color.WHITE else context.getColor(R.color.artemis_primary))
                val size = context.resources.getDimensionPixelSize(R.dimen.artemis_icon_action)
                setBounds(0, 0, size, size)
            }
            summary.setCompoundDrawablesRelative(
                icon(R.drawable.artemis_ic_attach),
                null,
                icon(if (expanded) R.drawable.artemis_ic_chevron_up else R.drawable.artemis_ic_chevron_down),
                null
            )
            summary.compoundDrawablePadding = dp(10)
        }
        summary.setOnClickListener {
            // Keep the tapped summary visible when stackFromEnd lays out the taller message.
            val layout = this
            val listener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    l: Int,
                    t: Int,
                    r: Int,
                    b: Int,
                    oldL: Int,
                    oldT: Int,
                    oldR: Int,
                    oldB: Int
                ) {
                    layout.removeOnLayoutChangeListener(this)
                    summary.post {
                        if (summary.isAttachedToWindow) summary.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, summary.width, summary.height),
                            true
                        )
                    }
                }
            }
            layout.addOnLayoutChangeListener(listener)
            files.visibility = if (files.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateSummary()
        }
        addView(
            summary,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        attachments.forEach { attachment ->
            files.addView(
                createAttachmentCard(context, attachment, isUser, strings, theme) {
                    onOpen(
                        attachment
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
        }
        addView(files)
        updateSummary()
    }
}
