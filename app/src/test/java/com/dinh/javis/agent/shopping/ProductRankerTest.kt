package com.dinh.javis.agent.shopping

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProductRankerTest {

    private lateinit var ranker: ProductRanker

    @Before
    fun setup() {
        ranker = ProductRanker()
    }

    @Test
    fun `ineligible when price exceeds strictly below budget`() {
        val request = ProductSearchRequest(
            query = "tai nghe",
            maxPrice = 500_000L,
            budgetBoundary = BudgetBoundary.STRICTLY_BELOW
        )
        val candidate = ProductCandidate(
            title = "Tai nghe Bluetooth chính hãng",
            price = 500_000L
        )

        val eval = ranker.evaluateCandidate(candidate, request)
        assertFalse("Price equal to maxPrice should be ineligible for STRICTLY_BELOW", eval.isEligible)
    }

    @Test
    fun `eligible when price equals inclusive max budget`() {
        val request = ProductSearchRequest(
            query = "tai nghe",
            maxPrice = 500_000L,
            budgetBoundary = BudgetBoundary.INCLUSIVE_MAX
        )
        val candidate = ProductCandidate(
            title = "Tai nghe Bluetooth chính hãng",
            price = 500_000L
        )

        val eval = ranker.evaluateCandidate(candidate, request)
        assertTrue("Price equal to maxPrice should be eligible for INCLUSIVE_MAX", eval.isEligible)
    }

    @Test
    fun `ineligible when missing required specification`() {
        val request = ProductSearchRequest(
            query = "tai nghe",
            requiredSpecifications = listOf("Bluetooth")
        )
        val candidate = ProductCandidate(
            title = "Tai nghe có dây jack 3.5mm",
            price = 100_000L
        )

        val eval = ranker.evaluateCandidate(candidate, request)
        assertFalse("Should be ineligible when required spec Bluetooth is missing", eval.isEligible)
    }

    @Test
    fun `ineligible when contains excluded terms`() {
        val request = ProductSearchRequest(
            query = "tai nghe",
            excludedTerms = listOf("hàng cũ", "cũ")
        )
        val candidate = ProductCandidate(
            title = "Tai nghe Bluetooth hàng cũ 99%",
            price = 200_000L
        )

        val eval = ranker.evaluateCandidate(candidate, request)
        assertFalse("Should be ineligible when containing excluded term", eval.isEligible)
    }

    @Test
    fun `selectBestMatch prefers higher rating and Mall seller`() {
        val request = ProductSearchRequest(
            query = "tai nghe",
            maxPrice = 500_000L,
            budgetBoundary = BudgetBoundary.INCLUSIVE_MAX
        )

        val item1 = ProductCandidate(
            title = "Tai nghe thường A",
            price = 450_000L,
            rating = 4.2f,
            reviewCount = 50,
            isMall = false
        )

        val item2 = ProductCandidate(
            title = "Tai nghe Shopee Mall B",
            price = 480_000L,
            rating = 4.9f,
            reviewCount = 2000,
            isMall = true
        )

        val best = ranker.selectBestMatch(listOf(item1, item2), request)
        assertNotNull(best)
        assertEquals("Tai nghe Shopee Mall B", best?.candidate?.title)
    }
}
