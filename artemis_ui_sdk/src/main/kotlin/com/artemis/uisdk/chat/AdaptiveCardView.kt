package com.artemis.uisdk.chat

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.artemis.uisdk.ArtemisThemeConfig
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/** Restricts executable content to the bundled runtime and validates messages from it. */
internal class AdaptiveCardView(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs) {
    private val main = Handler(Looper.getMainLooper())
    private var ids: Set<String> = emptySet()
    private var onAction: ((String, String, Map<String, String>) -> Unit)? = null
    private var onOpen: ((String) -> Unit)? = null
    private var onFailure: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
        }
        addJavascriptInterface(Bridge(), "ArtemisAdaptive")
    }

    fun bind(card: JsonObject, theme: ArtemisThemeConfig, used: Set<String>, action: (String, String, Map<String, String>) -> Unit, open: (String) -> Unit, failure: () -> Unit) {
        ids = AdaptiveCardContracts.actionIds(card)
        onAction = action
        onOpen = open
        onFailure = failure
        val runtime = context.assets.open("artemis/adaptive-runtime.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val html = AdaptiveCardContracts.document(card, runtime, theme, used)
        layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (80 * resources.displayMetrics.density).toInt())
        loadDataWithBaseURL("https://artemis.invalid/", html, "text/html", "UTF-8", null)
    }

    private inner class Bridge {
        @JavascriptInterface fun postMessage(raw: String) {
            if (raw.length > 131072) return
            val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return
            main.post {
                when (json.safeString("type")) {
                    "height" -> {
                        val height = runCatching { json.get("height").asDouble }.getOrNull() ?: return@post
                        if (!height.isFinite()) return@post
                        val px = (height.coerceIn(40.0, 8000.0) * resources.displayMetrics.density).toInt()
                        if (layoutParams.height != px) { layoutParams = layoutParams.apply { this.height = px } }
                    }
                    "open" -> AdaptiveCardContracts.safeHttp(json.safeString("url"))?.let { onOpen?.invoke(it) }
                    "error" -> onFailure?.invoke()
                    "action" -> {
                        val id = json.safeString("id") ?: return@post
                        if (id.length > 512 || id !in ids) return@post
                        val values = mutableMapOf<String, String>()
                        json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.take(200)?.forEach { (key, value) ->
                            if (key.length <= 512) values[key] = (if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()).take(10000)
                        }
                        onAction?.invoke(id, json.safeString("value").orEmpty().take(10000), values)
                    }
                }
            }
        }
    }
}

