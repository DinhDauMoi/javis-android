package com.dinh.javis.agent.shopping

/**
 * Observed product candidate with provenance, pricing details, and uncertainty tracking (Section 6).
 */
data class ProductCandidate(
    val title: String,
    val productId: String? = null,
    val sellerName: String? = null,
    val variant: String? = null,
    val price: Long? = null,
    val minPrice: Long? = null,
    val maxPrice: Long? = null,
    val isPriceConditional: Boolean = false,
    val priceConditionDetails: String? = null,
    val rating: Float? = null,
    val reviewCount: Int? = null,
    val specifications: List<String> = emptyList(),
    val isSponsored: Boolean = false,
    val isMall: Boolean = false,
    val isPreferred: Boolean = false,
    val observedAtMs: Long = System.currentTimeMillis(),
    val source: String = "accessibility",
    val extractionConfidence: Float = 1.0f,
    val missingFields: List<String> = emptyList()
) {
    /**
     * Resolves the effective payable price.
     * Returns exact price, or minPrice if in a range, or null if unknown.
     */
    val effectivePrice: Long?
        get() = price ?: minPrice
}
