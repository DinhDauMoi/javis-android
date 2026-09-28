package com.dinh.javis.agent

import android.content.Context
import android.util.Log
import com.dinh.javis.ai.ModelRouter
import com.dinh.javis.ai.capabilities.ActionPlanResult
import com.dinh.javis.ai.capabilities.ActionSummary
import com.dinh.javis.ai.capabilities.ScreenObservation
import com.dinh.javis.data.ActionLog
import com.dinh.javis.data.AppDatabase
import com.dinh.javis.data.PreferenceManager
import com.dinh.javis.data.TaskRun
import com.dinh.javis.service.JavisAccessibilityService
import com.dinh.javis.vision.ScreenObservationEngine
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Behavior Agent orchestrator with safe execution lifecycle (M1, M2, M3).
 *
 * Key safety properties:
 * - Single exclusive run owner using AtomicReference run token (BA-04).
 * - Old cleanup cannot clear a newer run's state (BA-04).
 * - Real user approval via ApprovalManager — model cannot self-approve (BA-01).
 * - Typed outcomes — planner TERMINATE never auto-maps to SUCCESS (BA-02).
 * - Policy checked BEFORE observation and BEFORE dispatch (BA-03).
 * - Actions validated by ActionValidator before dispatch (BA-02, BA-03).
 * - Task verification on fresh observation before recording SUCCESS (BA-07).
 * - Stuck detection prevents unbounded no-progress loops (BA-05).
 * - Cancellation check before and after async results (BA-04).
 * - Bitmap owned by observation loop; released after use (BA-06).
 * - Finalization uses NonCancellable scope to safely persist outcome (BA-04).
 *
 * Compatibility:
 * - Legacy AgentCallback shim preserved for CommandExecutor until migration.
 * - openWakeWord, beep, custom commands, chat, Tile, Bubble all unaffected.
 */
class AgentOrchestrator(private val context: Context) {

    private val TAG = "AgentOrchestrator"

    private val preferenceManager = PreferenceManager(context)
    private val policyGuard = PolicyGuard(context)
    private val modelRouter = ModelRouter.getInstance(context)
    private val observationEngine = ScreenObservationEngine(context)
    private val behaviorAggregator = BehaviorAggregator(context)
    private val approvalManager = ApprovalManager()
    private val taskVerifier = TaskVerifier()
    private val database = AppDatabase.getDatabase(context)

    // ── Exclusive run ownership (BA-04) ──────────────────────────────────
    // Holds the currently active RunContext; null means idle.
    private val activeRunRef = AtomicReference<RunContext?>(null)
    private var executionJob: Job? = null

    val isRunning: Boolean
        get() = activeRunRef.get() != null

    @Volatile
    var pendingShoppingRequest: com.dinh.javis.agent.shopping.ProductSearchRequest? = null

    /**
     * Cancels the currently active goal, if any.
     * The running coroutine will detect cancellation and finalize with CANCELLED outcome.
     */
    fun cancelActiveGoal() {
        val current = activeRunRef.get() ?: return
        Log.i(TAG, "Cancelling run ${current.runId}")
        approvalManager.invalidatePending()
        executionJob?.cancel()
        // activeRunRef is cleared in finally block of the run coroutine
    }

