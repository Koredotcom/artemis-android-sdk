package com.artemis.uisdk.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.artemis.uisdk.ArtemisContactCenterConfig
import com.artemis.uisdk.ArtemisUI
import com.artemis.uisdk.ArtemisUiEvent
import com.artemis.uisdk.R
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.util.Locale
import kotlin.math.roundToInt

/**
 * AudioCodes WebRTC surface for an accepted Contact Center call.
 *
 * Runtime supplies the call-specific SIP credentials only after the client has
 * sent contact_center.call_response(accepted). They are kept in this Activity's
 * in-memory Intent while the call is active and are never written to logs.
 */
class ArtemisContactCenterCallActivity : Activity() {
    companion object {
        const val EXTRA_ENDPOINT_JSON = "artemis.contact_center.endpoint"
        const val EXTRA_SBC_JSON = "artemis.contact_center.sbc"
        const val EXTRA_RUNTIME_ENDPOINT = "artemis.contact_center.runtime_endpoint"
        const val EXTRA_SIP_STACK_URL = "artemis.contact_center.sip_stack_url"
        const val EXTRA_HANDLE_IDENTIFIER = "artemis.contact_center.handle"
        const val EXTRA_END_REASON = "artemis.contact_center.end_reason"
        const val RESULT_REMOTE_TERMINATED = Activity.RESULT_FIRST_USER + 1
        private const val PERMISSION_REQUEST = 7412

        @Volatile private var activeActivity: ArtemisContactCenterCallActivity? = null

        /** Ends the visible call screen when Runtime reports the call ended remotely. */
        fun finishActiveAsRemote() {
            activeActivity?.runOnUiThread { activeActivity?.finishWithReason("remote_ended", RESULT_REMOTE_TERMINATED) }
        }

        fun intent(
            context: Context,
            endpoint: AgentMediaEndpoint,
            sbc: AgentMediaSbcDetails?,
            runtimeEndpoint: String,
            config: ArtemisContactCenterConfig,
            handleIdentifier: String? = null,
        ): Intent = Intent(context, ArtemisContactCenterCallActivity::class.java).apply {
            putExtra(EXTRA_ENDPOINT_JSON, Gson().toJson(endpoint))
            sbc?.let { putExtra(EXTRA_SBC_JSON, Gson().toJson(it)) }
            putExtra(EXTRA_RUNTIME_ENDPOINT, runtimeEndpoint)
            config.sipStackUrl?.let { putExtra(EXTRA_SIP_STACK_URL, it) }
            handleIdentifier?.let { putExtra(EXTRA_HANDLE_IDENTIFIER, it) }
        }
    }

    private val gson = Gson()
    private lateinit var endpoint: AgentMediaEndpoint
    private var fallbackSbc: AgentMediaSbcDetails? = null
    private var webView: WebView? = null
    private var statusView: TextView? = null
    private var headerView: View? = null
    private var controlsView: View? = null
    private var muteControl: TextView? = null
    private var cameraControl: TextView? = null
    private var previewControl: TextView? = null
    private var fullscreenControl: TextView? = null
    private var errorView: TextView? = null
    private var trustedOrigin: URI? = null
    private var ready = false
    private var callStarted = false
    private var permissionsGranted = false
    private var muted = false
    private var cameraEnabled = true
    private var previewVisible = true
    private var resultSent = false
    private var stopping = false
    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneState: Boolean? = null
    private var hasConnected = false
    private var fullscreen = false
    private val uiHandle get() = intent.getStringExtra(EXTRA_HANDLE_IDENTIFIER)?.let(ArtemisUI::resolveHandle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivity = this
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = normalSystemUiFlags()

        val parsedEndpoint = runCatching {
            gson.fromJson(intent.getStringExtra(EXTRA_ENDPOINT_JSON), AgentMediaEndpoint::class.java)
        }.getOrNull()
        if (parsedEndpoint == null) {
            finishWithReason("invalid_call_endpoint")
            return
        }
        endpoint = parsedEndpoint
        fallbackSbc = runCatching {
            intent.getStringExtra(EXTRA_SBC_JSON)?.let { gson.fromJson(it, AgentMediaSbcDetails::class.java) }
        }.getOrNull()

        val runtimeEndpoint = intent.getStringExtra(EXTRA_RUNTIME_ENDPOINT)
        val scriptUrl = intent.getStringExtra(EXTRA_SIP_STACK_URL)
        val mediaUrls = resolveMediaUrls(runtimeEndpoint, scriptUrl)
        if (mediaUrls == null) {
            finishWithReason("invalid_media_url")
            return
        }
        trustedOrigin = URI(mediaUrls.first)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(9, 13, 18)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerView = buildHeader()
        column.addView(headerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))

