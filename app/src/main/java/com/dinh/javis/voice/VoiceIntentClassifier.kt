package com.dinh.javis.voice

import android.util.Log
import com.dinh.javis.utils.TextNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AI-assisted intent recovery for unrecognized voice transcripts.
 *
 * Scope is intentionally narrow: maps a transcript to only a small allowed
 * intent set used for ASR-error recovery (primarily scroll directions).
 * Never accepts free-form commands, coordinates, app targets, or any action
 * outside [KnownIntent]. Uncertain or error cases fall back to a Vietnamese
 * clarification prompt rather than guessing.
 *
 * The LLM call is injected via [llmCall] so tests can substitute without
 * constructing a real [OpenAiClient] (which requires a Context / PreferenceManager).
 */
class VoiceIntentClassifier(
    private val llmCall: (suspend (systemPrompt: String, transcript: String) -> String)?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    /**
     * Allowed intents the model may return. Kept to the minimum needed for
     * ASR-error recovery so the model can never invent arbitrary actions.
     */
    enum class KnownIntent {
        SCROLL_UP,
        SCROLL_DOWN,
        UNKNOWN
    }

    /** Recovery decision returned to caller: either a safe command or a clarification. */
    sealed class RecoveryDecision {
        data class ScrollUp(val rationale: String) : RecoveryDecision()
        data class ScrollDown(val rationale: String) : RecoveryDecision()
        data class Clarify(val messageVi: String) : RecoveryDecision()
    }

    val minConfidence: Double = 0.82

    /**
     * Attempt AI recovery for a transcript that the deterministic parser
     * classified as [Command.Unknown]. Returns [RecoveryDecision.Clarify]
     * when AI is unavailable, output is malformed, confidence is low, or
     * the intent is unsupported — never executes a guess.
     */
    suspend fun recover(transcript: String): RecoveryDecision = withContext(Dispatchers.IO) {
        val normalized = TextNormalizer.removeAccents(TextNormalizer.stripFillerWords(transcript.trim()))

        if (normalized.isBlank() || llmCall == null) {
            return@withContext RecoveryDecision.Clarify(
                "Tôi chưa hiểu câu lệnh của bạn. Bạn vui lòng nói rõ hơn hoặc ghi \"lướt lên / lướt xuống\" không?"
            )
        }

        val systemPrompt = buildString {
            append("Bạn là trợ lý phân loại intent giọng nói cho JAVIS (trợ năng Android). ")
            append("Phân loại câu nói của người dùng thành ĐÚNG MỘT trong các intent sau, kèm điểm tự tin:")
            append("\n  - SCROLL_UP: người dùng muốn lướt/xem nội dung tiếp theo (lướt lên, swipe up, next video)")
            append("\n  - SCROLL_DOWN: người dùng muốn quay lại/xem nội dung trước (lướt xuống, swipe down, previous video)")
            append("\n  - UNKNOWN: không rõ ràng")
            append("\nTrả về JSON nghiêm ngặt, không có markdown, nằm giữa dấu ngoặc kẹp {{}}: ")
            append("{\"intent\": \"SCROLL_UP\", \"confidence\": 0.95, \"rationale\": \"...\"}")
            append("\nChỉ chọn intent có điểm tự tin >=")
            append(minConfidence)
            append(". Nếu không tự tin, dùng UNKNOWN.")
        }

        try {
            val rawResponse = llmCall?.invoke(systemPrompt, normalized)
                ?: return@withContext RecoveryDecision.Clarify(
                    "Tôi chưa hiểu câu lệnh của bạn. Bạn vui lòng nói rõ hơn hoặc ghi \"lướt lên / lướt xuống\" không?"
                )
            val json = extractJson(rawResponse)
            if (json != null) {
                val intentStr = json.optString("intent", "UNKNOWN")
                val confidence = json.optDouble("confidence", 0.0)
                val rationale = json.optString("rationale", "")

                when (KnownIntent.entries.find { it.name == intentStr } ?: KnownIntent.UNKNOWN) {
                    KnownIntent.SCROLL_UP -> {
                        if (confidence >= minConfidence) {
                            return@withContext RecoveryDecision.ScrollUp(rationale)
                        }
                    }
                    KnownIntent.SCROLL_DOWN -> {
                        if (confidence >= minConfidence) {
                            return@withContext RecoveryDecision.ScrollDown(rationale)
                        }
                    }
                    KnownIntent.UNKNOWN -> {
                        // falls through to clarification
                    }
                }
            }
            return@withContext RecoveryDecision.Clarify(
                "Tôi chưa chắc hiểu đúng. Bạn vui lòng nói \"lướt lên\" hoặc \"lướt xuống\" để JAVIS cuộn trang nhé."
            )
        } catch (e: Exception) {
            Log.w(TAG, "Recovery failed: ${e.message}")
            return@withContext RecoveryDecision.Clarify(
                "Có lỗi kết nối tới AI. Bạn vui lòng nói lại \"lướt lên\" hoặc \"lướt xuống\" nhé."
            )
        }
    }

    private fun extractJson(response: String): JSONObject? = try {
        val start = response.indexOf('{')
        val end = response.indexOf('}', start)
        if (start >= 0 && end >= start) {
            JSONObject(response.substring(start, end + 1))
        } else null
    } catch (e: Exception) {
        Log.w(TAG, "JSON parse failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "VoiceIntentClassifier"
    }
}
