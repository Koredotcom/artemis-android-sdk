package com.artemis.uisdk.chat

import java.net.URI
import com.google.gson.JsonObject

/** Presentation decisions kept independent of Android views for contract regression tests. */
internal object TemplateContracts {
    fun actionValue(button: JsonObject): String? {
        val value = button.get("value") ?: button.get("payload")
        return if (value == null || value.isJsonNull) button.string("id") else if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()
    }
    fun actionUrl(value: String?, base: String? = null): String? {
        if (value.isNullOrBlank()) return null
        val uri = runCatching {
            val parsed = URI(value.trim())
            if (!parsed.isAbsolute && base != null) URI(base).resolve(parsed) else parsed
        }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> uri.toString().takeIf { !uri.host.isNullOrBlank() && uri.userInfo == null }
            "mailto", "tel" -> uri.toString().takeIf { !uri.schemeSpecificPart.isNullOrBlank() }
            else -> null
        }
    }
    fun hasPreferredText(message: ChatMessage): Boolean {
        fun text(value: com.google.gson.JsonElement?) = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.isNotBlank() == true
        return listOf("markdown", "html").any { name -> text(message.richContent?.get(name)) || message.renderables?.any {
            it.isJsonObject && it.asJsonObject.string("name") == name && text(it.asJsonObject.get("payload"))
        } == true }
    }
    fun date(value: String?): java.util.Date? {
        if (value == null || !value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return null
        return runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { isLenient = false }.parse(value) }.getOrNull()
    }
    fun inputError(field: JsonObject, value: String): String? {
        if (field.flag("required") && value.isBlank()) return "required"
        if (value.isEmpty()) return null
        val valid = when (field.string("input_type") ?: field.string("type")) {
            "email" -> value.matches(Regex("[^\\s@]+@[^\\s@]+"))
            "number" -> value.toDoubleOrNull()?.isFinite() == true
            "date" -> date(value) != null
            "time" -> value.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\\.[0-9]{1,3})?)?"))
            else -> true
        }
        return if (valid) null else "invalid"
    }
    fun draftKey(message: ChatMessage, renderId: String?, scope: String) = "${message.serverMessageId ?: message.messageId}\u0000${renderId.orEmpty()}\u0000$scope"
    fun pieSweeps(values: List<Double>): List<Float> {
        val positive = values.map { if (it.isFinite() && it > 0) it else 0.0 }
        val max = positive.maxOrNull() ?: return emptyList()
        if (max == 0.0) return positive.map { 0f }
        val scaled = positive.map { it / max }
        val total = scaled.sum()
        return scaled.map { (it / total * 360).toFloat() }
    }
}