internal object AdaptiveCardContracts {
    private val supportedElements = setOf("TextBlock", "RichTextBlock", "TextRun", "Image", "ImageSet", "Media", "FactSet", "Container", "ColumnSet", "Column", "ActionSet", "Table", "TableRow", "TableCell", "Input.Text", "Input.Date", "Input.Time", "Input.Number", "Input.ChoiceSet", "Input.Toggle")
    private val supportedActions = setOf("Action.OpenUrl", "Action.Submit", "Action.ShowCard", "Action.ToggleVisibility", "Action.Execute")
    private val resources = setOf("url", "iconurl", "poster", "fallbacksource", "backgroundimage")
    private fun version(value: String) = Regex("1\\.([0-9]+)").matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let { it <= 5 } == true
    fun parse(payload: JsonElement): JsonObject? = runCatching {
        val raw = if (payload.isJsonPrimitive && payload.asJsonPrimitive.isString) payload.asString else payload.toString()
        if (raw.length > 128 * 1024) return null
        val card = JsonParser.parseString(raw).asJsonObject
        if (!withinDepth(card, 0) || !validCard(card, 0)) return null
        card
    }.getOrNull()
    private fun withinDepth(value: JsonElement, depth: Int): Boolean {
        if (depth > 32) return false
        if (value.isJsonArray) return value.asJsonArray.all { withinDepth(it, depth + 1) }
        if (value.isJsonObject) return value.asJsonObject.entrySet().all { withinDepth(it.value, depth + 1) }
        return true
    }
    private fun obj(value: JsonElement?): JsonObject? = value?.takeIf { it.isJsonObject }?.asJsonObject
    private fun array(value: JsonElement?): JsonArray? = value?.takeIf { it.isJsonArray }?.asJsonArray
    private fun validCard(card: JsonObject, depth: Int): Boolean {
        if (depth > 32 || card.safeString("type") != "AdaptiveCard" || card.has("version") && !version(card.safeString("version").orEmpty())) return false
        if (card.has("body") && array(card.get("body"))?.all { validElement(it, depth + 1) } != true) return false
        if (card.has("actions") && array(card.get("actions"))?.all { validAction(it, depth + 1) } != true) return false
        return !card.has("selectAction") || validAction(card.get("selectAction"), depth + 1)
    }
    private fun validAction(element: JsonElement?, depth: Int): Boolean {
        val action = obj(element) ?: return false
        if (depth > 32 || action.safeString("type") !in supportedActions) return false
        val fallback = action.get("fallback")
        if (fallback != null && !(fallback.isJsonPrimitive && fallback.asString == "drop") && !validAction(fallback, depth + 1)) return false
        return when (action.safeString("type")) {
            "Action.OpenUrl" -> !action.safeString("url").isNullOrBlank()
            "Action.ShowCard" -> obj(action.get("card"))?.let { validCard(it, depth + 1) } == true
            "Action.ToggleVisibility" -> !action.has("targetElements") || array(action.get("targetElements")) != null
            "Action.Execute" -> !action.has("verb") || action.safeString("verb") != null
            else -> true
        }
    }
    private fun validElement(element: JsonElement?, depth: Int): Boolean {
        val value = obj(element) ?: return false
        val type = value.safeString("type") ?: return false
        if (depth > 32 || type !in supportedElements) return false
        if (value.has("selectAction") && !validAction(value.get("selectAction"), depth + 1)) return false
        val fallback = value.get("fallback")
        if (fallback != null && !(fallback.isJsonPrimitive && fallback.asString == "drop") && !validElement(fallback, depth + 1)) return false
        fun elements(key: String, expected: String? = null): Boolean = array(value.get(key))?.all {
            (expected == null || obj(it)?.safeString("type") == expected) && validElement(it, depth + 1)
        } == true
        return when (type) {
            "TextBlock", "TextRun" -> value.safeString("text") != null
            "Image" -> !value.safeString("url").isNullOrBlank()
            "RichTextBlock" -> array(value.get("inlines"))?.all { it.isJsonPrimitive && it.asJsonPrimitive.isString || validElement(it, depth + 1) } == true
            "ImageSet" -> elements("images", "Image")
            "Media" -> array(value.get("sources"))?.all { !obj(it)?.safeString("url").isNullOrBlank() } == true && (!value.has("captionSources") || array(value.get("captionSources"))?.all { !obj(it)?.safeString("url").isNullOrBlank() } == true)
            "FactSet" -> array(value.get("facts"))?.all { obj(it)?.safeString("title") != null && obj(it)?.safeString("value") != null } == true
            "Container", "Column", "TableCell" -> !value.has("items") || elements("items")
            "ColumnSet" -> elements("columns", "Column")
            "ActionSet" -> array(value.get("actions"))?.all { validAction(it, depth + 1) } == true
            "Table" -> elements("rows", "TableRow")
            "TableRow" -> elements("cells", "TableCell")
            "Input.ChoiceSet" -> !value.safeString("id").isNullOrBlank() && array(value.get("choices"))?.all { obj(it)?.safeString("title") != null && obj(it)?.safeString("value") != null } == true
            "Input.Toggle" -> !value.safeString("id").isNullOrBlank() && value.safeString("title") != null
            else -> !value.safeString("id").isNullOrBlank()
        }
    }
    fun actionIds(element: JsonElement): Set<String> = buildSet {
        fun visit(item: JsonElement, depth: Int) {
            if (depth > 32) return
            if (item.isJsonArray) { item.asJsonArray.forEach { visit(it, depth + 1) }; return }
            if (!item.isJsonObject) return
            val obj = item.asJsonObject
            if (obj.safeString("type") in setOf("Action.Submit", "Action.Execute")) add(obj.safeString("id")?.takeIf { it.isNotBlank() } ?: "adaptive_card_action")
            obj.entrySet().filter { it.key != "data" }.forEach { visit(it.value, depth + 1) }
        }
        visit(element, 0)
    }
    fun safeHttp(value: String?): String? = runCatching { URI(value?.trim() ?: return null) }.getOrNull()?.let { uri ->
        uri.toString().takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null }
    }
    private fun sanitize(element: JsonElement, depth: Int = 0): JsonElement {
        if (depth > 32) return com.google.gson.JsonNull.INSTANCE
        if (element.isJsonArray) return JsonArray().also { arr -> element.asJsonArray.forEach { arr.add(sanitize(it, depth + 1)) } }
        if (!element.isJsonObject) return element.deepCopy()
        val copy = JsonObject()
        element.asJsonObject.entrySet().forEach { (key, value) ->
            if (key == "data") copy.add(key, value.deepCopy())
            else if (key.lowercase() in resources && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                val url = value.asString
                val safe = safeHttp(url) ?: url.takeIf { it.matches(Regex("(?is)^data:image/(png|jpeg|gif|webp);base64,[a-z0-9+/=\\s]+$")) }
                if (safe != null) copy.addProperty(key, safe)
            } else copy.add(key, sanitize(value, depth + 1))
        }
        return copy
    }
    private fun js(value: JsonElement): String = value.toString().replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    private fun color(value: String?, fallback: String) = value?.takeIf { it.matches(Regex("(?i)^#[0-9a-f]{3,8}$")) } ?: fallback
    fun document(card: JsonObject, runtime: JsonObject, theme: ArtemisThemeConfig, used: Set<String>): String {
        val text = color(theme.text, "#1e293b")
        val primary = color(theme.primary, "#2563eb")
        val usedJson = JsonArray().apply { used.forEach { add(it) } }
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-artemis-adaptive'; style-src 'unsafe-inline'; font-src data:; img-src http: https: data:; media-src http: https:; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"><style>${runtime.safeString("css").orEmpty()}
html,body{margin:0;padding:0;overflow:hidden;background:transparent;color:$text;font-family:sans-serif}.ac-pushButton{background:$primary;border-color:$primary;color:white}</style></head><body><div id="card"></div><script nonce="artemis-adaptive">${runtime.safeString("js").orEmpty()}</script><script nonce="artemis-adaptive">${runtime.safeString("markdown").orEmpty()}</script><script nonce="artemis-adaptive">
(function(){var post=function(v){ArtemisAdaptive.postMessage(JSON.stringify(v));};var report=function(){requestAnimationFrame(function(){post({type:'height',height:document.documentElement.scrollHeight});});};try{var card=new AdaptiveCards.AdaptiveCard();card.onAnchorClicked=function(_e,a){post({type:'open',url:a.href});return true;};card.onExecuteAction=function(a){if(a instanceof AdaptiveCards.OpenUrlAction){post({type:'open',url:a.url});return;}if(a instanceof AdaptiveCards.SubmitActionBase){post({type:'action',id:a.id||'adaptive_card_action',value:a.verb||'',data:a.data||{}});}report();};card.parse(${js(sanitize(card))});var used=new Set(${js(usedJson)});function lock(n){if(!n)return;if(typeof n.getActionCount==='function')for(var i=0;i<n.getActionCount();i++){var a=n.getActionAt(i);if(used.has(a.id||'adaptive_card_action'))a.isEnabled=false;if(a.card)lock(a.card);}if(typeof n.getItemCount==='function')for(var j=0;j<n.getItemCount();j++)lock(n.getItemAt(j));if(typeof n.getColumnCount==='function')for(var k=0;k<n.getColumnCount();k++)lock(n.getColumnAt(k));}lock(card);var el=card.render();if(!el)throw Error();document.getElementById('card').appendChild(el);if(window.ResizeObserver)new ResizeObserver(report).observe(document.getElementById('card'));document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a');if(a){e.preventDefault();e.stopPropagation();post({type:'open',url:a.href});}},true);report();setTimeout(report,200);setTimeout(report,1000);}catch(e){post({type:'error'});}})();</script></body></html>"""
    }
}

private fun JsonObject.safeString(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
