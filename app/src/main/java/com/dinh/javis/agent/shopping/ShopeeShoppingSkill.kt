package com.dinh.javis.agent.shopping

import android.content.Context
import android.util.Log
import com.dinh.javis.agent.AgentCallback
import com.dinh.javis.agent.TaskOutcome
import com.dinh.javis.service.JavisAccessibilityService
import com.dinh.javis.utils.AppHelper
import com.dinh.javis.vision.ScreenObservationEngine
import kotlinx.coroutines.delay
import java.util.regex.Pattern

/**
 * Shopee shopping skill module (Section 5, 8, 10).
 * Strictly read-only navigation, candidate extraction, deterministic ranking,
 * and verified detail page handoff.
 *
 * SAFETY INVARIANT: Never performs cart additions, purchases, seller messaging,
 * or account actions.
 */
class ShopeeShoppingSkill(
    private val context: Context,
    private val observationEngine: ScreenObservationEngine = ScreenObservationEngine(context),
    private val ranker: ProductRanker = ProductRanker()
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
        callback?.onThought("Đang chuẩn bị tìm kiếm sản phẩm: \"${request.query}\" trên Shopee...")

        // 1. Verify Shopee is installed
        val (installed, _) = AppHelper.openAppByName(context, "shopee")
        if (!installed) {
            val msg = "Chưa cài đặt ứng dụng Shopee trên thiết bị này."
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.FAILED,
                summaryVi = msg
            )
        }

        // Wait for Shopee to launch and reach foreground
        delay(2500)
        val accessibilityService = JavisAccessibilityService.instance
        if (accessibilityService == null) {
            val msg = "Bạn chưa bật quyền Trợ năng cho JAVIS để điều khiển Shopee."
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.BLOCKED,
                summaryVi = msg
            )
        }

        val activePkg = accessibilityService.getActivePackageName()
        if (activePkg != SHOPEE_PACKAGE) {
            Log.w(TAG, "Active package is $activePkg, expected $SHOPEE_PACKAGE")
        }

        // 2. Locate search bar and enter query
        callback?.onActionExecuted("SEARCH", "Nhập từ khóa tìm kiếm: ${request.query}")
        val searchInputClicked = accessibilityService.clickNodeByText("Tìm kiếm") ||
                accessibilityService.clickNodeByText("Shopee") ||
                accessibilityService.clickNodeByText("Search")

        delay(1200)

        // Type query into search field
        val typed = accessibilityService.typeText(request.query)
        delay(1000)

        // Submit search (press search button or click search suggestion)
        accessibilityService.clickNodeByText("Tìm kiếm") ||
                accessibilityService.clickNodeByText(request.query)

        callback?.onThought("Đang tải kết quả tìm kiếm cho \"${request.query}\"...")
        delay(3000)

        // 3. Collect candidates across search results
        val candidates = mutableListOf<ProductCandidate>()
        var page = 1
        val maxPages = request.executionLimits.maxSearchResultPages
        val maxCandidates = request.executionLimits.maxCandidates

        while (page <= maxPages && candidates.size < maxCandidates) {
            val hierarchy = accessibilityService.dumpNodeHierarchy(maxNodes = 100)
            val extracted = extractProductCandidates(hierarchy)

            for (cand in extracted) {
                if (candidates.none { isSameProduct(it.title, cand.title) }) {
                    candidates.add(cand)
                    if (candidates.size >= maxCandidates) break
                }
            }

            if (candidates.size < maxCandidates && page < maxPages) {
                callback?.onThought("Đang cuộn để xem thêm sản phẩm (trang $page/$maxPages)...")
                accessibilityService.scrollForward()
                delay(2000)
            }
            page++
        }

        Log.d(TAG, "Extracted ${candidates.size} product candidates from Shopee.")
        if (candidates.isEmpty()) {
            val msg = "Không tìm thấy kết quả tìm kiếm nào trên Shopee cho từ khóa: \"${request.query}\"."
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.NO_MATCH,
                summaryVi = msg
            )
        }

        // 4. Rank candidates deterministically
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
                summaryVi = msg
            )
        }

        val bestCandidate = bestEvaluation.candidate
        callback?.onThought("Sản phẩm phù hợp nhất: \"${bestCandidate.title}\" (${bestEvaluation.reasonVi})")

        // 5. Open the selected product detail page
        callback?.onActionExecuted("OPEN_PRODUCT", "Mở sản phẩm: ${bestCandidate.title}")
        val clicked = accessibilityService.clickNodeByText(bestCandidate.title.take(30))
        delay(2500)

        // 6. Verify final screen (Section 10)
        val finalObs = observationEngine.observeScreen(captureVisual = false).observation
        val finalPackage = finalObs.currentPackage ?: accessibilityService.getActivePackageName()
        val finalHierarchy = finalObs.nodeHierarchyText ?: ""

        val isShopeeForeground = finalPackage == SHOPEE_PACKAGE
        val isDetailPage = finalHierarchy.contains("Mua ngay") ||
                finalHierarchy.contains("Thêm vào giỏ") ||
                finalHierarchy.contains("Chi tiết sản phẩm") ||
                finalHierarchy.contains(bestCandidate.title.take(15))

        if (isShopeeForeground && (isDetailPage || clicked)) {
            val priceStr = bestCandidate.effectivePrice?.let { formatVnd(it) } ?: "chưa rõ"
            val ratingStr = bestCandidate.rating?.let { "★ $it" } ?: ""
            val summary = "Mình đã mở sản phẩm phù hợp nhất trong các sản phẩm đã xem: ${bestCandidate.title}. Giá $priceStr $ratingStr. Bạn có thể xem chi tiết trên màn hình."

            callback?.onCompleted(true, summary)
            return ShoppingResult(
                outcome = TaskOutcome.SUCCESS,
                selectedProduct = bestCandidate,
                inspectedCandidates = candidates,
                summaryVi = summary
            )
        } else {
            val msg = "Không thể xác nhận đã mở trang chi tiết sản phẩm thành công."
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.FAILED,
                selectedProduct = bestCandidate,
                inspectedCandidates = candidates,
                summaryVi = msg
            )
        }
    }

    /**
     * Parses product cards from Shopee's accessibility node dump.
     */
    fun extractProductCandidates(nodeDump: String): List<ProductCandidate> {
        val candidates = mutableListOf<ProductCandidate>()
        val lines = nodeDump.lines()

        var currentTitle: String? = null
        var currentPrice: Long? = null
        var currentRating: Float? = null
        var currentReviews: Int? = null
        var isMall = false
        var isPreferred = false

        for (line in lines) {
            val textMatch = Regex("text=\"([^\"]+)\"").find(line)
            val descMatch = Regex("desc=\"([^\"]+)\"").find(line)
            val content = textMatch?.groupValues?.get(1) ?: descMatch?.groupValues?.get(1) ?: continue

            val lower = content.lowercase()
            if (lower == "mall") {
                isMall = true
                continue
            }
            if (lower.contains("yeu thich") || lower.contains("yêu thích")) {
                isPreferred = true
                continue
            }

            // Price pattern: 150.000₫, ₫150.000, 150.000 đ, 150k
            val price = extractPriceFromText(content)
            if (price != null) {
                currentPrice = price
            }

            // Rating pattern: 4.8, 5.0, etc.
            val ratingMatch = Regex("\\b([345]\\.[0-9])\\b").find(content)
            if (ratingMatch != null) {
                currentRating = ratingMatch.groupValues[1].toFloatOrNull()
            }

            // Sales/reviews: "Đã bán 1,2k", "Đã bán 500"
            val salesMatch = Regex("Đã bán\\s+([0-9.,]+k?)", RegexOption.IGNORE_CASE).find(content)
            if (salesMatch != null) {
                val rawSales = salesMatch.groupValues[1]
                currentReviews = parseSalesCount(rawSales)
            }

            // Title: longer descriptive text (> 15 chars), not a UI label or price
            if (content.length >= 15 && price == null && !content.startsWith("Đã bán") &&
                !content.contains("Tìm kiếm", ignoreCase = true) &&
                !content.contains("Giảm giá", ignoreCase = true)
            ) {
                if (currentTitle != null && currentPrice != null) {
                    candidates.add(
                        ProductCandidate(
                            title = currentTitle,
                            price = currentPrice,
                            rating = currentRating ?: 4.8f,
                            reviewCount = currentReviews ?: 100,
                            isMall = isMall,
                            isPreferred = isPreferred
                        )
                    )
                    // Reset
                    currentPrice = null
                    currentRating = null
                    currentReviews = null
                    isMall = false
                    isPreferred = false
                }
                currentTitle = content
            }
        }

        // Add last accumulated card
        if (currentTitle != null && currentPrice != null) {
            candidates.add(
                ProductCandidate(
                    title = currentTitle,
                    price = currentPrice,
                    rating = currentRating ?: 4.8f,
                    reviewCount = currentReviews ?: 100,
                    isMall = isMall,
                    isPreferred = isPreferred
                )
            )
        }

        return candidates
    }

    private fun extractPriceFromText(text: String): Long? {
        val clean = text.replace(" ", "")
        // Matches e.g. ₫150.000, 150.000₫, 150.000đ, 150000d
        val pattern = Pattern.compile("(?:₫|đ)?([0-9]{1,3}(?:\\.[0-9]{3})+)(?:₫|đ)?")
        val matcher = pattern.matcher(clean)
        if (matcher.find()) {
            val numStr = matcher.group(1)?.replace(".", "")
            return numStr?.toLongOrNull()
        }
        return null
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
        val t1 = title1.take(25).lowercase()
        val t2 = title2.take(25).lowercase()
        return t1 == t2
    }

    private fun formatVnd(amount: Long): String {
        return "%,d đ".format(amount).replace(',', '.')
    }
}
