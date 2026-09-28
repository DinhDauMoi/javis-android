package com.dinh.javis.agent.shopping

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.dinh.javis.agent.AgentCallback
import com.dinh.javis.agent.PolicyDecision
import com.dinh.javis.agent.PolicyGuard
import com.dinh.javis.agent.TaskOutcome
import com.dinh.javis.ai.ModelRouter
import com.dinh.javis.service.JavisAccessibilityService
import com.dinh.javis.utils.AppHelper
import com.dinh.javis.utils.TextNormalizer
import com.dinh.javis.vision.OcrBlock
import com.dinh.javis.vision.ScreenCaptureService
import com.dinh.javis.vision.ScreenObservationEngine
import com.dinh.javis.vision.ScreenTargetResolver
import kotlinx.coroutines.delay
import java.util.regex.Pattern

/**
 * Interface abstracting accessibility operations for Shopee shopping execution.
 * Allows injection of test fakes without requiring Robolectric or instrumentation.
 */
interface ShoppingAccessibilityBridge {
    fun isAvailable(): Boolean
    fun isAppForeground(packageName: String): Boolean
    fun getActivePackageName(): String?
    fun dumpNodeHierarchy(maxNodes: Int = 80): String
    fun clickSearchBox(): Boolean
    fun clickNodeByText(targetText: String): Boolean
    fun tapAt(x: Float, y: Float): Boolean
    fun typeText(text: String): Boolean
    fun getEditableText(): String?
    fun isSearchFocused(): Boolean
    fun performSearchAction(): Boolean
    fun scrollForward(): Boolean
}

/**
 * Default production implementation backed by [JavisAccessibilityService].
 */
class DefaultShoppingAccessibilityBridge(
    private val serviceProvider: () -> JavisAccessibilityService? = { JavisAccessibilityService.instance }
) : ShoppingAccessibilityBridge {
    private val service get() = serviceProvider()

    override fun isAvailable(): Boolean = service != null

    override fun isAppForeground(packageName: String): Boolean =
        service?.isAppForeground(packageName) ?: false

    override fun getActivePackageName(): String? =
        service?.getActivePackageName()

    override fun dumpNodeHierarchy(maxNodes: Int): String =
        service?.dumpNodeHierarchy(maxNodes) ?: ""

    override fun clickSearchBox(): Boolean {
        val s = service ?: return false
        val box = s.findSearchBox()
        if (box != null) {
            val clicked = box.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (clicked) return true
        }
        return s.clickNodeByText("Tìm kiếm") || s.clickNodeByText("Shopee") || s.clickNodeByText("Search")
    }

    override fun clickNodeByText(targetText: String): Boolean =
        service?.clickNodeByText(targetText) ?: false

    override fun tapAt(x: Float, y: Float): Boolean =
        service?.tapAt(x, y) ?: false

    override fun typeText(text: String): Boolean =
        service?.typeText(text) ?: false

    override fun getEditableText(): String? =
        service?.getEditableText()

    override fun isSearchFocused(): Boolean {
        val s = service ?: return false
        val root = s.rootInActiveWindow ?: return false
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null || s.getEditableText() != null
    }

    override fun performSearchAction(): Boolean =
        service?.performSearchAction() ?: false

    override fun scrollForward(): Boolean =
        service?.scrollForward() ?: false
}

/**
 * Shopee shopping skill module (Section 5, 8, 10).
 * Strictly read-only navigation, candidate extraction, deterministic ranking,
 * physical coordinate fallback, and verified detail page handoff.
 *
 * SAFETY INVARIANT: Never performs cart additions, purchases, seller messaging,
 * or account actions.
 */
