package com.dinh.javis.agent.shopping

import android.graphics.Rect
import com.dinh.javis.agent.AgentCallback
import com.dinh.javis.agent.TaskOutcome
import com.dinh.javis.vision.OcrBlock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ShopeeShoppingSkillTest {

    private lateinit var skill: ShopeeShoppingSkill
    private lateinit var ranker: ProductRanker

    @Before
    fun setUp() {
        skill = ShopeeShoppingSkill()
        ranker = ProductRanker()
    }

    private fun createOcrBlock(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): OcrBlock {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        return OcrBlock(
            text = text,
            boundingBox = Rect().apply {
                this.left = left.toInt()
                this.top = top.toInt()
                this.right = right.toInt()
                this.bottom = bottom.toInt()
            },
            centerX = cx,
            centerY = cy,
            top = top,
            bottom = bottom,
            left = left,
            right = right
        )
    }

    private class FakeAccessibilityBridge(
        var available: Boolean = true,
        var appForeground: Boolean = true,
        var activePackage: String? = "com.shopee.vn",
        var hierarchy: String = "[#1] View bounds=(0,0,1080,2400) center=(540,1200)",
        var searchBoxClicked: Boolean = true,
        var textClicked: Boolean = true,
        var typed: Boolean = true,
        var currentEditableText: String? = null,
        var searchFocused: Boolean = true,
        var searchSubmitted: Boolean = true,
        var typeCalled: Boolean = false,
        var searchSubmittedCalled: Boolean = false,
        var scrollForwardCalled: Boolean = false,
        var overrideTextOnType: Boolean = true
    ) : ShoppingAccessibilityBridge {
        override fun isAvailable(): Boolean = available
        override fun isAppForeground(packageName: String): Boolean = appForeground
        override fun getActivePackageName(): String? = activePackage
        override fun dumpNodeHierarchy(maxNodes: Int): String = hierarchy
        override fun clickSearchBox(): Boolean = searchBoxClicked
        override fun clickNodeByText(targetText: String): Boolean = textClicked
        override fun tapAt(x: Float, y: Float): Boolean = true
        override fun typeText(text: String): Boolean {
            typeCalled = true
            if (overrideTextOnType) {
                currentEditableText = text
            }
            return typed
        }
        override fun getEditableText(): String? = currentEditableText
        override fun isSearchFocused(): Boolean = searchFocused
        override fun performSearchAction(): Boolean {
            searchSubmittedCalled = true
            return searchSubmitted
        }
        override fun scrollForward(): Boolean {
            scrollForwardCalled = true
            return true
        }
    }

    @Test
    fun execute_missingAccessibility_blocksWithActionableVietnameseMessage() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(available = false)
        val skillWithNoA11y = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        val request = ProductSearchRequest(query = "t-shirt", maxPrice = 100_000L)
        var completedCalled = false
        var completedSuccess = true
        var completedMsg = ""

        val result = skillWithNoA11y.execute(
            request = request,
            callback = object : AgentCallback {
                override fun onStepStarted(stepIndex: Int, maxSteps: Int) {}
                override fun onThought(thought: String) {}
                override fun onActionExecuted(action: String, details: String) {}
                override fun onConfirmationRequired(question: String, onUserResponse: (Boolean) -> Unit) {}
                override fun onCompleted(success: Boolean, message: String) {
                    completedCalled = true
                    completedSuccess = success
                    completedMsg = message
                }
            }
        )

        assertEquals(TaskOutcome.BLOCKED, result.outcome)
        assertTrue(result.summaryVi.contains("quyền Trợ năng"))
        assertTrue(completedCalled)
        assertFalse(completedSuccess)
        assertEquals(result.summaryVi, completedMsg)
    }

    @Test
    fun execute_shopeeNotInstalled_blocksWithActionableMessage() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(available = true)
        val skillMissingShopee = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(false, null) }
        )
        val request = ProductSearchRequest(query = "t-shirt", maxPrice = 100_000L)

        val result = skillMissingShopee.execute(request)
        assertEquals(TaskOutcome.BLOCKED, result.outcome)
        assertTrue(result.summaryVi.contains("Chưa cài đặt ứng dụng Shopee"))
    }

    @Test
    fun execute_delayedForegroundTimeout_terminatesCleanly() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(available = true, appForeground = false)
        val skillTimeout = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        val request = ProductSearchRequest(
            query = "t-shirt",
            maxPrice = 100_000L,
            executionLimits = ShoppingExecutionLimits(maxTimeMs = 500L)
        )

        val result = skillTimeout.execute(request)
        assertEquals(TaskOutcome.BLOCKED, result.outcome)
        assertTrue(result.summaryVi.contains("Quá thời gian chờ"))
    }

    @Test
    fun execute_emptyNodeTreeAndNoCapture_blocksWithCapturePermissionGuidance() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(
            available = true,
            appForeground = true,
            activePackage = "com.shopee.vn",
            hierarchy = ""
        )
        val skillNoCapture = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            captureServiceChecker = { false },
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        val request = ProductSearchRequest(query = "t-shirt", maxPrice = 100_000L)

        val result = skillNoCapture.execute(request)
        assertEquals(TaskOutcome.BLOCKED, result.outcome)
        assertTrue(result.summaryVi.contains("Bật dịch vụ màn hình"))
    }

    @Test
    fun budgetBoundary_strictlyBelow_excludesExactBudgetAmount() {
        val candidates = listOf(
            ProductCandidate(
                title = "Áo thun nam basic 100k",
                price = 100_000L,
                checkpointX = 100f,
                checkpointY = 200f
            ),
            ProductCandidate(
                title = "Áo thun nam rẻ 95k",
                price = 95_000L,
                checkpointX = 100f,
                checkpointY = 400f
            )
        )

        val requestStrict = ProductSearchRequest(
            query = "áo thun",
            maxPrice = 100_000L,
            budgetBoundary = BudgetBoundary.STRICTLY_BELOW
        )

        val rankedStrict = ranker.rank(candidates, requestStrict)
        val eligibleStrict = rankedStrict.filter { it.isEligible }
        assertEquals(1, eligibleStrict.size)
        assertEquals("Áo thun nam rẻ 95k", eligibleStrict[0].candidate.title)

        val requestInclusive = ProductSearchRequest(
            query = "áo thun",
            maxPrice = 100_000L,
            budgetBoundary = BudgetBoundary.INCLUSIVE_MAX
        )

        val rankedInclusive = ranker.rank(candidates, requestInclusive)
        val eligibleInclusive = rankedInclusive.filter { it.isEligible }
        assertEquals(2, eligibleInclusive.size)
    }

    @Test
    fun extractProductCandidates_parsesWithoutFabricatingRatingsOrReviews() {
        val nodeDump = """
            [#1] View bounds=(0,0,1080,2400) center=(540,1200) [scrollable]
            [#2] TextView text="Tai nghe Bluetooth True Wireless F9 chống nước" bounds=(50,300,500,450) center=(275,375)
            [#3] TextView text="₫129.000" bounds=(50,460,250,510) center=(150,485)
            [#4] TextView text="Đã bán 1,5k" bounds=(260,460,400,510) center=(330,485)
            [#5] TextView text="Yêu thích" bounds=(50,250,150,290) center=(100,270)
        """.trimIndent()

        val candidates = skill.extractProductCandidates(nodeDump)
        assertEquals(1, candidates.size)

        val candidate = candidates[0]
        assertEquals("Tai nghe Bluetooth True Wireless F9 chống nước", candidate.title)
        assertEquals(129_000L, candidate.price)
        assertNull("Rating must not be fabricated when not observed", candidate.rating)
        assertNull("Review count must not be fabricated when not observed", candidate.reviewCount)
        assertEquals(1500, candidate.salesCount)
        assertEquals(275f, candidate.checkpointX)
        assertEquals(375f, candidate.checkpointY)
        assertTrue(candidate.isPreferred)
        assertFalse(candidate.isMall)
        assertEquals("accessibility", candidate.source)
    }

    @Test
    fun extractCandidatesFromOcr_extractsProductCardsWithCheckpointsWhenNodeDumpIsEmpty() {
        val ocrBlocks = listOf(
            createOcrBlock("Tìm kiếm", 100f, 90f, 400f, 160f),
            createOcrBlock("Bộ lọc", 900f, 90f, 1050f, 160f),

            createOcrBlock("Mall", 40f, 200f, 120f, 240f),
            createOcrBlock("Tai nghe Chụp Tai Bluetooth Sony WH-1000XM4 Chính Hãng", 40f, 250f, 500f, 400f),
            createOcrBlock("₫5.990.000", 40f, 410f, 250f, 460f),
            createOcrBlock("Đã bán 3,2k", 260f, 410f, 450f, 460f),
            createOcrBlock("4.9", 460f, 410f, 500f, 460f),

            createOcrBlock("Tai nghe nhét tai Baseus Bowie E3 không dây", 550f, 250f, 1020f, 400f),
            createOcrBlock("350.000 đ", 550f, 410f, 750f, 460f),
            createOcrBlock("Đã bán 850", 760f, 410f, 950f, 460f)
        )

        val candidates = skill.extractCandidatesFromOcr(ocrBlocks)
        assertEquals(2, candidates.size)

        val item1 = candidates[0]
        assertEquals("Tai nghe Chụp Tai Bluetooth Sony WH-1000XM4 Chính Hãng", item1.title)
        assertEquals(5_990_000L, item1.price)
        assertEquals(4.9f, item1.rating)
        assertNull(item1.reviewCount)
        assertEquals(3200, item1.salesCount)
        assertTrue(item1.isMall)
        assertEquals(270f, item1.checkpointX)
        assertEquals(325f, item1.checkpointY)
        assertEquals("ocr", item1.source)

        val item2 = candidates[1]
        assertEquals("Tai nghe nhét tai Baseus Bowie E3 không dây", item2.title)
        assertEquals(350_000L, item2.price)
        assertNull("Item 2 rating not present in OCR; must be null", item2.rating)
        assertNull(item2.reviewCount)
        assertEquals(850, item2.salesCount)
        assertFalse(item2.isMall)
        assertEquals(785f, item2.checkpointX)
        assertEquals(325f, item2.checkpointY)
        assertEquals("ocr", item2.source)
    }

    @Test
    fun endToEndRanking_withOcrCandidatesAndBudgetConstraints() {
        val ocrBlocks = listOf(
            createOcrBlock("Tai nghe Bluetooth giá rẻ A", 40f, 250f, 500f, 400f),
            createOcrBlock("190.000 đ", 40f, 410f, 250f, 460f),

            createOcrBlock("Tai nghe Bluetooth cao cấp B", 550f, 250f, 1020f, 400f),
            createOcrBlock("650.000 đ", 550f, 410f, 750f, 460f)
        )

        val candidates = skill.extractCandidatesFromOcr(ocrBlocks)
        assertEquals(2, candidates.size)

        val request = ProductSearchRequest(
            query = "tai nghe bluetooth",
            maxPrice = 500_000L,
            budgetBoundary = BudgetBoundary.STRICTLY_BELOW
        )

        val best = ranker.selectBestMatch(candidates, request)
        assertNotNull(best)
        assertEquals("Tai nghe Bluetooth giá rẻ A", best!!.candidate.title)
        assertEquals(190_000L, best.candidate.price)
        assertNull(best.candidate.rating)
    }

    @Test
    fun execute_searchFieldNotFocused_neverTypesOrSubmits_returnsFailed() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(
            available = true,
            appForeground = true,
            searchFocused = false, // Never focused!
            hierarchy = "[#1] View bounds=(0,0,1080,2400) center=(540,1200)"
        )
        val skillUnfocused = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        val request = ProductSearchRequest(
            query = "t-shirt",
            maxPrice = 100_000L,
            executionLimits = ShoppingExecutionLimits(maxTimeMs = 5000L)
        )

        val result = skillUnfocused.execute(request)
        assertEquals(TaskOutcome.FAILED, result.outcome)
        assertTrue(result.summaryVi.contains("Không thể tìm hoặc kích hoạt ô tìm kiếm"))
        assertFalse("Must never attempt typing when search field is not focused (R1)", fakeBridge.typeCalled)
        assertFalse("Must never submit when search field is not focused (R1)", fakeBridge.searchSubmittedCalled)
    }

    @Test
    fun execute_typingVerificationFails_neverSubmits_returnsFailed() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(
            available = true,
            appForeground = true,
            searchFocused = true,
            overrideTextOnType = false, // Keeps editable text empty or stale
            currentEditableText = "unrelated old query",
            hierarchy = "[#1] View bounds=(0,0,1080,2400) center=(540,1200)"
        )
        val skillTypingFailed = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        val request = ProductSearchRequest(
            query = "t-shirt",
            maxPrice = 100_000L,
            executionLimits = ShoppingExecutionLimits(maxTimeMs = 5000L)
        )

        val result = skillTypingFailed.execute(request)
        assertEquals(TaskOutcome.FAILED, result.outcome)
        assertTrue(result.summaryVi.contains("Không thể xác nhận đã nhập đúng từ khóa"))
        assertTrue("Typing must have been attempted", fakeBridge.typeCalled)
        assertFalse("Must never submit when typed query cannot be verified (R2)", fakeBridge.searchSubmittedCalled)
    }

    @Test
    fun execute_resultScreenOnlyHasGenericPrice_neverExtractsCandidates_returnsFailed() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(
            available = true,
            appForeground = true,
            searchFocused = true,
            typed = true,
            currentEditableText = "t-shirt",
            // Stale screen with only "Giá" and no "Bộ lọc" / sort tabs
            hierarchy = """
                [#1] View bounds=(0,0,1080,2400)
                [#2] TextView text="Giá tốt hôm nay" bounds=(50,300,500,450)
            """.trimIndent()
        )
        val skillStaleScreen = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        val request = ProductSearchRequest(
            query = "t-shirt",
            maxPrice = 100_000L,
            executionLimits = ShoppingExecutionLimits(maxTimeMs = 8000L)
        )

        val result = skillStaleScreen.execute(request)
        assertEquals(TaskOutcome.FAILED, result.outcome)
        assertTrue(result.summaryVi.contains("Hết thời gian chờ tải kết quả tìm kiếm"))
        assertTrue(result.inspectedCandidates.isEmpty())
    }

    @Test
    fun execute_resultScreenBlockedByCaptchaOrLogin_returnsBlocked() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(
            available = true,
            appForeground = true,
            searchFocused = true,
            typed = true,
            currentEditableText = "t-shirt",
            hierarchy = """
                [#1] View bounds=(0,0,1080,2400)
                [#2] TextView text="Xác minh bảo mật để tiếp tục" bounds=(50,300,500,450)
                [#3] Button text="Mã xác thực" bounds=(50,500,500,600)
            """.trimIndent()
        )
        val skillCaptcha = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        val request = ProductSearchRequest(query = "t-shirt", maxPrice = 100_000L)

        val result = skillCaptcha.execute(request)
        assertEquals(TaskOutcome.BLOCKED, result.outcome)
        assertTrue(result.summaryVi.contains("xác minh bảo mật") || result.summaryVi.contains("đăng nhập"))
    }

    @Test
    fun execute_resultScreenShowsEmptyResults_returnsNoMatch() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(
            available = true,
            appForeground = true,
            searchFocused = true,
            typed = true,
            currentEditableText = "t-shirt",
            hierarchy = """
                [#1] View bounds=(0,0,1080,2400)
                [#2] TextView text="Rất tiếc, không tìm thấy kết quả nào" bounds=(50,300,500,450)
            """.trimIndent()
        )
        val skillEmpty = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        val request = ProductSearchRequest(query = "t-shirt", maxPrice = 100_000L)

        val result = skillEmpty.execute(request)
        assertEquals(TaskOutcome.NO_MATCH, result.outcome)
        assertTrue(result.summaryVi.contains("Không tìm thấy kết quả nào"))
    }

    @Test
    fun execute_maxPhysicalActionsExhausted_terminatesWithBudgetExhausted() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(
            available = true,
            appForeground = true,
            searchFocused = true,
            typed = true,
            currentEditableText = "t-shirt"
        )
        val skillExhausted = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        // Set maxUiActions to 1, so after clicking search box, action budget is exhausted
        val request = ProductSearchRequest(
            query = "t-shirt",
            maxPrice = 100_000L,
            executionLimits = ShoppingExecutionLimits(maxUiActions = 1)
        )

        val result = skillExhausted.execute(request)
        assertEquals(TaskOutcome.BUDGET_EXHAUSTED, result.outcome)
        assertTrue(result.summaryVi.contains("Đã vượt quá số bước thao tác tối đa"))
        assertFalse("Typing must not be attempted after budget exhaustion (R4)", fakeBridge.typeCalled)
    }

    @Test
    fun execute_whenCoroutineCancelled_terminatesImmediately() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(
            available = true,
            appForeground = true,
            searchFocused = true,
            typed = true,
            currentEditableText = "t-shirt"
        )
        val skillToCancel = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        val request = ProductSearchRequest(query = "t-shirt", maxPrice = 100_000L)

        val job = launch {
            kotlinx.coroutines.currentCoroutineContext().cancel()
            skillToCancel.execute(request)
        }
        job.join()
        assertTrue("Job must be cancelled", job.isCancelled)
        assertFalse("Typing must not be called after cancellation (R7)", fakeBridge.typeCalled)
    }

    @Test
    fun execute_detailPagePriceExceedsMaxPrice_failsVerification() = runBlocking {
        val fakeBridge = FakeAccessibilityBridge(
            available = true,
            appForeground = true,
            searchFocused = true,
            typed = true,
            currentEditableText = "t-shirt",
            hierarchy = """
                [#1] View bounds=(0,0,1080,2400)
                [#2] TextView text="Bộ lọc" bounds=(50,100,200,200)
                [#3] TextView text="Bán chạy" bounds=(220,100,400,200)
                [#4] TextView text="Áo thun nam basic cotton" bounds=(50,300,500,450)
                [#5] TextView text="₫99.000" bounds=(50,460,300,550)
                [#6] TextView text="Chi tiết sản phẩm" bounds=(50,600,400,700)
                [#7] TextView text="₫250.000" bounds=(50,710,300,800)
            """.trimIndent()
        )
        val skillPriceCheck = ShopeeShoppingSkill(
            accessibilityBridge = fakeBridge,
            appOpener = { Pair(true, "com.shopee.vn") }
        )
        val request = ProductSearchRequest(query = "t-shirt", maxPrice = 100_000L)

        val result = skillPriceCheck.execute(request)
        assertEquals(TaskOutcome.FAILED, result.outcome)
        assertTrue(result.summaryVi.contains("Không thể xác nhận đã mở trang chi tiết"))
    }
}
