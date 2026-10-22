package com.artemis.uisdk

import android.content.Context
import android.view.View
import android.widget.EditText
import kotlinx.coroutines.flow.StateFlow

/** Actions and current title supplied to a host-provided chat header. */
data class ArtemisChatHeaderContext(
    val title: String,
    val onMinimize: () -> Unit,
    val onClose: () -> Unit,
)

/** Creates an unattached native view to replace the SDK chat header. */
fun interface ArtemisChatHeaderBuilder {
    fun build(context: Context, header: ArtemisChatHeaderContext): View
}

/** SDK-owned input and actions supplied to a host-provided chat footer. */
data class ArtemisChatFooterContext(
    val input: EditText,
    val enabled: StateFlow<Boolean>,
    val attachmentPreview: View,
    val onSend: () -> Unit,
    val onAttach: (() -> Unit)?,
)

/** Creates an unattached native view to replace the SDK message composer. */
fun interface ArtemisChatFooterBuilder {
    fun build(context: Context, footer: ArtemisChatFooterContext): View
}
