package com.chatgemma.app.domain.usecase.context

import com.chatgemma.app.ai.GemmaInferenceEngine
import com.chatgemma.app.ai.PromptBuilder
import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.domain.model.InferenceParams
import javax.inject.Inject

/**
 * Compresses the oldest part of a session's conversation into a single summary
 * message so the prompt fits back inside the context window. The most recent
 * messages are always kept verbatim so the model retains short-term detail.
 */
class CompressContextUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val gemmaEngine: GemmaInferenceEngine
) {
    /**
     * @param contextSize the engine's context window; bounds how much history is
     * summarized per pass so the summarization prompt itself fits in the window.
     * @return true if any messages were compressed.
     */
    suspend operator fun invoke(
        sessionId: String,
        branchId: String,
        contextSize: Int,
        keepRecentMessages: Int = KEEP_RECENT_MESSAGES
    ): Boolean {
        val messages = chatRepository.getMessagesList(sessionId, branchId)
        val older = messages.dropLast(keepRecentMessages)
        if (older.size < MIN_MESSAGES_TO_COMPRESS) return false

        // Bound the chunk so the summarization prompt (chunk + instructions +
        // summary output) fits in the context window
        val maxChunkTokens = (contextSize * CHUNK_BUDGET_FRACTION).toInt()
        var chunkTokens = 0
        val chunk = older.takeWhile { msg ->
            chunkTokens += msg.tokenCount.coerceAtLeast(1)
            chunkTokens <= maxChunkTokens
        }.ifEmpty { older.take(1) }
        if (chunk.size < MIN_MESSAGES_TO_COMPRESS) return false

        val prompt = PromptBuilder.buildCompressPrompt(chunk)
        val compressed = gemmaEngine.generateFull(
            prompt = prompt,
            params = InferenceParams(temperature = 0.3f, maxTokens = SUMMARY_MAX_TOKENS)
        ).let(::stripControlTokens)
        if (compressed.isEmpty() || compressed.startsWith("[Error:")) return false

        // Replace the first chunk message with the summary, delete the rest
        val summaryText = "[Summary of earlier conversation]\n$compressed"
        val tokenCount = gemmaEngine.countTokens(summaryText)
        chatRepository.updateMessageContent(chunk.first().id, summaryText, tokenCount)
        chunk.drop(1).forEach { chatRepository.deleteMessage(it.id) }
        return true
    }

    private fun stripControlTokens(text: String): String =
        text.replace("<end_of_turn>", "")
            .replace("<start_of_turn>", "")
            .replace("<eos>", "")
            .replace("<bos>", "")
            .trim()

    private companion object {
        const val KEEP_RECENT_MESSAGES = 4
        const val MIN_MESSAGES_TO_COMPRESS = 2
        const val SUMMARY_MAX_TOKENS = 512
        const val CHUNK_BUDGET_FRACTION = 0.5f
    }
}
