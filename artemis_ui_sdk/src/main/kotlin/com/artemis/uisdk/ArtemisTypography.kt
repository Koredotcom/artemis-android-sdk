package com.artemis.uisdk

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.MainThread

/** Host typography for SDK-owned text. A custom Typeface can come from the host's font resources. */
data class ArtemisTypographyConfig @JvmOverloads constructor(
    val typeface: Typeface? = null,
    val monospaceTypeface: Typeface? = null,
    val textSizeScale: Float = 1f,
    val textViewStyler: ArtemisTextViewStyler? = null,
) {
    init {
        require(textSizeScale.isFinite() && textSizeScale in 0.5f..2f) {
            "typography.textSizeScale must be between 0.5 and 2"
        }
    }

    @MainThread
    internal fun applyTo(root: View) {
        fun visit(view: View) {
            if (view is TextView) {
                val baseSize = (view.getTag(R.id.artemis_original_text_size_px) as? Float)
                    ?: view.textSize.also { view.setTag(R.id.artemis_original_text_size_px, it) }
                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseSize * textSizeScale)
                val isMonospace = view.typeface == Typeface.MONOSPACE
                val selected = if (isMonospace) monospaceTypeface ?: typeface else typeface
                selected?.let { view.typeface = Typeface.create(it, view.typeface?.style ?: Typeface.NORMAL) }
                textViewStyler?.style(view)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }
}

/** Optional fine-grained styling hook, called after SDK text styling on the main thread. */
fun interface ArtemisTextViewStyler {
    fun style(textView: TextView)
}
