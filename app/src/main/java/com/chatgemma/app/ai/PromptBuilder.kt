package com.chatgemma.app.ai

import com.chatgemma.app.domain.model.Message

object PromptBuilder {
    // Gemma instruction-tuned format
    private const val BOS = "<bos>"
    private const val START_OF_TURN = "<start_of_turn>"
    private const val END_OF_TURN = "<end_of_turn>"

    /**
     * Builds the standard chat prompt from a list of messages.
     * Leaves the final model turn open so the model continues.
     */
    fun buildChatPrompt(
        history: List<Message>,
        systemPrompt: String? = null
    ): String = buildString {
        append(BOS)
        if (systemPrompt != null) {
            append("${START_OF_TURN}user\n[System: $systemPrompt]${END_OF_TURN}\n")
            append("${START_OF_TURN}model\nUnderstood.${END_OF_TURN}\n")
        }
        history.forEach { msg ->
            val role = if (msg.role == "user") "user" else "model"
            val text = buildString {
                if (msg.mediaType == "image") append("[Image attached] ")
                if (msg.mediaType == "video") append("[Video attached] ")
                if (msg.mediaType == "audio") append("[Voice message] ")
                append(msg.textContent ?: "")
            }
            append("${START_OF_TURN}$role\n$text${END_OF_TURN}\n")
        }
        // Open the model's next turn
        append("${START_OF_TURN}model\n")
    }

    /**
     * Prompt for compressing a topic's messages into a shorter version.
     */
    fun buildCompressPrompt(messages: List<Message>): String {
        val content = messages.joinToString("\n") { "${it.role}: ${it.textContent ?: ""}" }
        return buildChatPrompt(
            listOf(
                Message(
                    id = "", sessionId = "", branchId = "",
                    role = "user", createdAt = 0,
                    textContent = """Compress the following conversation segment to be shorter while preserving all key information, decisions, and conclusions. Output only the compressed version with no preamble:

$content"""
                )
            )
        )
    }

    /**
     * Prompt for producing a 2-3 sentence summary of a topic.
     */
    fun buildSummarizePrompt(messages: List<Message>): String {
        val content = messages.joinToString("\n") { "${it.role}: ${it.textContent ?: ""}" }
        return buildChatPrompt(
            listOf(
                Message(
                    id = "", sessionId = "", branchId = "",
                    role = "user", createdAt = 0,
                    textContent = """Summarize the following conversation segment in 2-3 concise sentences. Capture the main topic and key outcomes only. Output only the summary:

$content"""
                )
            )
        )
    }

    /**
     * Prompt for auto-tagging the current conversation topic.
     */
    fun buildAutoTagPrompt(messages: List<Message>): String {
        val content = messages.takeLast(10)
            .joinToString("\n") { "${it.role}: ${it.textContent?.take(200) ?: ""}" }
        return buildChatPrompt(
            listOf(
                Message(
                    id = "", sessionId = "", branchId = "",
                    role = "user", createdAt = 0,
                    textContent = """Based on the conversation below, identify the primary topic in 2-4 words.
Respond with ONLY the topic label (no punctuation, no explanation):

$content"""
                )
            )
        )
    }
}
