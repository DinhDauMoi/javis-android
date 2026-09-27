package com.dinh.javis.agent.shopping

import android.graphics.Rect
import com.dinh.javis.vision.OcrBlock
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
        // Simulates Canvas / WebView rendered Shopee search grid with empty accessibility node text
        val ocrBlocks = listOf(
            // Header / UI buttons (should be filtered out)
            createOcrBlock("Tìm kiếm", 100f, 90f, 400f, 160f),
            createOcrBlock("Bộ lọc", 900f, 90f, 1050f, 160f),

            // Product 1 Card
            createOcrBlock("Mall", 40f, 200f, 120f, 240f),
            createOcrBlock("Tai nghe Chụp Tai Bluetooth Sony WH-1000XM4 Chính Hãng", 40f, 250f, 500f, 400f),
            createOcrBlock("₫5.990.000", 40f, 410f, 250f, 460f),
            createOcrBlock("Đã bán 3,2k", 260f, 410f, 450f, 460f),
            createOcrBlock("4.9", 460f, 410f, 500f, 460f),

            // Product 2 Card
            createOcrBlock("Tai nghe nhét tai Baseus Bowie E3 không dây", 550f, 250f, 1020f, 400f),
            createOcrBlock("350.000 đ", 550f, 410f, 750f, 460f),
            createOcrBlock("Đã bán 850", 760f, 410f, 950f, 460f)
        )

        val candidates = skill.extractCandidatesFromOcr(ocrBlocks)
        assertEquals(2, candidates.size)

        // Product 1
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

        // Product 2
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
}