        val stage = FrameLayout(this)
        val view = WebView(this)
        webView = view
        configureWebView(view)
        stage.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (endpoint.callType == "video") {
            fullscreenControl = TextView(this).apply {
                text = "Fullscreen"
                contentDescription = "Fullscreen"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { setColor(Color.argb(166, 0, 0, 0)); cornerRadius = dp(16).toFloat() }
                setOnClickListener { toggleFullscreen() }
            }
            stage.addView(fullscreenControl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
        }
        column.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        errorView = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.rgb(254, 202, 202))
            setBackgroundColor(Color.rgb(127, 29, 29))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        column.addView(errorView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        controlsView = buildControls()
        column.addView(controlsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        view.loadDataWithBaseURL(
            mediaUrls.first,
            document(mediaUrls.second, endpoint.callType == "video"),
            "text/html",
            "UTF-8",
            null,
        )
        requestMediaPermissions()
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(6), dp(18), dp(6))
        setBackgroundColor(Color.WHITE)
        addView(LinearLayout(this@ArtemisContactCenterCallActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = if (endpoint.callType == "video") "Video call" else "Audio call"
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.rgb(15, 23, 42))
            })
            statusView = TextView(context).apply {
                text = "Connecting ${if (endpoint.callType == "video") "video" else "audio"} call…"
                textSize = 13f
                setTextColor(Color.rgb(100, 116, 139))
            }
            addView(statusView)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(TextView(this@ArtemisContactCenterCallActivity).apply {
            text = "End"
            textSize = 14f
            setTextColor(Color.rgb(220, 38, 38))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            contentDescription = "End call"
            setOnClickListener { stopAndFinish("user_ended") }
        })
    }

    private fun buildControls(): View = CallControlsFlowLayout(this).apply {
        setPadding(dp(8), dp(14), dp(8), dp(14))
        setBackgroundColor(Color.WHITE)

        muteControl = addControl("Mute") {
            muted = !muted
            muteControl?.apply {
                text = if (muted) "Unmute" else "Mute"
                contentDescription = if (muted) "Unmute microphone" else "Mute microphone"
                isSelected = muted
            }
            evaluate("window.setContactCenterMuted && window.setContactCenterMuted($muted);")
        }
        if (endpoint.callType == "video") {
            cameraControl = addControl("Camera off") {
                cameraEnabled = !cameraEnabled
                cameraControl?.apply {
                    text = if (cameraEnabled) "Camera off" else "Camera on"
                    contentDescription = if (cameraEnabled) "Turn camera off" else "Turn camera on"
                    isSelected = !cameraEnabled
                }
                evaluate("window.setContactCenterCameraEnabled && window.setContactCenterCameraEnabled($cameraEnabled);")
            }
            previewControl = addControl("Hide preview") {
                previewVisible = !previewVisible
                previewControl?.text = if (previewVisible) "Hide preview" else "Show preview"
                evaluate("window.setContactCenterPreviewVisible && window.setContactCenterPreviewVisible($previewVisible);")
            }
        }
        addControl("Hang up", destructive = true) { stopAndFinish("user_ended") }
    }

    private fun CallControlsFlowLayout.addControl(
        label: String,
        destructive: Boolean = false,
        action: () -> Unit,
    ): TextView {
        val button = TextView(this@ArtemisContactCenterCallActivity).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (destructive) Color.WHITE else Color.rgb(15, 23, 42))
            contentDescription = label
            background = GradientDrawable().apply {
                setColor(if (destructive) Color.rgb(220, 38, 38) else Color.WHITE)
                cornerRadius = dp(22).toFloat()
                if (!destructive) setStroke(dp(1), Color.rgb(203, 213, 225))
            }
            setPadding(dp(8), dp(11), dp(8), dp(11))
            minHeight = dp(44)
            setOnClickListener { action() }
        }
        addView(button, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        return button
    }

    private fun updateConnectedUi() {
        hasConnected = true
        statusView?.text = "Connected"
        errorView?.visibility = View.GONE
        webView?.alpha = 1f
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        fullscreenControl?.text = if (fullscreen) "Exit fullscreen" else "Fullscreen"
        fullscreenControl?.contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen"
        headerView?.visibility = if (fullscreen) View.GONE else View.VISIBLE
        controlsView?.visibility = if (fullscreen) View.GONE else View.VISIBLE
    }

    private fun normalSystemUiFlags(): Int = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
        if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun configureWebView(view: WebView) {
        view.setBackgroundColor(Color.BLACK)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.addJavascriptInterface(Bridge(), "AndroidContactCenter")
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean = true
            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean = true
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) postError(error.description?.toString() ?: "Unable to load the call media")
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (!permissionsGranted || !CallOriginPolicy.sameOrigin(trustedOrigin, request.origin?.toString())) {
                        request.deny()
                        return@runOnUiThread
                    }
                    val allowed = request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                            endpoint.callType == "video" && it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }.toTypedArray()
                    if (allowed.isEmpty()) request.deny() else request.grant(allowed)
                }
            }
        }
    }

    private fun requestMediaPermissions() {
        val requested = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (endpoint.callType == "video") requested += Manifest.permission.CAMERA
        val missing = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            permissionsGranted = true
            configureCommunicationAudio()
            startCallWhenReady()
        } else requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        permissionsGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (permissionsGranted) {
            configureCommunicationAudio()
            startCallWhenReady()
        } else postError(if (endpoint.callType == "video") "Microphone and camera permissions are required to place this call." else "Microphone permission is required to place this call.", "Call permission required")
    }

    /** WebView's Chromium audio backend needs communication mode in addition to RECORD_AUDIO. */
    private fun configureCommunicationAudio() {
        if (audioManager != null) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        previousAudioMode = manager.mode
        previousSpeakerphoneState = manager.isSpeakerphoneOn
        runCatching {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            manager.isSpeakerphoneOn = true
        }
    }

    private fun startCallWhenReady() {
        if (!permissionsGranted || !ready || stopping || callStarted) return
        val sbc = resolvedSbc()
        if (sbc == null) {
            postError("Contact Center SIP details are unavailable")
            return
        }
        val target = endpoint.sipUser?.trim()?.takeIf(String::isNotBlank) ?: sipTarget(endpoint.sipUri)
        val customerUser = endpoint.customerSipUser?.trim()?.takeIf(String::isNotBlank)
            ?: endpoint.customerUserId?.trim()?.takeIf(String::isNotBlank)
        val password = endpoint.customerSipPassword?.takeIf(String::isNotBlank)
        if (target.isNullOrBlank() || customerUser.isNullOrBlank() || password.isNullOrBlank()) {
            postError("Contact Center SIP identity is incomplete")
            return
        }
        val headers = if (endpoint.callType == "video") {
            val conversation = endpoint.providerConversationId ?: endpoint.conversationId.orEmpty()
            listOf("X-cId:$conversation", "X-isVideoCall:true")
        } else emptyList()
        val payload = JsonObject().apply {
            addProperty("callId", endpoint.callId)
            add("addresses", gson.toJsonTree(sbc.first))
            addProperty("domain", sbc.second)
            add("iceServers", gson.toJsonTree(sbc.third))
            add("customer", JsonObject().apply {
                addProperty("sipUser", customerUser)
                addProperty("sipPassword", password)
                addProperty("sipAuthUser", endpoint.customerSipAuthUser ?: customerUser)
                addProperty("displayName", endpoint.customerDisplayName ?: customerUser)
            })
            addProperty("target", target)
            addProperty("callType", endpoint.callType)
            add("headers", gson.toJsonTree(headers))
        }
        statusView?.text = if (endpoint.callType == "video") "Connecting video call…" else "Connecting audio call…"
        callStarted = true
        evaluate("void window.startContactCenterCall(${gson.toJson(payload)});")
    }

    private fun resolvedSbc(): Triple<List<String>, String, List<AgentMediaIceServer>>? {
        val endpointAddresses = endpoint.addresses
        val endpointDomain = endpoint.domain?.trim()?.takeIf(String::isNotBlank) ?: sipUriDomain(endpoint.sipUri)
        if (endpointAddresses.isNotEmpty() && !endpointDomain.isNullOrBlank()) {
            return Triple(endpointAddresses, endpointDomain, endpoint.iceServers)
        }
        val fallback = fallbackSbc ?: return null
        return fallback.addresses.takeIf { it.isNotEmpty() }
            ?.let { addresses -> fallback.domain?.trim()?.takeIf(String::isNotBlank)?.let { Triple(addresses, it, fallback.iceServers) } }
    }

    private fun resolveMediaUrls(runtime: String?, override: String?): Pair<String, String>? {
        val base = secureUrl(runtime) ?: return null
        val script = if (override.isNullOrBlank()) {
            URI(base).resolve("/api/sdk/assets/contact-center/ac_webrtc-1.19.0.min.js").toString()
        } else secureUrl(override)
        if (script == null) return null
        if (URI(base).scheme.equals("http", true) && !URI(script).scheme.equals("https", true)) return null
        return Pair(base.trimEnd('/') + "/", script)
    }

    private fun secureUrl(value: String?): String? = runCatching {
        val uri = URI(value?.trim().orEmpty())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)?.trim('[', ']')
        if (host.isNullOrBlank() || uri.userInfo != null) return@runCatching null
        if (scheme == "https" || scheme == "http" && host in setOf("localhost", "127.0.0.1", "::1")) uri.toString() else null
    }.getOrNull()

    private fun sipTarget(value: String?): String? {
        val trimmed = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val noScheme = trimmed.removePrefix("sip:").removePrefix("sips:")
        return noScheme.substringBefore('@').substringBefore(';').substringBefore(':').takeIf(String::isNotBlank)
    }

    private fun sipUriDomain(value: String?): String? {
        val trimmed = value?.trim()?.removePrefix("sip:")?.removePrefix("sips:") ?: return null
        return trimmed.substringAfter('@', "").substringBefore(';').substringBefore(':').takeIf(String::isNotBlank)
    }

    private fun evaluate(script: String) { webView?.post { webView?.evaluateJavascript(script, null) } }

    private fun postError(message: String, status: String = "Unable to connect") {
        if (resultSent || isFinishing) return
        statusView?.text = status
        errorView?.apply { text = message; visibility = View.VISIBLE; announceForAccessibility(message) }
    }

    private fun stopAndFinish(reason: String) {
        if (resultSent) return
        stopping = true
        evaluate("window.stopContactCenterCall && window.stopContactCenterCall();")
        finishWithReason(reason)
    }

    private fun finishWithReason(reason: String, resultCode: Int = RESULT_OK) {
        if (resultSent) return
        resultSent = true
        stopping = true
        if (reason != "remote_ended") evaluate("window.stopContactCenterCall && window.stopContactCenterCall();")
        setResult(resultCode, Intent().putExtra(EXTRA_END_REASON, reason))
        finish()
    }

    override fun onBackPressed() { stopAndFinish("user_ended") }

    override fun onDestroy() {
        if (!resultSent) {
            stopping = true
            evaluate("window.stopContactCenterCall && window.stopContactCenterCall();")
            setResult(RESULT_OK, Intent().putExtra(EXTRA_END_REASON, "user_ended"))
        }
        if (activeActivity === this) activeActivity = null
        webView?.removeJavascriptInterface("AndroidContactCenter")
        webView?.destroy()
        webView = null
        audioManager?.let { manager ->
            runCatching {
                previousSpeakerphoneState?.let { manager.isSpeakerphoneOn = it }
                previousAudioMode?.let { manager.mode = it }
            }
        }
        audioManager = null
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(value: String?) {
            val message = runCatching { value?.let { gson.fromJson(it, JsonObject::class.java) } }.getOrNull() ?: return
            val type = message.string("type") ?: return
            runOnUiThread {
                // A WebView message can be queued before hang-up but delivered after it.
                if (stopping || resultSent || isFinishing) return@runOnUiThread
                when (type) {
                    "ready" -> {
                        ready = true
                        statusView?.text = if (endpoint.callType == "video") "Connecting video call…" else "Connecting audio call…"
                        startCallWhenReady()
                    }
                    "connected" -> {
                        updateConnectedUi()
                        uiHandle?.emit(ArtemisUiEvent.ContactCenterCallAccepted(endpoint))
                        uiHandle?.emit(ArtemisUiEvent.ContactCenterStateChanged("in_call", callId = endpoint.callId))
                    }
                    "local_stream" -> uiHandle?.emit(ArtemisUiEvent.ContactCenterLocalStream(endpoint.callId, endpoint.callType))
                    "remote_stream" -> uiHandle?.emit(ArtemisUiEvent.ContactCenterRemoteStream(endpoint.callId, endpoint.callType))
                    "fullscreen" -> toggleFullscreen()
                    "terminated" -> if (!resultSent) {
                        val established = message.get("established")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
                        if (hasConnected || established) finishWithReason(message.string("reason") ?: "remote_ended", RESULT_REMOTE_TERMINATED)
                        else postError(message.string("reason")?.takeIf { it.isNotBlank() }?.let { "The call ended before it connected ($it)." } ?: "The call ended before it connected.")
                    }
                    "error" -> {
                        val error = message.string("message") ?: "The call could not connect"
                        uiHandle?.emit(ArtemisUiEvent.ContactCenterError(error))
                        uiHandle?.emit(ArtemisUiEvent.ContactCenterStateChanged("error", error, endpoint.callId))
                        postError(error)
                    }
                }
            }
        }
    }

    private fun document(scriptUrl: String, video: Boolean): String {
        val scriptLiteral = gson.toJson(scriptUrl)
        val audioBadge = if (video) "" else "<div id=\"audioBadge\"><div class=\"audioCircle\">☎</div><div>Audio call</div></div>"
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <style>
              html,body{margin:0;width:100%;height:100%;background:#090d12;overflow:hidden}
              #stage{position:relative;width:100%;height:100%;background:#090d12;display:flex;align-items:center;justify-content:center}
              video{background:#090d12;visibility:hidden;opacity:0;pointer-events:none;transition:opacity .18s ease}
              video.has-stream{visibility:visible;opacity:1}
              #remote{width:100%;height:100%;object-fit:contain}
              #localCard{display:none;position:absolute;right:14px;bottom:14px;width:30%;max-width:190px;padding:0 0 8px;background:#a9d7f7;border-radius:16px;box-shadow:0 5px 16px #0007;overflow:hidden}
              #localCard.camera-off{padding-bottom:0;background:#090d12}
              #localCard.has-stream{display:block}
              #local{display:block;width:100%;max-height:180px;aspect-ratio:4/3;object-fit:cover;border:0;border-radius:0}
              #localClose{position:absolute;z-index:2;right:0;top:0;width:38px;height:38px;border:0;background:transparent;color:#0f172a;font:30px sans-serif;line-height:30px}
              #fullscreen{position:absolute;right:16px;top:16px;width:48px;height:48px;border:0;border-radius:50%;background:#fff;color:#0f172a;font:28px sans-serif;box-shadow:0 3px 12px #0005}
              #audioBadge{position:absolute;left:50%;top:50%;transform:translate(-50%,-37px);display:flex;flex-direction:column;align-items:center;gap:12px;color:#dbeafe;font:500 16px sans-serif}
              .audioCircle{width:74px;height:74px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:#1d4ed8;color:#fff;font:32px sans-serif;box-shadow:0 8px 24px #0006}
            </style>
            </head><body><div id="stage">$audioBadge<video id="remote" autoplay playsinline disablepictureinpicture></video><div id="localCard"><video id="local" autoplay muted playsinline disablepictureinpicture></video></div></div>
            <script>
            (() => {
              const post = (type, value = {}) => { try { AndroidContactCenter.postMessage(JSON.stringify({type, ...value})); } catch (_) {} };
              let phone = null, activeCall = null, stopping = false, generation = 0, cancelPending = null, confirmed = false;
              let desiredMuted = false, desiredCamera = true;
              const remote = document.getElementById('remote'), local = document.getElementById('local'), localCard = document.getElementById('localCard');
              const script = document.createElement('script'); script.src = $scriptLiteral;
              script.onload = () => { if (typeof AudioCodesUA === 'function' && typeof JsSIP !== 'undefined') post('ready'); else post('error', {message:'AudioCodes WebRTC SDK is unavailable'}); };
              script.onerror = () => post('error', {message:'Unable to load the AudioCodes WebRTC SDK'}); document.head.appendChild(script);
              window.startContactCenterCall = async (input) => {
                if (phone) window.stopContactCenterCall();
                const attempt = ++generation;
                stopping = false; confirmed = false;
                let currentPhone, timeout, pending = true;
                try {
                  currentPhone = new AudioCodesUA(); phone = currentPhone;
                  let resolveConnection, rejectConnection;
                  const connection = new Promise((resolve, reject) => { resolveConnection = resolve; rejectConnection = reject; });
                  cancelPending = () => rejectConnection(new Error('Call stopped'));
                  currentPhone.setAcLogger?.(() => {}); currentPhone.setJsSipLogger?.(() => {}); currentPhone.setServerConfig(input.addresses, input.domain, input.iceServers);
                  currentPhone.setAccount(input.customer.sipUser, input.customer.displayName, input.customer.sipPassword, input.customer.sipAuthUser);
                  currentPhone.setUserAgent?.('AudioCodes-WebRTC'); currentPhone.setRegisterExtraHeaders?.(['X-SBC: AudioCodes Mediant']); currentPhone.setWebSocketKeepAlive?.(10, true, true, 0, false);
                  currentPhone.setReconnectIntervals?.(2, 30); currentPhone.setRegisterExpires?.(600); currentPhone.setDtmfOptions?.(true, null, null); currentPhone.setEnableAddVideo?.(true);
                  currentPhone.setListeners({
                    loginStateChanged: (_loggedIn, cause) => { if (attempt !== generation || stopping || !pending) return; if (cause === 'login' || cause === 'connected') { pending = false; resolveConnection(); } else if (cause === 'login failed') { pending = false; rejectConnection(new Error('AudioCodes login failed')); } },
                    callConfirmed: () => { if (attempt === generation && !stopping) { confirmed = true; post('connected'); } },
                    callTerminated: (_call, _message, cause) => { if (attempt !== generation || stopping) return; activeCall = null; post('terminated', {reason: typeof cause === 'string' ? cause : undefined, established: confirmed}); },
                    callShowStreams: (_call, localStream, remoteStream) => { if (attempt !== generation || stopping) return; if (localStream instanceof MediaStream && input.callType === 'video') { local.muted = true; local.srcObject = localStream; local.classList.add('has-stream'); localCard.classList.add('has-stream'); local.play().catch(() => {}); post('local_stream'); } if (remoteStream instanceof MediaStream) { remote.srcObject = remoteStream; remote.classList.add('has-stream'); remote.volume = 1; remote.play().catch(() => {}); post('remote_stream'); } },
                    outgoingCallProgress: () => {}, incomingCall: (call) => call && call.reject && call.reject(), callHoldStateChanged: () => {}, callScreenSharingEnded: () => {}
                  });
                  currentPhone.init(true); await Promise.race([connection, new Promise((_resolve, reject) => { timeout = setTimeout(() => reject(new Error('Timed out connecting to the Contact Center SBC')), 15000); })]);
                  if (attempt !== generation || stopping) return;
                  activeCall = currentPhone.call(input.callType === 'video' ? currentPhone.VIDEO : currentPhone.AUDIO, input.target, input.headers);
                  if (desiredMuted) activeCall?.muteAudio?.(true);
                  if (!desiredCamera) activeCall?.muteVideo?.(true);
                } catch (error) {
                  if (attempt === generation && !stopping) { try { currentPhone?.logout?.(); } catch (_) {} try { currentPhone?.deinit?.(); } catch (_) {} phone = null; post('error', {message: error && error.message ? error.message : String(error)}); }
                } finally { pending = false; if (timeout != null) clearTimeout(timeout); if (attempt === generation) cancelPending = null; }
              };
              window.setContactCenterMuted = (muted) => { desiredMuted = Boolean(muted); if (activeCall?.muteAudio) activeCall.muteAudio(desiredMuted); };
              window.setContactCenterCameraEnabled = (enabled) => { desiredCamera = Boolean(enabled); if (activeCall?.muteVideo) activeCall.muteVideo(!desiredCamera); localCard.classList.toggle('camera-off', !enabled); };
              window.setContactCenterPreviewVisible = (visible) => { localCard.style.visibility = visible ? 'visible' : 'hidden'; };
              window.stopContactCenterCall = () => { generation += 1; stopping = true; if (cancelPending) { cancelPending(); cancelPending = null; } try { activeCall?.terminate?.(); } catch (_) {} try { phone?.logout?.(); } catch (_) {} try { phone?.deinit?.(); } catch (_) {} activeCall = null; phone = null; [remote, local].forEach((element) => { if (element.srcObject) element.srcObject.getTracks().forEach((track) => track.stop()); element.srcObject = null; element.classList.remove('has-stream'); }); localCard.classList.remove('has-stream'); };
            })();
            </script></body></html>
        """.trimIndent()
    }

}

internal object CallOriginPolicy {
    fun sameOrigin(expected: URI?, actual: String?): Boolean {
        val received = runCatching { URI(actual ?: return false) }.getOrNull() ?: return false
        if (expected == null || expected.userInfo != null || received.userInfo != null) return false
        val scheme = expected.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme !in setOf("https", "http") || received.scheme?.lowercase(Locale.ROOT) != scheme) return false
        if (expected.host.isNullOrBlank() || !expected.host.equals(received.host, ignoreCase = true)) return false
        fun port(value: URI): Int = if (value.port >= 0) value.port else if (scheme == "https") 443 else 80
        return port(expected) == port(received)
    }
}

/** Wrap call controls on narrow screens while keeping each touch target usable. */
internal class CallControlsFlowLayout(context: Context) : ViewGroup(context) {
    private data class Row(val children: MutableList<View> = mutableListOf(), var width: Int = 0, var height: Int = 0)
    private val rows = mutableListOf<Row>()
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateLayoutParams(params: LayoutParams?): LayoutParams = MarginLayoutParams(params)
    override fun checkLayoutParams(params: LayoutParams?): Boolean = params is MarginLayoutParams
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        rows.clear()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var row = Row()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            if (row.children.isNotEmpty() && row.width + childWidth > available) { rows.add(row); row = Row() }
            row.children.add(child); row.width += childWidth; row.height = maxOf(row.height, childHeight)
        }
        if (row.children.isNotEmpty()) rows.add(row)
        val desiredHeight = paddingTop + paddingBottom + rows.sumOf { it.height }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = right - left - paddingLeft - paddingRight
        var y = paddingTop
        rows.forEach { row ->
            var x = paddingLeft + ((available - row.width) / 2).coerceAtLeast(0)
            row.children.forEach { child ->
                val params = child.layoutParams as MarginLayoutParams
                x += params.leftMargin
                val childTop = y + params.topMargin + (row.height - params.topMargin - params.bottomMargin - child.measuredHeight) / 2
                child.layout(x, childTop, x + child.measuredWidth, childTop + child.measuredHeight)
                x += child.measuredWidth + params.rightMargin
            }
            y += row.height
        }
    }
}

private class DottedBackgroundDrawable(private val density: Float) : Drawable() {
    private val backgroundColor = Color.rgb(255, 250, 255)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(222, 226, 232) }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        val spacing = 34f * density
        val radius = 1.25f * density
        var y = spacing * .55f
        while (y < bounds.height()) {
            var x = spacing * .55f
            while (x < bounds.width()) {
                canvas.drawCircle(x, y, radius, paint)
                x += spacing
            }
            y += spacing
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
