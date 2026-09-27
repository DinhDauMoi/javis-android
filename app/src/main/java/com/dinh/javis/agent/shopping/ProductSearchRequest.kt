package com.dinh.javis.agent.shopping

/**
 * Budget boundary type for shopping requests.
 */
enum class BudgetBoundary {
    STRICTLY_BELOW,
    INCLUSIVE_MAX
}

/**
 * Budget scope type (item price vs delivered total).
 */
enum class BudgetScope {
    ITEM_PRICE,
    DELIVERED_TOTAL
}

/**
 * Execution limits for shopping tasks.
 */
data class ShoppingExecutionLimits(
    val maxTimeMs: Long = 180_000L,
    val maxUiActions: Int = 30,
    val maxSearchResultPages: Int = 3,
    val maxCandidates: Int = 8,
    val maxDetailedInspections: Int = 3,
    val maxModelCalls: Int = 8,
    val maxNoProgressRetries: Int = 2
)

/**
 * Structured, normalized shopping request (Section 4).
 */
data class ProductSearchRequest(
    val targetApp: String = "com.shopee.vn",
    val query: String,
    val requiredSpecifications: List<String> = emptyList(),
    val minPrice: Long? = null,
    val maxPrice: Long? = null,
    val budgetBoundary: BudgetBoundary = BudgetBoundary.INCLUSIVE_MAX,
    val budgetScope: BudgetScope = BudgetScope.ITEM_PRICE,
    val qualityPreferences: List<String> = emptyList(),
    val excludedTerms: List<String> = emptyList(),
    val executionLimits: ShoppingExecutionLimits = ShoppingExecutionLimits()
)