class ShopeeShoppingSkill(
    private val context: Context? = null,
    private val observationEngine: ScreenObservationEngine? = null,
    private val ranker: ProductRanker = ProductRanker(),
    private val modelRouter: ModelRouter? = null,
    private val policyGuard: PolicyGuard? = null,
    private val accessibilityBridge: ShoppingAccessibilityBridge = DefaultShoppingAccessibilityBridge(),
    private val captureServiceChecker: () -> Boolean = { ScreenCaptureService.isCapturing() },
    private val appOpener: (name: String) -> Pair<Boolean, String?> = { name ->
        context?.let { AppHelper.openAppByName(it, name) } ?: Pair(false, null)
    }
) {

    private val TAG = "ShopeeShoppingSkill"
    private val SHOPEE_PACKAGE = "com.shopee.vn"

    /**
     * Executes the shopping search and opens the best verified match on the phone.
     */
    suspend fun execute(
        request: ProductSearchRequest,
        callback: AgentCallback? = null
    ): ShoppingResult {
        callback?.onThought(request.buildExplanationVi())

        // 1. Accessibility readiness check BEFORE launching Shopee (R1)
        if (!accessibilityBridge.isAvailable()) {
            val msg = "Bạn chưa bật quyền Trợ năng cho JAVIS để điều khiển Shopee. Vui lòng bật quyền Trợ năng trong Cài đặt nhé."
            Log.w(TAG, msg)
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.BLOCKED,
                summaryVi = msg
            )
        }

        // 2. Package readiness check BEFORE launching Shopee (R1)
        val (installed, _) = appOpener("shopee")
        if (!installed) {
            val msg = "Chưa cài đặt ứng dụng Shopee trên thiết bị này."
            Log.w(TAG, msg)
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.BLOCKED,
                summaryVi = msg
            )
        }

        // 3. Foreground verification with bounded polling
        val foregroundDeadlineMs = System.currentTimeMillis() + 5000L
        var isForeground = false
        while (System.currentTimeMillis() < foregroundDeadlineMs) {
            if (accessibilityBridge.isAppForeground(SHOPEE_PACKAGE)) {
                isForeground = true
                break
            }
            delay(200)
        }

        if (!isForeground && !accessibilityBridge.isAppForeground(SHOPEE_PACKAGE)) {
            val msg = "Quá thời gian chờ ứng dụng Shopee hiển thị ở tiền cảnh."
            Log.w(TAG, msg)
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.BLOCKED,
                summaryVi = msg
            )
        }

        val startTime = System.currentTimeMillis()
        var attemptedActionCount = 0
        var verifiedActionCount = 0
        val maxActions = request.executionLimits.maxUiActions
        val deadlineMs = request.executionLimits.maxTimeMs

        // Safety guard check helper (R4)
        fun checkGuardrails(): PolicyCheckResultInternal {
            val activePkg = accessibilityBridge.getActivePackageName()
            if (activePkg != SHOPEE_PACKAGE) {
                return PolicyCheckResultInternal.Denied("Đã rời khỏi ứng dụng Shopee (hiện tại: ${activePkg ?: "Không xác định"}). Dừng tác vụ.")
            }
            if (policyGuard != null) {
                val pCheck = policyGuard.checkPackagePreObservation(activePkg)
                if (pCheck.decision == PolicyDecision.DENY) {
                    return PolicyCheckResultInternal.Denied(pCheck.reason)
                }
            }
            if (System.currentTimeMillis() - startTime > deadlineMs) {
                return PolicyCheckResultInternal.Denied("Đã vượt quá thời gian tối đa cho phép (${deadlineMs / 1000}s).")
            }
            if (attemptedActionCount >= maxActions) {
                return PolicyCheckResultInternal.Denied("Đã vượt quá số bước thao tác tối đa ($maxActions bước).")
            }
            return PolicyCheckResultInternal.Allowed
        }

        val guardCheck = checkGuardrails()
        if (guardCheck is PolicyCheckResultInternal.Denied) {
            callback?.onCompleted(false, guardCheck.reason)
            return ShoppingResult(outcome = TaskOutcome.FAILED, summaryVi = guardCheck.reason, executedActions = verifiedActionCount, attemptedActions = attemptedActionCount)
        }

        val isCapturing = captureServiceChecker()

        // 4. Tiered observation readiness check
        val initialHierarchy = accessibilityBridge.dumpNodeHierarchy()
        if (initialHierarchy.isBlank() && !isCapturing) {
            val msg = "Giao diện Shopee không thể đọc qua Trợ năng và dịch vụ quan sát màn hình chưa được bật. Bạn hãy nói 'Bật dịch vụ màn hình' để cấp quyền nhé."
            Log.w(TAG, msg)
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.BLOCKED,
                summaryVi = msg,
                executedActions = verifiedActionCount,
                attemptedActions = attemptedActionCount
            )
        }

        val obsEngine = observationEngine ?: (context?.let { ScreenObservationEngine(it) })

        // 5. Search-field resolution & Focus verification (R1)
        callback?.onThought("Đang tìm ô tìm kiếm trên Shopee...")
        if (attemptedActionCount >= maxActions) {
            val msg = "Đã vượt quá số bước thao tác tối đa ($maxActions bước)."
            callback?.onCompleted(false, msg)
            return ShoppingResult(outcome = TaskOutcome.BUDGET_EXHAUSTED, summaryVi = msg, executedActions = verifiedActionCount, attemptedActions = attemptedActionCount)
        }
        attemptedActionCount++
        var focusedSearch = accessibilityBridge.clickSearchBox()

        if (!focusedSearch && isCapturing && obsEngine != null) {
            if (attemptedActionCount < maxActions) {
                attemptedActionCount++
                val searchObs = obsEngine.observeScreen(captureVisual = false, forceOcr = true)
                try {
                    val targetResolver = ScreenTargetResolver(searchObs.observation.screenshotWidth, searchObs.observation.screenshotHeight)
                    val searchPoint = targetResolver.findTargetFromOcr("Tìm kiếm", searchObs.ocrBlocks)
                        ?: targetResolver.findTargetFromOcr("Shopee", searchObs.ocrBlocks)
                    if (searchPoint != null) {
                        accessibilityBridge.tapAt(searchPoint.x, searchPoint.y)
                    }
                } finally {
                    searchObs.bitmap?.recycle()
                }
            }
        }

        // Bounded polling for search field focus
        val focusDeadline = System.currentTimeMillis() + 3000L
        var isSearchFocused = false
        while (System.currentTimeMillis() < focusDeadline) {
            if (accessibilityBridge.isSearchFocused()) {
                isSearchFocused = true
                break
            }
            delay(200)
        }

        // One bounded recovery attempt if not yet focused and budget allows
        if (!isSearchFocused && attemptedActionCount < maxActions) {
            attemptedActionCount++
            accessibilityBridge.clickSearchBox()
            delay(300)
            isSearchFocused = accessibilityBridge.isSearchFocused()
        }

        // R1: Require verified search focus before proceeding to typing
        if (!isSearchFocused) {
            val msg = "Không thể tìm hoặc kích hoạt ô tìm kiếm trên Shopee."
            Log.w(TAG, msg)
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.FAILED,
                summaryVi = msg,
                executedActions = verifiedActionCount,
                attemptedActions = attemptedActionCount
            )
        }

        val activePkgAfterFocus = accessibilityBridge.getActivePackageName()
        if (activePkgAfterFocus != SHOPEE_PACKAGE) {
            val msg = "Ứng dụng Shopee không còn hiển thị sau khi chọn ô tìm kiếm."
            callback?.onCompleted(false, msg)
            return ShoppingResult(outcome = TaskOutcome.FAILED, summaryVi = msg, executedActions = verifiedActionCount, attemptedActions = attemptedActionCount)
        }

        // 6. Type verification: Type query and verify text in editable box (R2)
        callback?.onThought("Đang nhập từ khóa \"${request.query}\"...")
        if (attemptedActionCount >= maxActions) {
            val msg = "Đã vượt quá số bước thao tác tối đa ($maxActions bước)."
            callback?.onCompleted(false, msg)
            return ShoppingResult(outcome = TaskOutcome.BUDGET_EXHAUSTED, summaryVi = msg, executedActions = verifiedActionCount, attemptedActions = attemptedActionCount)
        }
        attemptedActionCount++
        accessibilityBridge.typeText(request.query)

        val typeDeadline = System.currentTimeMillis() + 2500L
        var isTypeVerified = false
        while (System.currentTimeMillis() < typeDeadline) {
            val currentText = accessibilityBridge.getEditableText() ?: ""
            if (isExpectedQueryText(currentText, request.query)) {
                isTypeVerified = true
                break
            }
            delay(200)
        }

        if (!isTypeVerified && attemptedActionCount < maxActions) {
            // Retry typing once with budget accounting
            attemptedActionCount++
            accessibilityBridge.typeText(request.query)
            delay(300)
            val currentText = accessibilityBridge.getEditableText() ?: ""
            if (isExpectedQueryText(currentText, request.query)) {
                isTypeVerified = true
            }
        }

        // R2: Require verified query text before proceeding to submission
        if (!isTypeVerified) {
            val msg = "Không thể xác nhận đã nhập đúng từ khóa \"${request.query}\" vào ô tìm kiếm."
            Log.w(TAG, msg)
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.FAILED,
                summaryVi = msg,
                executedActions = verifiedActionCount,
                attemptedActions = attemptedActionCount
            )
        }

        verifiedActionCount++
        callback?.onActionExecuted("SEARCH_INPUT", "Đã nhập từ khóa tìm kiếm: ${request.query}")

        // 7. Submit verification (R2, R3)
        callback?.onThought("Đang gửi tìm kiếm...")
        if (attemptedActionCount >= maxActions) {
            val msg = "Đã vượt quá số bước thao tác tối đa ($maxActions bước)."
            callback?.onCompleted(false, msg)
            return ShoppingResult(outcome = TaskOutcome.BUDGET_EXHAUSTED, summaryVi = msg, executedActions = verifiedActionCount, attemptedActions = attemptedActionCount)
        }
        attemptedActionCount++
        val submitted = accessibilityBridge.performSearchAction()
        if (!submitted && isCapturing && obsEngine != null && attemptedActionCount < maxActions) {
            val submitObs = obsEngine.observeScreen(captureVisual = false, forceOcr = true)
            try {
                val targetResolver = ScreenTargetResolver(submitObs.observation.screenshotWidth, submitObs.observation.screenshotHeight)
                val submitPoint = targetResolver.findTargetFromOcr("Tìm kiếm", submitObs.ocrBlocks)
                if (submitPoint != null) {
                    attemptedActionCount++
                    accessibilityBridge.tapAt(submitPoint.x, submitPoint.y)
                }
            } finally {
                submitObs.bitmap?.recycle()
            }
        }

        // Bounded polling for result screen transition (R3)
        val resultDeadline = System.currentTimeMillis() + 4000L
        var isResultScreenReady = false
        var isResultScreenEmpty = false
        var isBlockedScreen = false
        var blockReason = ""

        while (System.currentTimeMillis() < resultDeadline) {
            val hierarchy = accessibilityBridge.dumpNodeHierarchy()

            // Check for login / CAPTCHA / verification screens
            if (hierarchy.contains("Đăng nhập", ignoreCase = true) ||
                hierarchy.contains("Xác minh", ignoreCase = true) ||
                hierarchy.contains("Mã xác thực", ignoreCase = true)
            ) {
                isBlockedScreen = true
                blockReason = "Shopee yêu cầu đăng nhập hoặc xác minh bảo mật để tiếp tục."
                break
            }

            // Check for empty results screen
            if (hierarchy.contains("Không tìm thấy kết quả", ignoreCase = true) ||
                hierarchy.contains("Không có kết quả", ignoreCase = true) ||
                hierarchy.contains("Rất tiếc, không tìm thấy", ignoreCase = true)
            ) {
                isResultScreenEmpty = true
                break
            }

            // Multiple structural indicators: Filter ("Bộ lọc") AND (Sort tabs or Price sort)
            // Stale screens containing only "Giá" must NOT pass.
            val hasFilter = hierarchy.contains("Bộ lọc", ignoreCase = true)
            val hasTabs = hierarchy.contains("Liên quan", ignoreCase = true) ||
                    hierarchy.contains("Phổ biến", ignoreCase = true) ||
                    hierarchy.contains("Bán chạy", ignoreCase = true)
            val hasPriceSort = hierarchy.contains("Giá", ignoreCase = true) && hasFilter

            val isSearchSuggestionScreen = hierarchy.contains("Lịch sử tìm kiếm", ignoreCase = true) ||
                    hierarchy.contains("Tìm kiếm gần đây", ignoreCase = true)

            if (!isSearchSuggestionScreen && (hasFilter && (hasTabs || hasPriceSort))) {
                isResultScreenReady = true
                break
            }
            delay(200)
        }

        if (isBlockedScreen) {
            callback?.onCompleted(false, blockReason)
            return ShoppingResult(outcome = TaskOutcome.BLOCKED, summaryVi = blockReason, executedActions = verifiedActionCount, attemptedActions = attemptedActionCount)
        }

        if (isResultScreenEmpty) {
            val msg = "Không tìm thấy kết quả nào cho từ khóa \"${request.query}\" trên Shopee."
            callback?.onCompleted(true, msg)
            return ShoppingResult(outcome = TaskOutcome.NO_MATCH, summaryVi = msg, executedActions = verifiedActionCount, attemptedActions = attemptedActionCount)
        }

        if (!isResultScreenReady) {
            val msg = "Hết thời gian chờ tải kết quả tìm kiếm trên Shopee."
            Log.w(TAG, msg)
            callback?.onCompleted(false, msg)
            return ShoppingResult(outcome = TaskOutcome.FAILED, summaryVi = msg, executedActions = verifiedActionCount, attemptedActions = attemptedActionCount)
        }

        verifiedActionCount++
        callback?.onActionExecuted("SUBMIT_SEARCH", "Đã gửi tìm kiếm: ${request.query}")

        callback?.onThought("Đang tải kết quả tìm kiếm cho \"${request.query}\"...")
        delay(1000)

        // 8. Collect candidates across search results with frame/page binding (R3)
        val candidates = mutableListOf<ProductCandidate>()
        var page = 1
        val maxPages = request.executionLimits.maxSearchResultPages
        val maxCandidates = request.executionLimits.maxCandidates

        while (page <= maxPages && candidates.size < maxCandidates) {
            val guard = checkGuardrails()
            if (guard is PolicyCheckResultInternal.Denied) {
                val msg = guard.reason
                callback?.onCompleted(false, msg)
                return ShoppingResult(outcome = TaskOutcome.FAILED, summaryVi = msg)
            }

            val hierarchy = accessibilityBridge.dumpNodeHierarchy(maxNodes = 100)
            val extractedNodes = extractProductCandidates(hierarchy, pageIndex = page)

            val extractedOcr = if (isCapturing && obsEngine != null) {
                val obsResult = obsEngine.observeScreen(captureVisual = false, forceOcr = true)
                try {
                    extractCandidatesFromOcr(obsResult.ocrBlocks, pageIndex = page)
                } finally {
                    obsResult.bitmap?.recycle()
                }
            } else {
                emptyList()
            }

            // Combine candidates prioritizing node candidates then OCR candidates
            for (cand in extractedNodes) {
                if (candidates.none { isSameProduct(it.title, cand.title) }) {
                    candidates.add(cand)
                    if (candidates.size >= maxCandidates) break
                }
            }
            for (cand in extractedOcr) {
                if (candidates.none { isSameProduct(it.title, cand.title) }) {
                    candidates.add(cand)
                    if (candidates.size >= maxCandidates) break
                }
            }

            if (candidates.size < maxCandidates && page < maxPages) {
                if (attemptedActionCount >= maxActions) {
                    Log.w(TAG, "Reached max physical actions ($maxActions) during candidate pagination.")
                    break
                }
                callback?.onThought("Đang cuộn để xem thêm sản phẩm (trang $page/$maxPages)...")
                attemptedActionCount++
                val scrolled = accessibilityBridge.scrollForward()
                if (scrolled) {
                    verifiedActionCount++
                }
                delay(1000)
            }
            page++
        }

        Log.d(TAG, "Extracted ${candidates.size} product candidates from Shopee.")
        if (candidates.isEmpty()) {
            val msg = "Không tìm thấy kết quả tìm kiếm nào trên Shopee cho từ khóa: \"${request.query}\"."
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.NO_MATCH,
                summaryVi = msg,
                executedActions = verifiedActionCount,
                attemptedActions = attemptedActionCount
            )
        }

        // 9. Rank candidates deterministically
        callback?.onThought("Đang đối chiếu ngân sách và đánh giá các sản phẩm...")
        val ranked = ranker.rank(candidates, request)
        val bestEvaluation = ranked.firstOrNull { it.isEligible }

        if (bestEvaluation == null) {
            val topRejected = ranked.firstOrNull()
            val reason = topRejected?.reasonVi ?: "không đáp ứng điều kiện tìm kiếm"
            val msg = "Đã xem ${candidates.size} sản phẩm nhưng không có sản phẩm nào phù hợp yêu cầu ($reason)."
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.NO_MATCH,
                inspectedCandidates = candidates,
                summaryVi = msg,
                executedActions = verifiedActionCount,
                attemptedActions = attemptedActionCount
            )
        }

        val bestCandidate = bestEvaluation.candidate
        callback?.onThought("Sản phẩm phù hợp nhất: \"${bestCandidate.title}\" (${bestEvaluation.reasonVi})")

        // 10. Open the selected product detail page (Re-locating on fresh screen, R3)
        if (attemptedActionCount >= maxActions) {
            val msg = "Đã vượt quá số bước thao tác tối đa ($maxActions bước)."
            callback?.onCompleted(false, msg)
            return ShoppingResult(outcome = TaskOutcome.BUDGET_EXHAUSTED, summaryVi = msg, executedActions = verifiedActionCount, attemptedActions = attemptedActionCount)
        }
        attemptedActionCount++
        var tapSuccess = accessibilityBridge.clickNodeByText(bestCandidate.title.take(30))

        if (!tapSuccess && isCapturing && obsEngine != null && attemptedActionCount < maxActions) {
            val currentObs = obsEngine.observeScreen(captureVisual = false, forceOcr = true)
            try {
                val targetResolver = ScreenTargetResolver(currentObs.observation.screenshotWidth, currentObs.observation.screenshotHeight)
                val freshTapPoint = targetResolver.findProductCardCheckpoint(
                    title = bestCandidate.title,
                    priceText = bestCandidate.effectivePrice?.let { formatVnd(it) },
                    ocrBlocks = currentObs.ocrBlocks
                )
                if (freshTapPoint != null) {
                    attemptedActionCount++
                    tapSuccess = accessibilityBridge.tapAt(freshTapPoint.x, freshTapPoint.y)
                }
            } finally {
                currentObs.bitmap?.recycle()
            }
        }

        if (!tapSuccess) {
            val msg = "Không thể tìm thấy vị trí hiển thị của sản phẩm \"${bestCandidate.title}\" trên màn hình hiện tại."
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.FAILED,
                selectedProduct = bestCandidate,
                inspectedCandidates = candidates,
                summaryVi = msg,
                executedActions = verifiedActionCount,
                attemptedActions = attemptedActionCount
            )
        }

        // Bounded polling for detail page verification
        val detailDeadline = System.currentTimeMillis() + 4000L
        var isVerifiedDetailPage = false
        while (System.currentTimeMillis() < detailDeadline) {
            val finalPackage = accessibilityBridge.getActivePackageName()
            val finalHierarchy = accessibilityBridge.dumpNodeHierarchy()
            val finalOcr = if (isCapturing && obsEngine != null) {
                val ocrObs = obsEngine.observeScreen(captureVisual = false, forceOcr = true)
                try {
                    ocrObs.observation.ocrText ?: ""
                } finally {
                    ocrObs.bitmap?.recycle()
                }
            } else ""

            val combinedEvidence = "$finalHierarchy\n$finalOcr"
            val isShopeeForeground = finalPackage == SHOPEE_PACKAGE

            // Negative check: verify we are NOT still on the search results grid page (R2)
            val isStillSearchResultGrid = combinedEvidence.contains("Bộ lọc", ignoreCase = true) &&
                    combinedEvidence.contains("Phổ biến", ignoreCase = true) &&
                    combinedEvidence.contains("Bán chạy", ignoreCase = true) &&
                    !combinedEvidence.contains("Chi tiết sản phẩm", ignoreCase = true) &&
                    !combinedEvidence.contains("Mô tả sản phẩm", ignoreCase = true)

            // Structural detail page indicators (R2)
            val hasStructuralDetailIndicators = combinedEvidence.contains("Chi tiết sản phẩm", ignoreCase = true) ||
                    combinedEvidence.contains("Mô tả sản phẩm", ignoreCase = true) ||
                    combinedEvidence.contains("Thông tin sản phẩm", ignoreCase = true) ||
                    (combinedEvidence.contains("Chat ngay", ignoreCase = true) && combinedEvidence.contains("Thêm vào giỏ", ignoreCase = true)) ||
                    (combinedEvidence.contains("Mua ngay", ignoreCase = true) && combinedEvidence.contains("Voucher", ignoreCase = true))

            // Product identity verification: fresh screen MUST contain product title tokens (R2)
            val titleTokens = TextNormalizer.removeAccents(bestCandidate.title.lowercase())
                .split("\\s+".toRegex()).filter { it.length >= 3 }
            val normEvidence = TextNormalizer.removeAccents(combinedEvidence.lowercase())
            val matchingTokenCount = titleTokens.count { normEvidence.contains(it) }
            val hasProductIdentityMatch = titleTokens.isNotEmpty() &&
                    (matchingTokenCount >= (titleTokens.size / 2).coerceAtLeast(1) || normEvidence.contains(titleTokens.take(3).joinToString(" ")))

            // Price re-verification on detail page if visible (R2)
            var priceReverified = true
            if (request.maxPrice != null && bestCandidate.effectivePrice != null) {
                val detailPrices = extractPriceCandidatesFromEvidence(combinedEvidence)
                if (detailPrices.isNotEmpty()) {
                    val highestDetailPrice = detailPrices.maxOrNull() ?: 0L
                    if (highestDetailPrice > request.maxPrice) {
                        Log.w(TAG, "Re-verified detail page price ($highestDetailPrice đ) exceeds max budget (${request.maxPrice} đ).")
                        priceReverified = false
                    }
                }
            }

            if (isShopeeForeground && !isStillSearchResultGrid && hasStructuralDetailIndicators && hasProductIdentityMatch && priceReverified) {
                isVerifiedDetailPage = true
                break
            }
            delay(200)
        }

        if (isVerifiedDetailPage) {
            callback?.onActionExecuted("OPEN_PRODUCT", "Mở sản phẩm: ${bestCandidate.title}")
            verifiedActionCount++

            val priceStr = bestCandidate.effectivePrice?.let { formatVnd(it) } ?: "chưa rõ"
            val ratingStr = bestCandidate.rating?.let { "★ $it" } ?: ""
            val summary = "Mình đã mở sản phẩm phù hợp nhất trong các sản phẩm đã xem: ${bestCandidate.title}. Giá $priceStr $ratingStr. Bạn có thể xem chi tiết trên màn hình."

            callback?.onCompleted(true, summary)
            return ShoppingResult(
                outcome = TaskOutcome.SUCCESS,
                selectedProduct = bestCandidate,
                inspectedCandidates = candidates,
                summaryVi = summary,
                executedActions = verifiedActionCount,
                attemptedActions = attemptedActionCount
            )
        } else {
            val msg = "Không thể xác nhận đã mở trang chi tiết sản phẩm phù hợp (${bestCandidate.title}) trên Shopee."
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.FAILED,
                selectedProduct = bestCandidate,
                inspectedCandidates = candidates,
                summaryVi = msg,
                executedActions = verifiedActionCount,
                attemptedActions = attemptedActionCount
            )
        }
    }

    /**
     * Verifies that the text in the editable search field matches the expected query
     * and is not empty or contaminated by unrelated content.
     */
    fun isExpectedQueryText(currentText: String, expectedQuery: String): Boolean {
        val normCurrent = TextNormalizer.removeAccents(currentText).trim().lowercase()
        val normExpected = TextNormalizer.removeAccents(expectedQuery).trim().lowercase()
        if (normCurrent.isEmpty() || normExpected.isEmpty()) return false
        if (normCurrent == normExpected) return true
        if (normCurrent.contains(normExpected)) {
            val extraLength = normCurrent.length - normExpected.length
            return extraLength <= 10
        }
        return false
    }

    /**
     * Parses product cards from Shopee's accessibility node dump.
     * Does NOT fabricate default ratings or reviews.
     */
    fun extractProductCandidates(nodeDump: String, pageIndex: Int = 1): List<ProductCandidate> {
        val candidates = mutableListOf<ProductCandidate>()
        val lines = nodeDump.lines()

        var currentTitle: String? = null
        var currentPrice: Long? = null
        var currentRating: Float? = null
        var currentSales: Int? = null
        var currentCenterX: Float? = null
        var currentCenterY: Float? = null
        var isMall = false
        var isPreferred = false

        for (line in lines) {
            val textMatch = Regex("text=\"([^\"]+)\"").find(line)
            val descMatch = Regex("desc=\"([^\"]+)\"").find(line)
            val content = textMatch?.groupValues?.get(1) ?: descMatch?.groupValues?.get(1) ?: continue

            val centerMatch = Regex("center=\\(([0-9.]+),([0-9.]+)\\)").find(line)
            val lineCenterX = centerMatch?.groupValues?.get(1)?.toFloatOrNull()
            val lineCenterY = centerMatch?.groupValues?.get(2)?.toFloatOrNull()

            val lower = content.lowercase()
            if (lower == "mall") {
                isMall = true
                continue
            }
            if (lower.contains("yeu thich") || lower.contains("yêu thích")) {
                isPreferred = true
                continue
            }

            val price = extractPriceFromText(content)
            if (price != null) {
                currentPrice = price
            }

            val ratingMatch = Regex("\\b([345]\\.[0-9])\\b").find(content)
            if (ratingMatch != null) {
                currentRating = ratingMatch.groupValues[1].toFloatOrNull()
            }

            val salesMatch = Regex("Đã bán\\s+([0-9.,]+k?)", RegexOption.IGNORE_CASE).find(content)
            if (salesMatch != null) {
                val rawSales = salesMatch.groupValues[1]
                currentSales = parseSalesCount(rawSales)
            }

            if (content.length >= 15 && price == null && !content.startsWith("Đã bán") &&
                !content.contains("Tìm kiếm", ignoreCase = true) &&
                !content.contains("Giảm giá", ignoreCase = true)
            ) {
                if (currentTitle != null && currentPrice != null) {
                    candidates.add(
                        ProductCandidate(
                            title = currentTitle,
                            price = currentPrice,
                            rating = currentRating,
                            reviewCount = null,
                            salesCount = currentSales,
                            checkpointX = currentCenterX,
                            checkpointY = currentCenterY,
                            isMall = isMall,
                            isPreferred = isPreferred,
                            source = "accessibility",
                            pageIndex = pageIndex
                        )
                    )
                    currentPrice = null
                    currentRating = null
                    currentSales = null
                    isMall = false
                    isPreferred = false
                }
                currentTitle = content
                currentCenterX = lineCenterX
                currentCenterY = lineCenterY
            }
        }

        if (currentTitle != null && currentPrice != null) {
            candidates.add(
                ProductCandidate(
                    title = currentTitle,
                    price = currentPrice,
                    rating = currentRating,
                    reviewCount = null,
                    salesCount = currentSales,
                    checkpointX = currentCenterX,
                    checkpointY = currentCenterY,
                    isMall = isMall,
                    isPreferred = isPreferred,
                    source = "accessibility",
                    pageIndex = pageIndex
                )
            )
        }

        return candidates
    }

    /**
     * Extracts product candidates directly from ML Kit OCR blocks.
     * Groups titles, prices, ratings, and sales volume by spatial proximity.
     */
    fun extractCandidatesFromOcr(ocrBlocks: List<OcrBlock>, pageIndex: Int = 1): List<ProductCandidate> {
        if (ocrBlocks.isEmpty()) return emptyList()

        val candidates = mutableListOf<ProductCandidate>()
        val excludedKeywords = setOf(
            "shopee", "tim kiem", "pho bien", "moi nhat", "ban chay",
            "bo loc", "goi y", "thong bao", "tin nhan", "toi", "trang chu", "live", "video",
            "them vao gio", "mua ngay", "shop xu huong"
        )

        val titleBlocks = mutableListOf<OcrBlock>()
        val priceBlocks = mutableListOf<Pair<OcrBlock, Long>>()
        val salesBlocks = mutableListOf<Pair<OcrBlock, Int>>()
        val ratingBlocks = mutableListOf<Pair<OcrBlock, Float>>()
        val mallBlocks = mutableListOf<OcrBlock>()
        val preferredBlocks = mutableListOf<OcrBlock>()

        for (block in ocrBlocks) {
            val text = block.text.trim()
            val textLower = text.lowercase()
            val normText = TextNormalizer.removeAccents(textLower)

            if (block.top > 0 && block.top < 80) continue

            if (textLower == "mall") {
                mallBlocks.add(block)
                continue
            }
            if (normText.contains("yeu thich")) {
                preferredBlocks.add(block)
                continue
            }

            val price = extractPriceFromText(text)
            if (price != null) {
                priceBlocks.add(block to price)
                continue
            }

            val salesMatch = Regex("Đã bán\\s+([0-9.,]+k?)", RegexOption.IGNORE_CASE).find(text)
            if (salesMatch != null) {
                val count = parseSalesCount(salesMatch.groupValues[1])
                salesBlocks.add(block to count)
                continue
            }

            val ratingMatch = Regex("\\b([345]\\.[0-9])\\b").find(text)
            if (ratingMatch != null && text.length <= 5) {
                val r = ratingMatch.groupValues[1].toFloatOrNull()
                if (r != null) {
                    ratingBlocks.add(block to r)
                    continue
                }
            }

            val isExcluded = normText == "gia" || normText == "loc" || normText == "bo loc" ||
                    excludedKeywords.any { normText.contains(it) }
            if (text.length >= 12 && !isExcluded && !text.startsWith("Đã bán") && !text.startsWith("₫")) {
                titleBlocks.add(block)
            }
        }

        for (titleBlock in titleBlocks) {
            val matchingPrice = priceBlocks.filter { (pBlock, _) ->
                pBlock.top >= titleBlock.top - 40 &&
                        pBlock.top <= titleBlock.bottom + 350 &&
                        kotlin.math.abs(pBlock.centerX - titleBlock.centerX) < 300
            }.minByOrNull { (pBlock, _) ->
                kotlin.math.abs(pBlock.top - titleBlock.bottom)
            }

            if (matchingPrice != null) {
                val priceVal = matchingPrice.second
                val salesVal = salesBlocks.firstOrNull { (sBlock, _) ->
                    kotlin.math.abs(sBlock.centerY - matchingPrice.first.centerY) < 150 &&
                            kotlin.math.abs(sBlock.centerX - titleBlock.centerX) < 300
                }?.second

                val ratingVal = ratingBlocks.firstOrNull { (rBlock, _) ->
                    kotlin.math.abs(rBlock.centerY - matchingPrice.first.centerY) < 150 &&
                            kotlin.math.abs(rBlock.centerX - titleBlock.centerX) < 300
                }?.second

                val isMall = mallBlocks.any { mBlock ->
                    kotlin.math.abs(mBlock.centerY - titleBlock.centerY) < 120 &&
                            kotlin.math.abs(mBlock.centerX - titleBlock.centerX) < 200
                }
                val isPreferred = preferredBlocks.any { pfBlock ->
                    kotlin.math.abs(pfBlock.centerY - titleBlock.centerY) < 120 &&
                            kotlin.math.abs(pfBlock.centerX - titleBlock.centerX) < 200
                }

                candidates.add(
                    ProductCandidate(
                        title = titleBlock.text.trim(),
                        price = priceVal,
                        rating = ratingVal,
                        reviewCount = null,
                        salesCount = salesVal,
                        checkpointX = titleBlock.centerX,
                        checkpointY = titleBlock.centerY,
                        isMall = isMall,
                        isPreferred = isPreferred,
                        source = "ocr",
                        pageIndex = pageIndex
                    )
                )
            }
        }

        return candidates
    }

    private fun extractPriceFromText(text: String): Long? {
        val clean = text.replace(" ", "")
        val pattern = Pattern.compile("(?:₫|đ)?([0-9]{1,3}(?:\\.[0-9]{3})+)(?:₫|đ)?")
        val matcher = pattern.matcher(clean)
        if (matcher.find()) {
            val numStr = matcher.group(1)?.replace(".", "")
            return numStr?.toLongOrNull()
        }
        return null
    }

    private fun extractPriceCandidatesFromEvidence(text: String): List<Long> {
        val prices = mutableListOf<Long>()
        val pattern = Pattern.compile("(?:₫|đ)?([0-9]{1,3}(?:\\.[0-9]{3})+)(?:₫|đ)?")
        val matcher = pattern.matcher(text.replace(" ", ""))
        while (matcher.find()) {
            val num = matcher.group(1)?.replace(".", "")?.toLongOrNull()
            if (num != null && num > 1000) {
                prices.add(num)
            }
        }
        return prices
    }

    private fun parseSalesCount(salesText: String): Int {
        val clean = salesText.replace(" ", "").lowercase()
        return if (clean.endsWith("k")) {
            val num = clean.removeSuffix("k").replace(",", ".").toDoubleOrNull() ?: 1.0
            (num * 1000).toInt()
        } else {
            clean.replace(".", "").replace(",", "").toIntOrNull() ?: 0
        }
    }

    private fun isSameProduct(title1: String, title2: String): Boolean {
        val t1 = TextNormalizer.removeAccents(title1.take(25).lowercase()).trim()
        val t2 = TextNormalizer.removeAccents(title2.take(25).lowercase()).trim()
        return t1 == t2
    }

    private fun formatVnd(amount: Long): String {
        return "%,d đ".format(amount).replace(',', '.')
    }

    private sealed class PolicyCheckResultInternal {
        object Allowed : PolicyCheckResultInternal()
        data class Denied(val reason: String) : PolicyCheckResultInternal()
    }
}
