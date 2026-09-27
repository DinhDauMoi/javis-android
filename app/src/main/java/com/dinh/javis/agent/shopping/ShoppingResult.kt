package com.dinh.javis.agent.shopping

import com.dinh.javis.agent.TaskOutcome

/**
 * Result of a shopping workflow execution (Section 9).
 */
data class ShoppingResult(
    val outcome: TaskOutcome,
    val selectedProduct: ProductCandidate? = null,
    val inspectedCandidates: List<ProductCandidate> = emptyList(),
    val summaryVi: String,
    val limitationsVi: String? = null
)
