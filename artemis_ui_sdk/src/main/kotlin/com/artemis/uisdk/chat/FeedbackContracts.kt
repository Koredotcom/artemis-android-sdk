package com.artemis.uisdk.chat

import com.google.gson.JsonObject

/** Assistant feedback is independent of the transferred-agent survey lane. */
enum class FeedbackSubmissionStatus { READY, PENDING, SUBMITTED, FAILED, EXPIRED }

data class FeedbackDraft(
    val rating: Int? = null,
    val comments: String = "",
    val followUpRating: Int? = null,
    val followUpText: String = "",
)

data class FeedbackResponseState(
    val status: FeedbackSubmissionStatus = FeedbackSubmissionStatus.READY,
    val draft: FeedbackDraft = FeedbackDraft(),
    val errorCode: String? = null,
)

internal data class FeedbackTemplate(
    val type: String,
    val max: Int,
    val emoji: Boolean,
    val data: JsonObject,
) {
    val ratings: IntRange get() = when (type) { "thumbs" -> 0..1; "scale" -> 0..10; "star" -> 1..max; else -> IntRange.EMPTY }
    fun valid(rating: Int?) = if (type == "text") rating == 0 else rating != null && rating in ratings
    fun showComment(rating: Int?): Boolean {
        if (type == "text") return true
        return when (data.string("comment_visibility")) {
            "always" -> true
            "never" -> false
            "conditional" -> rating != null && data.array("comment_ratings")?.any { FeedbackContracts.integer(it) == rating } == true
            else -> when (type) {
                "thumbs" -> rating == 0
                "star" -> rating != null && FeedbackContracts.integer(data.get("comment_threshold"))?.let { it >= 1 && rating <= it.coerceAtMost(max) } == true
                else -> false
            }
        }
    }
    val followUpType: String? get() = data.obj("follow_up")?.string("answer_type")
    val followUpRatingType: String? get() = data.obj("follow_up")?.string("rating_type")
    val followUpRatings: IntRange get() = when (followUpRatingType) { "thumbs" -> 0..1; "star" -> 1..5; "scale" -> 0..10; else -> IntRange.EMPTY }
    fun followUpQuestion(rating: Int?): String? {
        if (!valid(rating)) return null
        val followUp = data.obj("follow_up") ?: return null
        if (!followUp.flag("enabled") || followUpType !in setOf("text", "rating")) return null
        if (followUpType == "rating" && followUpRatings.isEmpty()) return null
        return followUp.obj("questions")?.string(rating.toString())?.takeIf(String::isNotBlank)
    }
    fun normalized(draft: FeedbackDraft): FeedbackDraft {
        val score = if (type == "text") 0 else draft.rating
        val question = followUpQuestion(score)
        return draft.copy(rating = score,
            comments = if (showComment(score)) draft.comments.take(5000) else "",
            followUpRating = draft.followUpRating?.takeIf { question != null && followUpType == "rating" && it in followUpRatings },
            followUpText = if (question != null && followUpType == "text") draft.followUpText.take(5000) else "")
    }
}

internal object FeedbackContracts {
    fun integer(value: com.google.gson.JsonElement?): Int? = runCatching {
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite() && it.asDouble == it.asInt.toDouble() }?.asInt
    }.getOrNull()
    fun parse(data: JsonObject): FeedbackTemplate? {
        val type = data.string("type").let { if (it == "stars") "star" else it }
        if (type !in setOf("thumbs", "star", "scale", "text")) return null
        val max = if (type == "star") (integer(data.get("max")) ?: 5).coerceIn(1, 5) else 10
        return FeedbackTemplate(type!!, max, type == "star" && data.string("rating_display") == "emoji", data)
    }
    fun renderId(message: ChatMessage, data: JsonObject): String? =
        (data.string("actionRenderId", "renderId") ?: message.actions?.string("renderId"))?.takeIf(String::isNotBlank)
    fun payload(message: ChatMessage, renderId: String?): JsonObject? {
        val legacy = message.richContent?.obj("feedback")
        if (legacy != null && FeedbackContracts.renderId(message, legacy) == renderId) return legacy
        return message.renderables?.firstNotNullOfOrNull { entry ->
            val item = entry.takeIf { it.isJsonObject }?.asJsonObject
            val data = item?.takeIf { it.string("name") == "feedback" }?.obj("payload")?.deepCopy() ?: return@firstNotNullOfOrNull null
            item.string("actionRenderId", "renderId")?.let { if (!data.has("actionRenderId") && !data.has("renderId")) data.addProperty("actionRenderId", it) }
            data.takeIf { FeedbackContracts.renderId(message, it) == renderId }
        }
    }
    fun key(message: ChatMessage, renderId: String?) = "${message.serverMessageId ?: message.messageId}\u0000${renderId.orEmpty()}"
}

/** History can rename a local ID to its server ID; retain answers only for the same template. */
internal fun retainFeedbackResponses(previous: ChatMessage?, message: ChatMessage): ChatMessage {
    if (previous == null || previous.feedbackResponses.isEmpty()) return message
    val retained = previous.feedbackResponses.filter { (key, _) ->
        val renderId = key.takeIf(String::isNotBlank)
        FeedbackContracts.payload(previous, renderId) == FeedbackContracts.payload(message, renderId)
    }
    return message.copy(feedbackResponses = retained)
}
