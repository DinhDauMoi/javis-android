package com.dinh.javis.agent.shopping

import com.dinh.javis.utils.TextNormalizer
import java.util.regex.Pattern

/**
 * Result of parsing natural language shopping request.
 */
sealed class ShoppingParseResult {
    data class Success(val request: ProductSearchRequest) : ShoppingParseResult()
    data class NeedsInput(val clarificationVi: String) : ShoppingParseResult()
}

/**
 * Parses Vietnamese and English spoken or typed requests into structured [ProductSearchRequest].
 * Handles accented and unaccented text, currency units (k, nghìn, triệu, tr),
 * budget boundaries (dưới vs không quá, under vs max), specifications, and preferences.
 */
object ShoppingTaskParser {

    /**
     * Determines whether the given user input expresses a shopping search intent.
     * Rejects informational questions (e.g., "what is Shopee?", "how do I find a shirt on Shopee?"),
     * quoted examples, and simple app open commands ("mở shopee", "open shopee").
     */
    fun isShoppingIntent(input: String): Boolean {
        if (isInformationalQuery(input)) return false

        val normalized = TextNormalizer.removeAccents(input.lowercase()).trim()
        val hasShopee = Pattern.compile("""\b(?:shopee|sope|shoppe)\b""", Pattern.CASE_INSENSITIVE)
            .matcher(normalized).find()

        // English and Vietnamese action verbs with word boundaries
        val hasSearchAction = Pattern.compile(
            """\b(?:tim|kiem|mua|chon|xem\s+gia|find|search\s+for|search|look\s+for|buy)\b""",
            Pattern.CASE_INSENSITIVE
        ).matcher(normalized).find()

        // Compound: "find ... on shopee", "mở shopee tìm ...", "shopee find ...", "tìm ... trên shopee"
        if (hasShopee && hasSearchAction) return true

        // Explicit shopping phrasing without mentioning Shopee
        if (normalized.startsWith("tim mua ") || normalized.startsWith("tim giup toi ") ||
            normalized.startsWith("buy product ") || normalized.startsWith("search product ") ||
            normalized.contains("san pham phu hop nhat")
        ) {
            return true
        }

        return false
    }

