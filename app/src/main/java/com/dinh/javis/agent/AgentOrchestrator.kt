package com.dinh.javis.agent

import android.content.Context
import android.util.Log
import com.dinh.javis.ai.ModelRouter
import com.dinh.javis.ai.capabilities.ActionPlanResult
import com.dinh.javis.ai.capabilities.ActionSummary
import com.dinh.javis.data.ActionLog
import com.dinh.javis.data.AppDatabase
import com.dinh.javis.data.PreferenceManager
import com.dinh.javis.data.TaskRun
import com.dinh.javis.service.JavisAccessibilityService
import com.dinh.javis.vision.ScreenObservationEngine
import kotlinx.coroutines.*
import java.util.UUID

interface AgentCallback {
    fun onStepStarted(stepIndex: Int, maxSteps: Int)
    fun onThought(thought: String)
    fun onActionExecuted(action: String, details: String)
    fun onConfirmationRequired(question: String, onUserResponse: (Boolean) -> Unit)
    fun onCompleted(success: Boolean, message: String)
}

/**
 * Điều phối viên vòng lặp ReAct Behavior Agent (Reasoning + Acting)
 * - Tự động quan sát màn hình qua 3 tầng (Accessibility -> OCR -> VLM)
 * - Lập kế hoạch bước đi tiếp theo bằng AI Model đã chọn (BYOK)
 * - Thực thi cử chỉ an toàn và đối chiếu kết quả
 */
class AgentOrchestrator(private val context: Context) {

    private val preferenceManager = PreferenceManager(context)
    private val policyGuard = PolicyGuard(context)
    private val modelRouter = ModelRouter.getInstance(context)
    private val observationEngine = ScreenObservationEngine(context)
    private val behaviorAggregator = BehaviorAggregator(context)
    private val database = AppDatabase.getDatabase(context)

    private var executionJob: Job? = null
    var isRunning: Boolean = false
        private set

    fun cancelActiveGoal() {
        executionJob?.cancel()
        isRunning = false
    }

