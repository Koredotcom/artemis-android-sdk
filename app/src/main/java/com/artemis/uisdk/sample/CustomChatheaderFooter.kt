package com.artemis.uisdk.sample

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.artemis.uisdk.ArtemisChatFooterBuilder
import com.artemis.uisdk.ArtemisChatHeaderBuilder

/** Native host-owned replacement views shown by the sample app. */
internal object CustomChatheaderFooter {
    val headerBuilder = ArtemisChatHeaderBuilder { context, header ->
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 8), dp(context, 8))
            setBackgroundColor(Color.rgb(15, 118, 110))
            addView(TextView(context).apply {
                text = "A"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 18f
                setBackgroundColor(0x55FFFFFF)
            }, LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)))
            addView(TextView(context).apply {
                text = header.title
                setTextColor(Color.WHITE)
                textSize = 17f
                setPadding(dp(context, 12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "Minimize"
                setOnClickListener { header.onMinimize() }
            })
            addView(Button(context).apply {
                text = "Close"
                setOnClickListener { header.onClose() }
            })
        }
    }

    val footerBuilder = ArtemisChatFooterBuilder { context, footer ->
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 250, 252))
            addView(footer.attachmentPreview, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                addView(footer.input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                footer.onAttach?.let { onAttach ->
                    addView(Button(context).apply {
                        text = "+"
                        contentDescription = "Attach file"
                        setOnClickListener { onAttach() }
                    })
                }
                addView(Button(context).apply {
                    text = "Send"
                    setOnClickListener { footer.onSend() }
                })
            })
        }
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
