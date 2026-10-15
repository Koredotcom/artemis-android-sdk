package com.artemis.uisdk.chat

import java.net.URI
import com.google.gson.JsonObject

/** Presentation decisions kept independent of Android views for contract regression tests. */
internal object TemplateContracts {
    fun actionValue(button: JsonObject): String? {
        val value = button.get("value") ?: button.get("payload")
        return if (value == null) button.string("id") else if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()
    }
    fun actionUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> value.trim().takeIf { !uri.host.isNullOrBlank() && uri.userInfo == null }
            "mailto", "tel" -> value.trim().takeIf { !uri.schemeSpecificPart.isNullOrBlank() }
            else -> null
        }
    }
    fun pieSweeps(values: List<Double>): List<Float> {
        val positive = values.map { if (it.isFinite() && it > 0) it else 0.0 }
        val max = positive.maxOrNull() ?: return emptyList()
        if (max == 0.0) return positive.map { 0f }
        val scaled = positive.map { it / max }
        val total = scaled.sum()
        return scaled.map { (it / total * 360).toFloat() }
    }
}
