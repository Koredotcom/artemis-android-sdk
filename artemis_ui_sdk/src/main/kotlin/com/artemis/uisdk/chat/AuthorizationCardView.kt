package com.artemis.uisdk.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.artemis.uisdk.ArtemisStrings
import com.artemis.uisdk.ArtemisThemeConfig
import com.artemis.uisdk.R
import com.artemis.uisdk.artemisColor

@SuppressLint("ViewConstructor")
internal class AuthorizationCardView(
    context: Context,
    private val message: ChatMessage,
    private val theme: ArtemisThemeConfig,
    private val strings: ArtemisStrings,
    private val callbacks: ChatMessageAdapter.Callbacks,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(id: Int) = strings.text(context, id)
    private val primary = context.artemisColor(theme, R.color.artemis_primary)
    private val foregroundColor = runCatching { Color.parseColor(theme.primaryForeground) }.getOrDefault(
        if (androidx.core.graphics.ColorUtils.calculateLuminance(primary) > .5) Color.BLACK else Color.WHITE)
    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(context.artemisColor(theme, R.color.artemis_surface)); cornerRadius = dp(12).toFloat()
            setStroke(dp(1), context.artemisColor(theme, R.color.artemis_border))
        }
        val card = message.authorization
        if (card != null) {
            addView(label(text(R.string.artemis_auth_title), true))
            if (!card.active && card.rows.any { it.status != AuthorizationStatus.CONNECTED }) addView(label(text(R.string.artemis_auth_replaced)))
            card.rows.forEach { row ->
                val group = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(0, dp(12), 0, dp(8))
                }
                group.addView(label(row.label, true))
                if (row.prompt.isNotBlank()) group.addView(label(row.prompt))
                if (row.blocking && row.status != AuthorizationStatus.CONNECTED) group.addView(label(text(R.string.artemis_auth_blocking)))
                group.addView(label(text(when (row.status) {
                    AuthorizationStatus.PENDING -> R.string.artemis_auth_pending
                    AuthorizationStatus.AUTHORIZING -> R.string.artemis_auth_authorizing
                    AuthorizationStatus.VERIFYING -> R.string.artemis_auth_verifying
                    AuthorizationStatus.CANCELLING -> R.string.artemis_auth_cancelling
                    AuthorizationStatus.CONNECTED -> R.string.artemis_auth_connected
                    AuthorizationStatus.CANCELLED -> R.string.artemis_auth_cancelled
                    AuthorizationStatus.FAILED -> R.string.artemis_auth_failed
                })).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
                val ready = row.status in setOf(AuthorizationStatus.PENDING, AuthorizationStatus.FAILED)
                if (card.active && ready) {
                    if (AuthorizationContracts.url(row.authUrl) != null) group.addView(button(R.string.artemis_auth_authorize, true) {
                        callbacks.onAuthorize(message, row.id)
                    }) else group.addView(label(text(R.string.artemis_auth_unavailable)))
                    if (row.attempted) group.addView(button(R.string.artemis_auth_check, false) { callbacks.onCheckAuthorization(message, row.id) })
                }
                if (card.active && row.canCancel && (ready || row.status == AuthorizationStatus.AUTHORIZING)) {
                    group.addView(button(R.string.artemis_auth_cancel, false) { callbacks.onCancelAuthorization(message, row.id) })
                }
                addView(group)
            }
        }
    }
    private fun label(value: String, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = if (bold) 16f else 14f
        setTextColor(context.artemisColor(theme, if (bold) R.color.artemis_text else R.color.artemis_muted))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }
    private fun button(id: Int, filled: Boolean, action: () -> Unit) = Button(context).apply {
        text = text(id); isAllCaps = false; textSize = 14f
        minHeight = dp(40); minimumHeight = dp(40)
        setPadding(dp(12), 0, dp(12), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (filled) primary else context.artemisColor(theme, R.color.artemis_surface))
            if (!filled) setStroke(dp(1), context.artemisColor(theme, R.color.artemis_border))
        }
        backgroundTintList = ColorStateList.valueOf(if (filled) primary else context.artemisColor(theme, R.color.artemis_surface))
        setTextColor(if (filled) foregroundColor else primary)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        setOnClickListener { action() }
    }
}