    /**
     * Identifies questions or quoted examples that should remain conversational AI queries.
     */
    private fun isInformationalQuery(text: String): Boolean {
        val trimmed = text.trim()
        // Entirely quoted string example
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length > 2) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length > 2) ||
            (trimmed.startsWith("“") && trimmed.endsWith("”") && trimmed.length > 2)
        ) {
            return true
        }

        val norm = TextNormalizer.removeAccents(trimmed.lowercase())
        val infoPattern = Pattern.compile(
            """\b(?:what\s+is|what's|how\s+to|how\s+do\s+i|how\s+can\s+i|why\s+is|why|explain|tell\s+me\s+about|la\s+gi|nhu\s+the\s+nao|lam\s+sao|huong\s+dan|cach\s+thuc|cach\s+nao|tai\s+sao)\b""",
            Pattern.CASE_INSENSITIVE
        )
        return infoPattern.matcher(norm).find()
    }

    /**
     * Parses the raw natural language input into a [ShoppingParseResult].
     */
    fun parse(rawInput: String): ShoppingParseResult {
        val cleanInput = TextNormalizer.stripFillerWords(rawInput.trim())
        // Narrowly scoped typo correction per plan: t-shurt -> t-shirt
        val correctedInput = normalizeNarrowTypos(cleanInput)
        val normalized = TextNormalizer.removeAccents(correctedInput.lowercase())

        // 1. Detect budget boundary (strictly below vs inclusive max)
        val isStrictlyBelow = Pattern.compile(
            """\b(?:duoi|thap\s+hon|nho\s+hon|it\s+hon|under|below|less\s+than)\b""",
            Pattern.CASE_INSENSITIVE
        ).matcher(normalized).find()
        val budgetBoundary = if (isStrictlyBelow) BudgetBoundary.STRICTLY_BELOW else BudgetBoundary.INCLUSIVE_MAX

        // 2. Parse price bounds (min and max) and matched price span
        val priceExtraction = parsePrices(correctedInput)
        val minPrice = priceExtraction.minPrice
        val maxPrice = priceExtraction.maxPrice
        val priceSpan = priceExtraction.matchedSpan

        // 3. Extract quality preferences
        val qualityPreferences = mutableListOf<String>()
        if (normalized.contains("danh gia tot") || normalized.contains("danh gia cao") ||
            normalized.contains("rating cao") || normalized.contains("good rating") ||
            normalized.contains("top rated")
        ) {
            qualityPreferences.add("đánh giá tốt")
        }
        if (normalized.contains("chinh hang") || normalized.contains("mall") || normalized.contains("official")) {
            qualityPreferences.add("chính hãng")
        }
        if (normalized.contains("nhieu nguoi mua") || normalized.contains("ban chay") || normalized.contains("best seller")) {
            qualityPreferences.add("nhiều người mua")
        }

        // 4. Extract excluded terms
        val excludedTerms = mutableListOf<String>()
        val excludeRegex = Pattern.compile(
            """(?:tru|khong lay|bo qua|loai tru|exclude|without)\s+([a-zA-Z0-9_\s]+?)(?:,|\.|;|\svoi|\sgia|\sunder|\swith|$|\son)""",
            Pattern.CASE_INSENSITIVE
        )
        val exMatcher = excludeRegex.matcher(correctedInput)
        while (exMatcher.find()) {
            val term = exMatcher.group(1)?.trim()
            if (!term.isNullOrBlank()) {
                excludedTerms.add(term)
            }
        }

        // 5. Extract core product search query
        val productQuery = extractProductQuery(correctedInput, priceSpan)

        // If product query is empty, do NOT invent a fake default; return NeedsInput clarification
        if (productQuery.isBlank()) {
            return ShoppingParseResult.NeedsInput("Bạn muốn tìm sản phẩm gì trên Shopee? Vui lòng nói rõ tên sản phẩm nhé.")
        }

        // 6. Extract specifications
        val requiredSpecifications = mutableListOf<String>()
        if (normalized.contains("bluetooth")) requiredSpecifications.add("Bluetooth")
        if (normalized.contains("khong day") || normalized.contains("wireless")) requiredSpecifications.add("không dây")
        if (normalized.contains("type c") || normalized.contains("type-c")) requiredSpecifications.add("Type-C")
        if (normalized.contains("chong on") || normalized.contains("noise cancelling")) requiredSpecifications.add("chống ồn")

        val request = ProductSearchRequest(
            targetApp = "com.shopee.vn",
            query = productQuery,
            requiredSpecifications = requiredSpecifications,
            minPrice = minPrice,
            maxPrice = maxPrice,
            budgetBoundary = budgetBoundary,
            budgetScope = BudgetScope.ITEM_PRICE,
            qualityPreferences = qualityPreferences,
            excludedTerms = excludedTerms
        )

        return ShoppingParseResult.Success(request)
    }

    /**
     * Convenience method to extract structured request if parsing succeeded.
     */
    fun parseRequest(rawInput: String): ProductSearchRequest? {
        return (parse(rawInput) as? ShoppingParseResult.Success)?.request
    }

    /**
     * Narrowly scoped typo normalization per plan.
     * Does not globally fuzzy-rewrite arbitrary product names.
     */
    fun normalizeNarrowTypos(input: String): String {
        return input.replace("(?i)\\bt-shurt\\b".toRegex(), "t-shirt")
            .replace("(?i)\\bt\\s+shurt\\b".toRegex(), "t-shirt")
            .replace("(?i)\\btshurt\\b".toRegex(), "t-shirt")
    }

    private data class PriceExtractionResult(
        val minPrice: Long?,
        val maxPrice: Long?,
        val matchedSpan: String?
    )

    /**
     * Extracts numerical money amounts from natural Vietnamese or English text.
     */
    private fun parsePrices(cleanInput: String): PriceExtractionResult {
        // Range: "từ X đến Y", "from X to Y", "between X and Y"
        val rangeRegex = Pattern.compile(
            """\b(?:từ|tu|khoảng|khoang|from|between)\s+([0-9.,]+(?:\s*(?:k|tr|nghìn|nghin|ngàn|ngan|triệu|trieu|m))?)\s+(?:đến|den|tới|toi|to|and|-)\s+([0-9.,]+(?:\s*(?:k|tr|nghìn|nghin|ngàn|ngan|triệu|trieu|m))?)\b""",
            Pattern.CASE_INSENSITIVE
        )
        val rangeMatcher = rangeRegex.matcher(cleanInput)
        if (rangeMatcher.find()) {
            val minStr = rangeMatcher.group(1)
            val maxStr = rangeMatcher.group(2)
            var min = minStr?.let { parseSingleAmount(it) }
            var max = maxStr?.let { parseSingleAmount(it) }
            if (min != null && max != null && min > max) {
                val temp = min
                min = max
                max = temp
            }
            if (min != null || max != null) {
                return PriceExtractionResult(min, max, rangeMatcher.group(0))
            }
        }

        // Single upper bound: "dưới X", "không quá X", "tối đa X", "under X", "below X", "up to X", "max X"
        val maxRegex = Pattern.compile(
            """\b(?:dưới|duoi|thấp\s+hơn|thap\s+hon|không\s+quá|khong\s+qua|tối\s+đa|toi\s+da|tầm|tam|khoảng|khoang|giá|gia|under|below|less\s+than|at\s+most|up\s+to|max)\s+([0-9.,]+(?:\s*(?:k|tr|nghìn|nghin|ngàn|ngan|triệu|trieu|đồng|dong|đ|vnd|m))?)\b""",
            Pattern.CASE_INSENSITIVE
        )
        val maxMatcher = maxRegex.matcher(cleanInput)
        if (maxMatcher.find()) {
            val amountStr = maxMatcher.group(1)
            val amount = amountStr?.let { parseSingleAmount(it) }
            if (amount != null && amount > 0L) {
                return PriceExtractionResult(null, amount, maxMatcher.group(0))
            }
        }

        // Fallback: search for any standalone price like "100k", "500 nghìn", "1.5 triệu", "2tr"
        // Avoid matching resolution (e.g., "4k monitor", "tivi 4k")
        val standaloneRegex = Pattern.compile(
            """\b(?<!tivi\s)(?<!man\s+hinh\s)(?<!camera\s)(?<!tv\s)([0-9.,]+)\s*(k|tr|nghìn|nghin|ngàn|ngan|triệu|trieu|đồng|dong|đ|vnd|m)\b""",
            Pattern.CASE_INSENSITIVE
        )
        val standaloneMatcher = standaloneRegex.matcher(cleanInput)
        while (standaloneMatcher.find()) {
            val numStr = standaloneMatcher.group(1)
            val unitStr = standaloneMatcher.group(2)
            val fullMatch = standaloneMatcher.group(0)

            // If unit is "k" and number < 10 (like "4k"), treat as resolution/spec unless context specifies price
            val numValue = numStr?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
            if (unitStr.equals("k", ignoreCase = true) && numValue < 10.0) {
                continue
            }

            val amount = parseNumberWithUnit(numStr, unitStr)
            if (amount != null && amount > 0L) {
                return PriceExtractionResult(null, amount, fullMatch)
            }
        }

        return PriceExtractionResult(null, null, null)
    }

    /**
     * Parses an individual amount string such as "500k", "500 nghìn", "1,5 triệu", "1tr5", "100k".
     */
    fun parseSingleAmount(input: String): Long? {
        val trimmed = TextNormalizer.removeAccents(input.trim().replace(" ", "").lowercase())

        // Match compound like "1tr5" -> 1.5 million
        val compoundTr = Pattern.compile("^([0-9]+)tr([0-9]+)$").matcher(trimmed)
        if (compoundTr.find()) {
            val whole = compoundTr.group(1)?.toLongOrNull() ?: return null
            val frac = compoundTr.group(2)?.toDoubleOrNull() ?: 0.0
            val fractionPart = frac / Math.pow(10.0, compoundTr.group(2)!!.length.toDouble())
            return ((whole + fractionPart) * 1_000_000L).toLong()
        }

        val pattern = Pattern.compile("^([0-9.,]+)(k|tr|nghin|ngan|trieu|dong|vnd|m)?$")
        val matcher = pattern.matcher(trimmed)
        if (!matcher.find()) return null

        val numPart = matcher.group(1) ?: return null
        val unitPart = matcher.group(2) ?: ""

        return parseNumberWithUnit(numPart, unitPart)
    }

    private fun parseNumberWithUnit(numStr: String?, unitStr: String?): Long? {
        if (numStr == null) return null
        val sanitizedNum = numStr.replace(",", ".").trim()
        val num = sanitizedNum.toDoubleOrNull() ?: return null
        if (num <= 0) return null
        val unit = unitStr?.lowercase()?.trim() ?: ""

        val multiplier = when {
            unit.contains("k") -> 1_000L
            unit.contains("nghin") || unit.contains("ngan") -> 1_000L
            unit.contains("tr") || unit.contains("trieu") || unit.contains("m") -> 1_000_000L
            else -> {
                if (num < 1000) 1_000L else 1L
            }
        }

        return (num * multiplier).toLong()
    }

    /**
     * Cleans up the product query by removing action verbs, app mentions, price spans, and preferences.
     * Preserves model numbers (e.g. "WH-1000XM4", "iPhone 15"), specifications ("4k"), and sizes ("size 42").
     */
    private fun extractProductQuery(cleanInput: String, matchedPriceSpan: String?): String {
        var query = cleanInput

        // Remove the matched price span first if present
        if (!matchedPriceSpan.isNullOrBlank()) {
            query = query.replace(matchedPriceSpan, " ", ignoreCase = true)
        }

        // Remove any explicit budget clauses with prefixes (under, dưới, etc.)
        val explicitPriceClause = Pattern.compile(
            """(?i)\b(?:dưới|duoi|thấp\s+hơn|thap\s+hon|không\s+quá|khong\s+qua|tối\s+đa|toi\s+da|tầm|tam|khoảng|khoang|giá|gia|từ|tu|under|below|less\s+than|at\s+most|up\s+to|max|between|from)\s+[0-9.,]+\s*(?:k|tr|nghìn|nghin|ngàn|ngan|triệu|trieu|đồng|dong|đ|vnd|m)?(?:\s*(?:đến|den|tới|toi|to|and|-)\s*[0-9.,]+\s*(?:k|tr|nghìn|nghin|ngàn|ngan|triệu|trieu|đồng|dong|đ|vnd|m)?)?""",
            Pattern.CASE_INSENSITIVE
        )
        query = explicitPriceClause.matcher(query).replaceAll(" ")

        // Remove standalone price tokens >= 10k (e.g. "100k", "500k", "1tr", "5tr") without stripping "4k" or model numbers
        val standalonePriceRegex = Pattern.compile(
            """(?i)\b([1-9][0-9]{1,3}k|[0-9.,]+(?:\s*(?:tr|nghìn|nghin|ngàn|ngan|triệu|trieu|đồng|dong|đ|vnd)))\b"""
        )
        query = standalonePriceRegex.matcher(query).replaceAll(" ")

        // Remove action and opening prefixes in Vietnamese and English (repeatable to strip e.g. "Mở Shopee, tìm giúp tôi")
        val prefixRegex = Pattern.compile(
            """^(?:open\s+shopee|mở\s+shopee|mo\s+shopee|vào\s+shopee|vao\s+shopee|shopee|tìm\s+giúp\s+tôi|tim\s+giup\s+toi|tìm\s+kiếm|tim\s+kiem|tìm|tim|kiếm|kiem|mua|hãy\s+tìm|hay\s+tim|giúp\s+tôi|giup\s+toi|find|search\s+for|search|look\s+for|buy)\s*[,]?\s*""",
            Pattern.CASE_INSENSITIVE
        )
        while (prefixRegex.matcher(query.trim()).find()) {
            query = prefixRegex.matcher(query.trim()).replaceFirst("")
        }

        // Remove app mention prepositions: "trên shopee", "ở shopee", "on shopee", "in shopee", "from shopee"
        query = query.replace("(?i)(?:^|\\s+)(?:trên|tren|ở|o|tại|tai|on|in|from)\\s+shopee\\b".toRegex(), " ")
            .replace("(?i)\\bshopee\\b".toRegex(), " ")

        // Remove trailing action directives like "rồi mở sản phẩm phù hợp nhất", "and open matching product"
        query = query.replace(
            """(?i),?\s*(?:rồi|roi|và|va|and|then)?\s*(?:mở|mo|chọn|chon|xem|open|select)\s+(?:sản\s+phẩm|san\s+pham|cái|cai|món|mon|product|item)\s+(?:phù\s+hợp|phu\s+hop|tốt|tot|nhất|nhat|best|matching).*$""".toRegex(),
            " "
        )

        // Remove preference clauses
        query = query.replace(
            """(?i),?\s*(?:đánh\s+giá\s+tốt|danh\s+gia\s+tot|đánh\s+giá\s+cao|danh\s+gia\s+cao|rating\s+cao|chính\s+hãng|chinh\s+hang|nhiều\s+người\s+mua|nhieu\s+nguoi\s+mua|bán\s+chạy|ban\s+chay|good\s+rating|top\s+rated|official|mall|best\s+seller)""".toRegex(),
            " "
        )

        // Clean punctuation and excess whitespace, preserving hyphens in models/sizes (e.g., WH-1000XM4, t-shirt)
        query = query.replace("[,.?!;:\"“”'’]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        return query
    }
}
