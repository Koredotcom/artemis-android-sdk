package com.artemis.uisdk

/** Host integration for an app-owned browser/auth flow. Launch only after an explicit user tap. */
fun interface ArtemisAuthorizationLauncher {
    fun launch(request: ArtemisAuthorizationRequest)
}

/** A one-use request scoped to the handle, conversation and card row that created it. */
class ArtemisAuthorizationRequest internal constructor(
    val requestId: String,
    val sessionId: String,
    val messageId: String,
    val rowId: String,
    val profileName: String,
    val authorizationUrl: String,
) {
    override fun toString() = "ArtemisAuthorizationRequest(requestId=$requestId, authorizationUrl=<redacted>)"
}

/** DISMISSED/FAILED restore retry; they do not cancel a paused workflow. */
enum class ArtemisAuthorizationResult { COMPLETED, DISMISSED, FAILED }
