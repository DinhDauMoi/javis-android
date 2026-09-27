package com.dinh.javis.agent.shopping

import kotlin.math.ln

/**
 * Result of evaluating a single product candidate.
 */
data class CandidateEvaluation(
    val candidate: ProductCandidate,
    val isEligible: Boolean,
    val score: Float,
    val reasonVi: String
)

/**
 * Deterministic product evaluation and preference ranker (Section 7).
 * Guarantees that hard constraints (budget, specifications, exclusions)
 * are strictly enforced without relying on model completion claims.
 */
class ProductRanker {

    /**
     * Evaluates a list of candidates against the search request, filters by eligibility,
     * and returns candidates sorted in descending order of preference score.
     */
    fun rank(
        candidates: List<ProductCandidate>,
        request: ProductSearchRequest
    ): List<CandidateEvaluation> {
        return candidates.map { evaluateCandidate(it, request) }
            .sortedWith(
                compareByDescending<CandidateEvaluation> { it.isEligible }
                    .thenByDescending { it.score }
            )
    }

    /**
     * Returns the best eligible candidate, or null if no candidate qualifies.
     */
    fun selectBestMatch(
        candidates: List<ProductCandidate>,
        request: ProductSearchRequest
    ): CandidateEvaluation? {
        val ranked = rank(candidates, request)
        return ranked.firstOrNull { it.isEligible }
    }

    /**
     * Evaluates a single candidate for eligibility and assigns a deterministic score.
     */
    fun evaluateCandidate(
        candidate: ProductCandidate,
        request: ProductSearchRequest
    ): CandidateEvaluation {
        val titleLower = candidate.title.lowercase()

        // 1. Hard check: Excluded terms
        for (excluded in request.excludedTerms) {
            val exLower = excluded.lowercase().trim()
            if (exLower.isNotBlank() && titleLower.contains(exLower)) {
                return CandidateEvaluation(
                    candidate = candidate,
                    isEligible = false,
                    score = 0f,
                    reasonVi = "Bị loại do chứa từ khóa loại trừ: \"$excluded\""
                )
            }
        }

        // 2. Hard check: Required specifications
        for (spec in request.requiredSpecifications) {
            val specLower = spec.lowercase().trim()
            val hasInTitle = titleLower.contains(specLower)
            val hasInSpecs = candidate.specifications.any { it.lowercase().contains(specLower) }
            if (!hasInTitle && !hasInSpecs) {
                return CandidateEvaluation(
                    candidate = candidate,
                    isEligible = false,
                    score = 0f,
                    reasonVi = "Không đáp ứng yêu cầu kỹ thuật: \"$spec\""
                )
            }
        }

        // 3. Hard check: Budget limits
        val effectivePrice = candidate.effectivePrice
        if (effectivePrice != null) {
            // Check minimum price
            if (request.minPrice != null && effectivePrice < request.minPrice) {
                return CandidateEvaluation(
                    candidate = candidate,
                    isEligible = false,
                    score = 0f,
                    reasonVi = "Giá (${formatVnd(effectivePrice)}) thấp hơn mức tối thiểu (${formatVnd(request.minPrice)})"
                )
            }

            // Check maximum price according to boundary
            if (request.maxPrice != null) {
                val exceeds = when (request.budgetBoundary) {
                    BudgetBoundary.STRICTLY_BELOW -> effectivePrice >= request.maxPrice
                    BudgetBoundary.INCLUSIVE_MAX -> effectivePrice > request.maxPrice
                }
                if (exceeds) {
                    val boundaryStr = if (request.budgetBoundary == BudgetBoundary.STRICTLY_BELOW) "dưới" else "tối đa"
                    return CandidateEvaluation(
                        candidate = candidate,
                        isEligible = false,
                        score = 0f,
                        reasonVi = "Giá (${formatVnd(effectivePrice)}) vượt quá ngân sách ($boundaryStr ${formatVnd(request.maxPrice)})"
                    )
                }
            }
        } else if (request.maxPrice != null) {
            // Price is completely unknown but budget is required
            return CandidateEvaluation(
                candidate = candidate,
                isEligible = false,
                score = 0f,
                reasonVi = "Không xác định được giá để đối chiếu ngân sách"
            )
        }

        // 4. Deterministic preference scoring for eligible candidates
        var score = 10.0f

        // A. Rating & Review volume score
        val rating = candidate.rating ?: 4.0f
        val reviews = candidate.reviewCount ?: 0
        // Weight rating scaled by log of review volume: max 50 points
        val reviewFactor = (ln((reviews + 1).toDouble()) / ln(10000.0)).toFloat().coerceIn(0.1f, 1.0f)
        score += (rating / 5.0f) * 40.0f * reviewFactor

        // B. Seller reputation bonus
        if (candidate.isMall) {
            score += 25.0f // Shopee Mall official shop
        } else if (candidate.isPreferred) {
            score += 15.0f // Shop Yêu thích / Yêu thích+
        }

        // C. Price score: closer to budget target (or economical) gets slight preference
        if (effectivePrice != null && request.maxPrice != null && request.maxPrice > 0) {
            val budgetRatio = (effectivePrice.toDouble() / request.maxPrice.toDouble()).toFloat()
            // Prefer products within 60% - 95% of budget
            val priceFitness = 1.0f - (budgetRatio - 0.8f).coerceAtLeast(0f)
            score += priceFitness * 15.0f
        }

        // D. Evidence certainty and penalties
        if (candidate.isPriceConditional) {
            score -= 10.0f // Penalty for conditional voucher/installment price
        }
        if (candidate.isSponsored) {
            // Sponsored is not evidence of quality
            score -= 5.0f
        }
        score *= candidate.extractionConfidence

        val priceStr = effectivePrice?.let { formatVnd(it) } ?: "chưa rõ giá"
        val ratingStr = candidate.rating?.let { "★ $it" } ?: "chưa có đánh giá"
        val sellerBadge = if (candidate.isMall) "Mall" else if (candidate.isPreferred) "Yêu thích" else "Thường"

        return CandidateEvaluation(
            candidate = candidate,
            isEligible = true,
            score = score,
            reasonVi = "Phù hợp yêu cầu: Giá $priceStr, $ratingStr ($sellerBadge)"
        )
    }

    private fun formatVnd(amount: Long): String {
        return "%,d đ".format(amount).replace(',', '.')
    }
}