    /**
     * Executes a goal using the full safe execution loop.
     *
     * @param request Scoped task request with budget and optional completion criteria.
     * @param callback Progress and completion callbacks.
     * @param legacyCallback Backward-compatible callback shim (used by CommandExecutor).
     */
    fun executeGoal(
        request: TaskRequest,
        callback: TaskProgressCallback? = null,
        legacyCallback: AgentCallback? = null
    ) {
        val runContext = RunContext(
            request = request,
            profileId = preferenceManager.activeAiProfileId
        )

        // BA-04: atomic ownership — reject if another run is active
        if (!activeRunRef.compareAndSet(null, runContext)) {
            val msg = "Đang có một tác vụ khác đang chạy. Vui lòng dừng tác vụ trước."
            callback?.onCompleted(TaskOutcome.FAILED, msg)
            legacyCallback?.onCompleted(false, msg)
            return
        }

        val budget = request.budget
        var outcome = TaskOutcome.INTERRUPTED
        var finishMessage = "Tác vụ bị gián đoạn."
        var verifiedActionCount = 0
        var retryCount = 0

        executionJob = CoroutineScope(Dispatchers.Main).launch {
            val history = mutableListOf<ActionSummary>()
            val recentObservations = mutableListOf<ScreenObservation>()
            val startTime = System.currentTimeMillis()
            var modelCallCount = 0

            try {
                // ── Preflight checks ─────────────────────────────────────
                val service = JavisAccessibilityService.instance
                if (service == null) {
                    outcome = TaskOutcome.FAILED
                    finishMessage = "Dịch vụ Trợ năng JAVIS chưa được bật. Vui lòng cấp quyền trong Cài đặt."
                    callback?.onCompleted(outcome, finishMessage)
                    legacyCallback?.onCompleted(false, finishMessage)
                    return@launch
                }

                // Save initial RUNNING record
                withContext(Dispatchers.IO) {
                    policyGuard.initDefaultRulesIfEmpty()
                    database.taskRunDao().insertRun(
                        TaskRun(
                            runId = runContext.runId,
                            profileId = runContext.profileId,
                            taskGoal = request.goal.take(TaskRun.MAX_STORED_GOAL_LENGTH),
                            taskCategory = request.taskCategory,
                            startTime = startTime,
                            endTime = 0L,
                            status = TaskRun.STATUS_RUNNING,
                            stepCount = 0
                        )
                    )
                }

                var step = 1
                while (step <= budget.effectiveActionSteps && isActive) {
                    // BA-05: deadline check
                    if (runContext.isDeadlineExceeded()) {
                        outcome = TaskOutcome.BUDGET_EXHAUSTED
                        finishMessage = "Đã vượt quá thời gian tối đa (${budget.deadlineMs / 1000}s) mà chưa hoàn thành tác vụ."
                        break
                    }

                    // BA-05: model call budget
                    if (modelCallCount >= budget.maxModelCalls) {
                        outcome = TaskOutcome.BUDGET_EXHAUSTED
                        finishMessage = "Đã đạt giới hạn tối đa lệnh gọi AI (${ budget.maxModelCalls})."
                        break
                    }

                    callback?.onStepStarted(step, budget.maxSteps)
                    legacyCallback?.onStepStarted(step, budget.maxSteps)

                    // ── BA-03: Pre-observation package check ──────────────
                    val currentPkg = service.getActivePackageName()
                    val preCheck = policyGuard.checkPackagePreObservation(currentPkg)
                    if (preCheck.decision == PolicyDecision.DENY) {
                        outcome = TaskOutcome.BLOCKED
                        finishMessage = preCheck.reason
                        break
                    }

                    // ── BA-06: Observe screen ─────────────────────────────
                    val obsResult = observationEngine.observeScreen(
                        captureVisual = preferenceManager.isVisionEnabled
                    )
                    val obs = obsResult.observation
                    val bitmap = obsResult.bitmap
                    recentObservations.add(obs)
                    if (recentObservations.size > 5) recentObservations.removeAt(0)

                    // ── BA-03: Full policy check post-observation ──────────
                    val policyCheck = policyGuard.checkScreenAndPackage(
                        obs.currentPackage,
                        obs.ocrText ?: obs.nodeHierarchyText
                    )

                    if (policyCheck.decision == PolicyDecision.DENY) {
                        outcome = TaskOutcome.BLOCKED
                        finishMessage = policyCheck.reason
                        // Bitmap must be released before breaking
                        bitmap?.recycle()
                        break
                    }

                    if (policyCheck.decision == PolicyDecision.REQUIRE_CONFIRM) {
                        val approvalReq = ApprovalManager.createRequest(
                            context = runContext,
                            action = ActionProposal(
                                runId = runContext.runId,
                                stepIndex = step,
                                action = "WAIT",
                                plannerThought = policyCheck.reason
                            ),
                            reasonVi = policyCheck.reason,
                            timeoutMs = 30_000L
                        )
                        val approved = approvalManager.requestApproval(runContext, approvalReq) { req, onResponse ->
                            callback?.onApprovalRequired(req, onResponse)
                            legacyCallback?.onConfirmationRequired(req.reasonVi, onResponse)
                        }
                        if (!approved) {
                            outcome = TaskOutcome.CANCELLED
                            finishMessage = "Tác vụ đã bị hủy theo yêu cầu người dùng."
                            bitmap?.recycle()
                            break
                        }
                    }

                    // ── BA-02: Plan next action ───────────────────────────
                    val planResult: ActionPlanResult = try {
                        modelCallCount++
                        modelRouter.planNextAction(
                            taskGoal = request.goal,
                            screenSummary = obs,
                            history = history
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Model planning error on step $step", e)
                        if (retryCount < budget.maxRetries) {
                            retryCount++
                            Log.w(TAG, "Transient planning error — retry $retryCount/${budget.maxRetries}")
                            bitmap?.recycle()
                            delay(1500L * retryCount)
                            continue
                        }
                        outcome = TaskOutcome.FAILED
                        finishMessage = "Lỗi kết nối AI sau ${budget.maxRetries} lần thử: ${e.localizedMessage ?: "Không xác định"}"
                        bitmap?.recycle()
                        break
                    }

                    // Release bitmap after planning (it was available for vision but not retained)
                    bitmap?.recycle()

                    // Check run ownership still valid after async model call
                    if (!isActive || activeRunRef.get()?.runId != runContext.runId) break

                    val statusSummary = ActionValidator.sanitizeThoughtForDisplay(planResult.thought)
                    callback?.onStatusUpdate(statusSummary)
                    legacyCallback?.onThought(statusSummary)

                    // ── BA-02: Separate completion from errors ─────────────
                    if (planResult.action == "TERMINATE") {
                        // TERMINATE is a proposal to stop, not automatic success.
                        // If we have completion criteria, verify them; otherwise needs input.
                        outcome = TaskOutcome.NEEDS_INPUT
                        finishMessage = "Mô hình AI đề xuất dừng lại. Bắt đầu xác nhận kết quả..."

                        // Attempt final verification
                        val freshObs = observationEngine.observeScreen(captureVisual = false).observation
                        val verified = taskVerifier.checkTaskCompletion(request, freshObs)
                        when (verified) {
                            true -> {
                                outcome = TaskOutcome.SUCCESS
                                finishMessage = if (planResult.thought.isNotBlank()) planResult.thought
                                    else "Đã xác nhận hoàn thành mục tiêu tác vụ."
                            }
                            false -> {
                                outcome = TaskOutcome.FAILED
                                finishMessage = "Tác vụ kết thúc nhưng kết quả chưa được xác nhận."
                            }
                            null -> {
                                // No criteria — request user verification
                                outcome = TaskOutcome.NEEDS_INPUT
                                finishMessage = "Mô hình AI báo đã xong. Vui lòng kiểm tra lại màn hình để xác nhận."
                            }
                        }
                        break
                    }

                    // ── BA-02: Build and validate the proposed action ─────
                    val proposal = ActionProposal(
                        runId = runContext.runId,
                        stepIndex = step,
                        action = planResult.action,
                        targetText = planResult.params.text,
                        x = planResult.params.x,
                        y = planResult.params.y,
                        direction = planResult.params.direction,
                        inputText = planResult.params.text,
                        plannerThought = planResult.thought
                    )

                    val validationResult = ActionValidator.validate(
                        proposal = proposal,
                        currentObservation = obs,
                        allowedPackages = request.allowedPackages,
                        screenWidth = obs.screenshotWidth,
                        screenHeight = obs.screenshotHeight
                    )

                    if (validationResult is ActionValidator.ValidationResult.Invalid) {
                        Log.w(TAG, "Action rejected by validator: ${validationResult.reasonVi}")
                        outcome = TaskOutcome.FAILED
                        finishMessage = "Hành động bị từ chối: ${validationResult.reasonVi}"
                        break
                    }

                    // ── BA-01: Check if approval is needed ────────────────
                    // Check if the current package requires confirmation
                    val dispatchPreCheck = policyGuard.checkActionDispatch(obs.currentPackage)
                    val needsApproval = dispatchPreCheck.decision == PolicyDecision.REQUIRE_CONFIRM

                    if (needsApproval) {
                        val approvalReq = ApprovalManager.createRequest(
                            context = runContext,
                            action = proposal,
                            reasonVi = "JAVIS sắp thực hiện: ${formatActionVi(proposal)}. Bạn có đồng ý không?",
                            timeoutMs = 30_000L
                        )
                        val approved = approvalManager.requestApproval(runContext, approvalReq) { req, onResponse ->
                            callback?.onApprovalRequired(req, onResponse)
                            legacyCallback?.onConfirmationRequired(req.reasonVi, onResponse)
                        }

                        if (!approved) {
                            outcome = TaskOutcome.CANCELLED
                            finishMessage = "Tác vụ đã bị hủy: người dùng không xác nhận hoặc hết thời gian."
                            break
                        }
                    }

                    // Recheck run ownership and cancellation before dispatch
                    if (!isActive || activeRunRef.get()?.runId != runContext.runId) break

                    // ── BA-03: Final package check immediately before dispatch ──
                    val dispatchPkg = service.getActivePackageName()
                    val finalPkgCheck = policyGuard.checkActionDispatch(dispatchPkg)
                    if (finalPkgCheck.decision == PolicyDecision.DENY) {
                        outcome = TaskOutcome.BLOCKED
                        finishMessage = finalPkgCheck.reason
                        break
                    }

                    // ── BA-07: Capture pre-action observation for postcondition ──
                    val preActionObs = obs

                    // ── Execute action ─────────────────────────────────────
                    var actionSuccess = dispatchAction(service, proposal)
                    val actionDetailsVi = formatActionVi(proposal)
                    callback?.onActionExecuted(proposal.action, actionDetailsVi)
                    legacyCallback?.onActionExecuted(proposal.action, actionDetailsVi)

                    // Write audit log
                    withContext(Dispatchers.IO) {
                        database.actionLogDao().insertLog(
                            ActionLog(
                                runId = runContext.runId,
                                actionType = proposal.action,
                                targetPackage = obs.currentPackage ?: "",
                                sanitizedDetails = actionDetailsVi,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }

                    // Wait for UI to settle after action
                    delay(1200)

                    // ── BA-07: Verify postcondition on fresh observation ──
                    if (proposal.action != "WAIT") {
                        val postObs = observationEngine.observeScreen(captureVisual = false).observation
                        val postconditionOk = taskVerifier.verifyActionPostcondition(proposal, preActionObs, postObs)
                        recentObservations.add(postObs)
                        if (!postconditionOk) {
                            Log.w(TAG, "Step $step: Postcondition failed for ${proposal.action} - screen unchanged")
                            actionSuccess = false
                        }
                    }

                    // ── BA-05: Stuck detection ─────────────────────────────
                    if (taskVerifier.isStuck(recentObservations)) {
                        outcome = TaskOutcome.FAILED
                        finishMessage = "Màn hình không thay đổi sau nhiều bước liên tiếp. JAVIS dừng lại để tránh lặp vô hạn."
                        break
                    }

                    if (actionSuccess) verifiedActionCount++

                    history.add(
                        ActionSummary(
                            stepIndex = step,
                            action = proposal.action,
                            details = actionDetailsVi,
                            success = actionSuccess
                        )
                    )

                    step++
                }

                // BA-05: Budget exhausted by step count
                if (outcome == TaskOutcome.INTERRUPTED && step > budget.effectiveActionSteps) {
                    outcome = TaskOutcome.BUDGET_EXHAUSTED
                    finishMessage = "Đã đạt giới hạn tối đa (${budget.maxSteps} bước) mà chưa hoàn thành tác vụ."
                }

                callback?.onCompleted(outcome, finishMessage)
                legacyCallback?.onCompleted(outcome == TaskOutcome.SUCCESS, finishMessage)

            } catch (e: CancellationException) {
                outcome = TaskOutcome.CANCELLED
                finishMessage = "Tác vụ đã bị người dùng hủy."
                callback?.onCompleted(outcome, finishMessage)
                legacyCallback?.onCompleted(false, finishMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in agent loop", e)
                outcome = TaskOutcome.FAILED
                finishMessage = "Đã xảy ra lỗi: ${e.localizedMessage ?: "Không xác định"}"
                callback?.onCompleted(outcome, finishMessage)
                legacyCallback?.onCompleted(false, finishMessage)
            } finally {
                val endTime = System.currentTimeMillis()
                val durationMs = endTime - runContext.startWallMs.coerceAtMost(startTime.toLong())

                // BA-04: Use NonCancellable scope for cleanup so finalization always completes
                withContext(Dispatchers.IO + NonCancellable) {
                    // Only finalize if we still own this run (not overwritten by a new run)
                    if (activeRunRef.get()?.runId == runContext.runId) {
                        val finalStatus = TaskRun.statusFromOutcome(outcome)
                        database.taskRunDao().insertRun(
                            TaskRun(
                                runId = runContext.runId,
                                profileId = runContext.profileId,
                                taskGoal = request.goal.take(TaskRun.MAX_STORED_GOAL_LENGTH),
                                taskCategory = request.taskCategory,
                                startTime = startTime,
                                endTime = endTime,
                                status = finalStatus,
                                stepCount = history.size,
                                verifiedActionCount = verifiedActionCount,
                                durationMs = durationMs,
                                failureReason = if (outcome != TaskOutcome.SUCCESS) finishMessage else null
                            )
                        )

                        // BA-08: only record analytics if consent given
                        if (preferenceManager.isBehaviorAnalyticsEnabled) {
                            behaviorAggregator.recordTaskMetric(
                                runId = runContext.runId,
                                taskCategory = request.taskCategory,
                                event = TaskMetricEvent(
                                    taskCategory = request.taskCategory,
                                    outcome = outcome,
                                    durationMs = durationMs,
                                    verifiedActionCount = verifiedActionCount,
                                    retryCount = retryCount,
                                    failureCategory = if (outcome == TaskOutcome.FAILED) categorizeFailure(finishMessage) else null
                                )
                            )
                        }

                        // BA-09: retention cleanup (runs after every task)
                        behaviorAggregator.runRetentionCleanup()

                        // Release run ownership only after full cleanup
                        activeRunRef.compareAndSet(runContext, null)
                    }
                }
            }
        }
    }

    /**
     * Backward-compatible overload accepting raw goal string and legacy AgentCallback.
     * Used by CommandExecutor until it migrates to TaskProgressCallback.
     */
    fun executeGoal(goal: String, callback: AgentCallback? = null) {
        executeGoal(
            request = TaskRequest(goal = goal, taskCategory = TaskRequest.CATEGORY_GENERAL),
            legacyCallback = callback
        )
    }

    /**
     * Executes a shopping search task via ShopeeShoppingSkill with orchestrator-managed
     * lifecycle, exclusive run ownership, cancellation support, and telemetry.
     */
    fun executeShopping(
        request: com.dinh.javis.agent.shopping.ProductSearchRequest,
        callback: TaskProgressCallback? = null,
        legacyCallback: AgentCallback? = null
    ) {
        val taskRequest = TaskRequest(
            goal = "Tìm mua ${request.query} trên Shopee",
            taskCategory = TaskRequest.CATEGORY_SHOPPING,
            allowedPackages = setOf("com.shopee.vn"),
            budget = TaskBudget(
                maxSteps = request.executionLimits.maxSearchResultPages * 4,
                deadlineMs = 120_000L,
                maxModelCalls = request.executionLimits.maxModelCalls.coerceAtLeast(5)
            )
        )
        val runContext = RunContext(
            request = taskRequest,
            profileId = preferenceManager.activeAiProfileId
        )

        if (!activeRunRef.compareAndSet(null, runContext)) {
            val msg = "Đang có một tác vụ khác đang chạy. Vui lòng dừng tác vụ trước."
            callback?.onCompleted(TaskOutcome.FAILED, msg)
            legacyCallback?.onCompleted(false, msg)
            return
        }

        var outcome = TaskOutcome.INTERRUPTED
        var finishMessage = "Tác vụ mua sắm bị gián đoạn."
        var verifiedActionCount = 0

        executionJob = CoroutineScope(Dispatchers.Main).launch {
            val startTime = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    policyGuard.initDefaultRulesIfEmpty()
                    database.taskRunDao().insertRun(
                        TaskRun(
                            runId = runContext.runId,
                            profileId = runContext.profileId,
                            taskGoal = taskRequest.goal.take(TaskRun.MAX_STORED_GOAL_LENGTH),
                            taskCategory = taskRequest.taskCategory,
                            startTime = startTime,
                            endTime = 0L,
                            status = TaskRun.STATUS_RUNNING,
                            stepCount = 0
                        )
                    )
                }

                val skill = com.dinh.javis.agent.shopping.ShopeeShoppingSkill(
                    context = context,
                    observationEngine = observationEngine,
                    modelRouter = modelRouter,
                    policyGuard = policyGuard
                )

                val result = skill.execute(
                    request = request,
                    callback = object : AgentCallback {
                        override fun onStepStarted(stepIndex: Int, maxSteps: Int) {
                            callback?.onStepStarted(stepIndex, maxSteps)
                            legacyCallback?.onStepStarted(stepIndex, maxSteps)
                        }

                        override fun onThought(thought: String) {
                            callback?.onStatusUpdate(thought)
                            legacyCallback?.onThought(thought)
                        }

                        override fun onActionExecuted(action: String, details: String) {
                            verifiedActionCount++
                            legacyCallback?.onActionExecuted(action, details)
                        }

                        override fun onConfirmationRequired(question: String, onUserResponse: (Boolean) -> Unit) {
                            legacyCallback?.onConfirmationRequired(question, onUserResponse)
                        }

                        override fun onCompleted(success: Boolean, message: String) {
                            // Completed handled below from result outcome
                        }
                    }
                )

                outcome = result.outcome
                finishMessage = result.summaryVi
                val isSuccess = outcome == TaskOutcome.SUCCESS
                callback?.onCompleted(outcome, finishMessage)
                legacyCallback?.onCompleted(isSuccess, finishMessage)

            } catch (e: CancellationException) {
                outcome = TaskOutcome.CANCELLED
                finishMessage = "Tác vụ mua sắm đã bị người dùng hủy."
                callback?.onCompleted(outcome, finishMessage)
                legacyCallback?.onCompleted(false, finishMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error during shopping execution", e)
                outcome = TaskOutcome.FAILED
                finishMessage = "Đã xảy ra lỗi: ${e.localizedMessage ?: "Không xác định"}"
                callback?.onCompleted(outcome, finishMessage)
                legacyCallback?.onCompleted(false, finishMessage)
            } finally {
                val endTime = System.currentTimeMillis()
                val durationMs = endTime - startTime
                withContext(Dispatchers.IO + NonCancellable) {
                    try {
                        if (activeRunRef.get()?.runId == runContext.runId) {
                            val finalStatus = TaskRun.statusFromOutcome(outcome)
                            database.taskRunDao().insertRun(
                                TaskRun(
                                    runId = runContext.runId,
                                    profileId = runContext.profileId,
                                    taskGoal = taskRequest.goal.take(TaskRun.MAX_STORED_GOAL_LENGTH),
                                    taskCategory = taskRequest.taskCategory,
                                    startTime = startTime,
                                    endTime = endTime,
                                    status = finalStatus,
                                    stepCount = verifiedActionCount,
                                    verifiedActionCount = verifiedActionCount,
                                    durationMs = durationMs,
                                    failureReason = if (outcome != TaskOutcome.SUCCESS) finishMessage else null
                                )
                            )
                            if (preferenceManager.isBehaviorAnalyticsEnabled) {
                                behaviorAggregator.recordTaskMetric(
                                    runId = runContext.runId,
                                    taskCategory = taskRequest.taskCategory,
                                    event = TaskMetricEvent(
                                        taskCategory = taskRequest.taskCategory,
                                        outcome = outcome,
                                        durationMs = durationMs,
                                        verifiedActionCount = verifiedActionCount,
                                        retryCount = 0,
                                        failureCategory = if (outcome == TaskOutcome.FAILED) categorizeFailure(finishMessage) else null
                                    )
                                )
                            }
                            behaviorAggregator.runRetentionCleanup()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error recording task metrics in finally block", e)
                    } finally {
                        activeRunRef.compareAndSet(runContext, null)
                    }
                    Unit
                }
            }
        }
    }

    private fun dispatchAction(service: JavisAccessibilityService, proposal: ActionProposal): Boolean {
        return when (proposal.action.uppercase()) {
            "CLICK" -> {
                if (proposal.isCoordinateBased) {
                    service.tapAt(proposal.x, proposal.y)
                } else {
                    service.clickNodeByText(proposal.targetText)
                }
            }
            "SCROLL" -> {
                when (proposal.direction.uppercase()) {
                    "UP" -> service.scrollBackward()
                    "DOWN" -> service.scrollForward()
                    else -> service.scrollForward()
                }
            }
            "TYPE" -> service.typeText(proposal.inputText)
            "WAIT" -> true
            "NAVIGATE_BACK" -> { service.pressBack(); true }
            "NAVIGATE_HOME" -> { service.goHome(); true }
            else -> {
                Log.w(TAG, "Unknown action in dispatch: ${proposal.action}")
                false
            }
        }
    }

    private fun formatActionVi(proposal: ActionProposal): String {
        return when (proposal.action.uppercase()) {
            "CLICK" -> if (proposal.isCoordinateBased)
                "Chạm tọa độ (${proposal.x.toInt()}, ${proposal.y.toInt()})"
            else "Bấm nút \"${proposal.targetText}\""
            "SCROLL" -> "Cuộn trang ${if (proposal.direction.equals("UP", true)) "lên trên" else "xuống dưới"}"
            "TYPE" -> "Nhập văn bản"
            "WAIT" -> "Chờ tải giao diện"
            "NAVIGATE_BACK" -> "Quay lại"
            "NAVIGATE_HOME" -> "Về màn hình chính"
            else -> proposal.action
        }
    }

    private fun categorizeFailure(message: String): String {
        return when {
            message.contains("mạng", ignoreCase = true) || message.contains("kết nối", ignoreCase = true) -> "NETWORK"
            message.contains("thời gian", ignoreCase = true) -> "TIMEOUT"
            message.contains("chính sách", ignoreCase = true) || message.contains("bảo vệ", ignoreCase = true) -> "POLICY"
            message.contains("hành động", ignoreCase = true) -> "VALIDATION"
            else -> "UNKNOWN"
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
