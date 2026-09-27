package com.dinh.javis.ai.capabilities

import android.graphics.Bitmap
import android.graphics.RectF
import com.dinh.javis.vision.OcrBlock

data class ChatMessage(
    val role: String, // system, user, assistant
    val content: String
)

data class ModelOptions(
    val temperature: Double = 0.7,
    val maxTokens: Int = 300,
    val stream: Boolean = false
)

data class DetectedElement(
    val label: String,
    val x: Float,
    val y: Float,
    val bounds: RectF? = null
)

data class VisionAnalysisResult(
    val description: String,
    val detectedElements: List<DetectedElement> = emptyList(),
    val rawResponse: String = ""
)

data class ActionParams(
    val x: Float = 0f,
    val y: Float = 0f,
    val text: String = "",
    val direction: String = "DOWN", // UP, DOWN, LEFT, RIGHT
    val durationMs: Long = 300
)

data class ActionPlanResult(
    val thought: String,
    val action: String, // CLICK, SCROLL, TYPE, WAIT, TERMINATE
    val params: ActionParams = ActionParams(),
    val isGoalComplete: Boolean = false,
    val rawJson: String = ""
)

data class ScreenObservation(
    val nodeHierarchyText: String? = null,
    val ocrText: String? = null,
    val screenshotWidth: Int = 0,
    val screenshotHeight: Int = 0,
    val currentPackage: String? = null,
    val ocrBlocks: List<OcrBlock> = emptyList()
)

data class ActionSummary(
    val stepIndex: Int,
    val action: String,
    val details: String,
    val success: Boolean
)

interface ChatCapability {
    suspend fun chat(messages: List<ChatMessage>, options: ModelOptions = ModelOptions()): String
}

interface VisionCapability {
    suspend fun analyzeScreen(
        prompt: String,
        bitmap: Bitmap,
        nodeContext: String? = null,
        options: ModelOptions = ModelOptions()
    ): VisionAnalysisResult
}

interface PlanningCapability {
    suspend fun planNextAction(
        taskGoal: String,
        screenSummary: ScreenObservation,
        history: List<ActionSummary> = emptyList(),
        options: ModelOptions = ModelOptions()
    ): ActionPlanResult
}