    fun executeGoal(
        goal: String,
        callback: AgentCallback? = null
    ) {
        if (isRunning) {
            callback?.onCompleted(false, "Đang có một tác vụ khác đang chạy. Vui lòng dừng tác vụ trước.")
            return
        }

        isRunning = true
        val maxSteps = preferenceManager.agentMaxSteps.coerceIn(1, 15)
        val runId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        executionJob = CoroutineScope(Dispatchers.Main).launch {
            val history = mutableListOf<ActionSummary>()
            var isSuccess = false
            var finishMessage = "Đã hoàn thành tác vụ."

            try {
                // 1. Kiểm tra Dịch vụ Trợ năng
                val service = JavisAccessibilityService.instance
                if (service == null) {
                    finishMessage = "Dịch vụ Trợ năng JAVIS chưa được bật. Vui lòng cấp quyền trong Cài đặt."
                    callback?.onCompleted(false, finishMessage)
                    return@launch
                }

                // 2. Lưu TaskRun ban đầu
                withContext(Dispatchers.IO) {
                    policyGuard.initDefaultRulesIfEmpty()
                    database.taskRunDao().insertRun(
                        TaskRun(
                            runId = runId,
                            profileId = preferenceManager.activeAiProfileId,
                            taskGoal = goal,
                            startTime = startTime,
                            endTime = 0L,
                            status = "RUNNING",
                            stepCount = 0
                        )
                    )
                }

                var step = 1
                while (step <= maxSteps && isActive) {
                    callback?.onStepStarted(step, maxSteps)

                    // Quan sát màn hình (Tầng 1 + Tầng 2 OCR + Ảnh nếu bật Vision)
                    val observationResult = observationEngine.observeScreen(
                        captureVisual = preferenceManager.isVisionEnabled
                    )
                    val obs = observationResult.observation

                    // Kiểm tra Guardrails bảo mật
                    val policyCheck = policyGuard.checkScreenAndPackage(
                        obs.currentPackage,
                        obs.ocrText ?: obs.nodeHierarchyText
                    )

                    if (policyCheck.decision == PolicyDecision.DENY) {
                        finishMessage = policyCheck.reason
                        callback?.onCompleted(false, finishMessage)
                        break
                    }

                    if (policyCheck.decision == PolicyDecision.REQUIRE_CONFIRM) {
                        var isApproved = false
                        suspendCancellableCoroutine { cont ->
                            callback?.onConfirmationRequired(policyCheck.reason) { userApproved ->
                                isApproved = userApproved
                                if (cont.isActive) cont.resume(Unit) {}
                            }
                        }
                        if (!isApproved) {
                            finishMessage = "Tác vụ đã bị hủy theo yêu cầu người dùng."
                            callback?.onCompleted(false, finishMessage)
                            break
                        }
                    }

                    // Lập kế hoạch hành động tiếp theo
                    val planResult: ActionPlanResult = modelRouter.planNextAction(
                        taskGoal = goal,
                        screenSummary = obs,
                        history = history
                    )

                    callback?.onThought(planResult.thought)

                    if (planResult.action == "TERMINATE" || planResult.isGoalComplete) {
                        isSuccess = true
                        finishMessage = if (planResult.thought.isNotBlank()) planResult.thought else "Đã hoàn thành mục tiêu tác vụ."
                        break
                    }

                    // Thực thi hành động cử chỉ qua AccessibilityService
                    val actionSuccess = dispatchAction(service, planResult)
                    val actionDetails = formatActionDetails(planResult)
                    callback?.onActionExecuted(planResult.action, actionDetails)

                    // Ghi audit log
                    withContext(Dispatchers.IO) {
                        database.actionLogDao().insertLog(
                            ActionLog(
                                runId = runId,
                                actionType = planResult.action,
                                targetPackage = obs.currentPackage ?: "",
                                sanitizedDetails = actionDetails,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }

                    history.add(
                        ActionSummary(
                            stepIndex = step,
                            action = planResult.action,
                            details = actionDetails,
                            success = actionSuccess
                        )
                    )

                    // Chờ màn hình cập nhật sau cử chỉ
                    delay(1200)
                    step++
                }

                if (step > maxSteps && !isSuccess) {
                    finishMessage = "Đã đạt giới hạn tối đa ($maxSteps bước) mà chưa xong tác vụ."
                }

                callback?.onCompleted(isSuccess, finishMessage)

            } catch (e: CancellationException) {
                finishMessage = "Tác vụ đã bị người dùng hủy."
                callback?.onCompleted(false, finishMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi xảy ra trong vòng lặp Agent", e)
                finishMessage = "Đã xảy ra lỗi: ${e.localizedMessage ?: "Không xác định"}"
                callback?.onCompleted(false, finishMessage)
            } finally {
                isRunning = false
                val endTime = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    database.taskRunDao().insertRun(
                        TaskRun(
                            runId = runId,
                            profileId = preferenceManager.activeAiProfileId,
                            taskGoal = goal,
                            startTime = startTime,
                            endTime = endTime,
                            status = if (isSuccess) TaskRun.STATUS_SUCCESS else TaskRun.STATUS_FAILED,
                            stepCount = history.size,
                            failureReason = if (isSuccess) null else finishMessage
                        )
                    )
                    behaviorAggregator.recordTaskExecution(goal, isSuccess)
                }
            }
        }
    }

    private fun dispatchAction(
        service: JavisAccessibilityService,
        plan: ActionPlanResult
    ): Boolean {
        return when (plan.action) {
            "CLICK" -> {
                val x = plan.params.x
                val y = plan.params.y
                if (x > 0 && y > 0) {
                    service.tapAt(x, y)
                } else if (plan.params.text.isNotBlank()) {
                    service.clickNodeByText(plan.params.text)
                } else {
                    false
                }
            }
            "SCROLL" -> {
                when (plan.params.direction.uppercase()) {
                    "UP" -> service.scrollBackward()
                    "DOWN" -> service.scrollForward()
                    else -> service.scrollForward()
                }
            }
            "TYPE" -> {
                service.typeText(plan.params.text)
            }
            "WAIT" -> {
                true
            }
            else -> false
        }
    }

    private fun formatActionDetails(plan: ActionPlanResult): String {
        return when (plan.action) {
            "CLICK" -> "Chạm tọa độ (${plan.params.x.toInt()}, ${plan.params.y.toInt()})"
            "SCROLL" -> "Cuộn trang ${if (plan.params.direction.equals("UP", true)) "lên trên" else "xuống dưới"}"
            "TYPE" -> "Nhập: \"${plan.params.text}\""
            "WAIT" -> "Chờ tải giao diện"
            else -> plan.action
        }
    }

    companion object {
        private const val TAG = "AgentOrchestrator"

        @Volatile
        private var INSTANCE: AgentOrchestrator? = null

        fun getInstance(context: Context): AgentOrchestrator {
            return INSTANCE ?: synchronized(this) {
                val instance = AgentOrchestrator(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
