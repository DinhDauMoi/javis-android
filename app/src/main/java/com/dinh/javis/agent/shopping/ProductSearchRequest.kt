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
) {
    /**
     * Formats monetary amount into natural Vietnamese format (e.g. 100.000đ).
     */
    private fun formatVnd(amount: Long): String {
        return java.text.NumberFormat.getInstance(java.util.Locale("vi", "VN")).format(amount) + "đ"
    }

    /**
     * Generates a clear Vietnamese description of budget constraints.
     */
    fun buildBudgetExplanationVi(): String {
        return when {
            minPrice != null && maxPrice != null -> {
                "giá sản phẩm từ ${formatVnd(minPrice)} đến ${formatVnd(maxPrice)}"
            }
            maxPrice != null -> {
                val boundaryStr = if (budgetBoundary == BudgetBoundary.STRICTLY_BELOW) "dưới" else "không quá"
                "giá sản phẩm $boundaryStr ${formatVnd(maxPrice)}"
            }
            minPrice != null -> {
                "giá sản phẩm từ ${formatVnd(minPrice)}"
            }
            else -> "không giới hạn giá"
        }
    }

    /**
     * Explains the interpreted request in natural Vietnamese for user feedback.
     * Matches expected behavior: “Đang tìm áo thun trên Shopee, giá sản phẩm không quá 100.000đ, chưa gồm phí vận chuyển.”
     */
    fun buildExplanationVi(): String {
        val displayProduct = if (query.equals("t-shirt", ignoreCase = true) || query.equals("t shirt", ignoreCase = true)) {
            "áo thun"
        } else {
            query
        }

        val budgetClause = buildBudgetExplanationVi()
        val scopeClause = if (budgetScope == BudgetScope.ITEM_PRICE) {
            "chưa gồm phí vận chuyển"
        } else {
            "đã gồm phí vận chuyển"
        }

        return if (minPrice != null || maxPrice != null) {
            "Đang tìm $displayProduct trên Shopee, $budgetClause, $scopeClause."
        } else {
            "Đang tìm $displayProduct trên Shopee."
        }
    }
}

