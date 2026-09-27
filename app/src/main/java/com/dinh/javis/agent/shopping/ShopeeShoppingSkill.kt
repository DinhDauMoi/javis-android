package com.dinh.javis.agent.shopping

import android.content.Context
import android.util.Log
import com.dinh.javis.agent.AgentCallback
import com.dinh.javis.agent.TaskOutcome
import com.dinh.javis.service.JavisAccessibilityService
import com.dinh.javis.utils.AppHelper
import com.dinh.javis.utils.TextNormalizer
import com.dinh.javis.vision.OcrBlock
import com.dinh.javis.vision.Point2D
import com.dinh.javis.vision.ScreenObservationEngine
import com.dinh.javis.vision.ScreenTargetResolver
import kotlinx.coroutines.delay
import java.util.regex.Pattern

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

        val appContext = context
        if (appContext == null) {
            val msg = "Lỗi hệ thống: Context không khả dụng để thực hiện tác vụ."
            callback?.onCompleted(false, msg)
            return ShoppingResult(
                outcome = TaskOutcome.FAILED,
                summaryVi = msg
            )
        }

        val obsEngine = observationEngine ?: ScreenObservationEngine(appContext)

        // 1. Verify Shopee is installed
        val (installed, _) = AppHelper.openAppByName(appContext, "shopee")
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
        accessibilityService.clickNodeByText("Tìm kiếm") ||
                accessibilityService.clickNodeByText("Shopee") ||
                accessibilityService.clickNodeByText("Search")

        delay(1200)

        // Type query into search field
        accessibilityService.typeText(request.query)
        delay(1000)

        // Submit search (press search button or click search suggestion)
        accessibilityService.clickNodeByText("Tìm kiếm") ||
                accessibilityService.clickNodeByText(request.query)

        callback?.onThought("Đang tải kết quả tìm kiếm cho \"${request.query}\"...")
        delay(3000)

        // 3. Collect candidates across search results
        val candidates = mutableListOf<ProductCandidate>()
        var lastOcrBlocks = listOf<OcrBlock>()
        var page = 1
        val maxPages = request.executionLimits.maxSearchResultPages
        val maxCandidates = request.executionLimits.maxCandidates

        while (page <= maxPages && candidates.size < maxCandidates) {
            val obsResult = obsEngine.observeScreen(captureVisual = false, forceOcr = true)
            val hierarchy = obsResult.observation.nodeHierarchyText
                ?: accessibilityService.dumpNodeHierarchy(maxNodes = 100)
            lastOcrBlocks = obsResult.ocrBlocks

            val extractedNodes = extractProductCandidates(hierarchy)
            val extractedOcr = if (lastOcrBlocks.isNotEmpty()) {
                extractCandidatesFromOcr(lastOcrBlocks)
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

        // 5. Open the selected product detail page (with coordinate fallback)
        callback?.onActionExecuted("OPEN_PRODUCT", "Mở sản phẩm: ${bestCandidate.title}")
        val clickedByText = accessibilityService.clickNodeByText(bestCandidate.title.take(30))

        if (!clickedByText) {
            val tapPoint = if (bestCandidate.checkpointX != null && bestCandidate.checkpointY != null) {
                Point2D(bestCandidate.checkpointX, bestCandidate.checkpointY)
            } else {
                val resolver = ScreenTargetResolver()
                resolver.findProductCardCheckpoint(
                    title = bestCandidate.title,
                    priceText = bestCandidate.effectivePrice?.let { formatVnd(it) },
                    ocrBlocks = lastOcrBlocks
                )
            }

            if (tapPoint != null) {
                Log.d(TAG, "Falling back to coordinate tap at (${tapPoint.x}, ${tapPoint.y})")
                val tapSuccess = accessibilityService.tapAt(tapPoint.x, tapPoint.y)
                Log.d(TAG, "Coordinate tap result: $tapSuccess")
            }
        }
        delay(2500)

        // 6. Verify final screen (Section 10)
        val finalObsResult = obsEngine.observeScreen(captureVisual = false, forceOcr = true)
        val finalObs = finalObsResult.observation
        val finalPackage = finalObs.currentPackage ?: accessibilityService.getActivePackageName()
        val finalHierarchy = finalObs.nodeHierarchyText ?: ""
        val finalOcr = finalObs.ocrText ?: ""
        val combinedEvidence = "$finalHierarchy\n$finalOcr"

        val isShopeeForeground = finalPackage == SHOPEE_PACKAGE
        val hasDetailPageIndicators = combinedEvidence.contains("Mua ngay", ignoreCase = true) ||
                combinedEvidence.contains("Thêm vào giỏ", ignoreCase = true) ||
                combinedEvidence.contains("Chi tiết sản phẩm", ignoreCase = true) ||
                combinedEvidence.contains("Mua với voucher", ignoreCase = true) ||
                combinedEvidence.contains(bestCandidate.title.take(15), ignoreCase = true)

        val isVerifiedDetailPage = isShopeeForeground && hasDetailPageIndicators

        if (isVerifiedDetailPage) {
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
            val msg = "Không thể xác nhận đã mở trang chi tiết sản phẩm trên Shopee."
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
     * Does NOT fabricate default ratings or reviews.
     */
    fun extractProductCandidates(nodeDump: String): List<ProductCandidate> {
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

            // Sales count: "Đã bán 1,2k", "Đã bán 500"
            val salesMatch = Regex("Đã bán\\s+([0-9.,]+k?)", RegexOption.IGNORE_CASE).find(content)
            if (salesMatch != null) {
                val rawSales = salesMatch.groupValues[1]
                currentSales = parseSalesCount(rawSales)
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
                            rating = currentRating,
                            reviewCount = null,
                            salesCount = currentSales,
                            checkpointX = currentCenterX,
                            checkpointY = currentCenterY,
                            isMall = isMall,
                            isPreferred = isPreferred,
                            source = "accessibility"
                        )
                    )
                    // Reset
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

        // Add last accumulated card
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
                    source = "accessibility"
                )
            )
        }

        return candidates
    }

    /**
     * Extracts product candidates directly from ML Kit OCR blocks.
     * Groups titles, prices, ratings, and sales volume by spatial proximity.
     */
    fun extractCandidatesFromOcr(ocrBlocks: List<OcrBlock>): List<ProductCandidate> {
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

            if (block.top > 0 && block.top < 80) continue // Skip status bar area

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
            // Find price block vertically below (within 350px) and horizontally aligned (within 300px)
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
                        source = "ocr"
                    )
                )
            }
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
        val t1 = TextNormalizer.removeAccents(title1.take(25).lowercase()).trim()
        val t2 = TextNormalizer.removeAccents(title2.take(25).lowercase()).trim()
        return t1 == t2
    }

    private fun formatVnd(amount: Long): String {
        return "%,d đ".format(amount).replace(',', '.')
    }
}
