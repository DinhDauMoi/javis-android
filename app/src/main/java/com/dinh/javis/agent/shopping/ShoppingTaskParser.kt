package com.dinh.javis.agent.shopping

import com.dinh.javis.utils.TextNormalizer
import java.util.regex.Pattern

/**
 * Parses Vietnamese spoken or typed requests into structured [ProductSearchRequest] (Section 4).
 * Handles accented and unaccented text, currency units (k, nghìn, triệu, tr),
 * budget boundaries (dưới vs không quá), specifications, and preferences.
 */
object ShoppingTaskParser {

    /**
     * Determines whether the given user input expresses a shopping search intent.
     */
    fun isShoppingIntent(input: String): Boolean {
        val normalized = TextNormalizer.removeAccents(input.lowercase()).trim()
        val hasShopee = normalized.contains("shopee") || normalized.contains("sope") || normalized.contains("shoppe")
        val hasSearchAction = normalized.contains("tim") || normalized.contains("kiem") ||
                normalized.contains("mua") || normalized.contains("chon") || normalized.contains("xem gia")
        
        // Compound: "mở shopee tìm ...", "shopee tìm ...", "tìm ... trên shopee"
        if (hasShopee && hasSearchAction) return true

        // Explicit shopping phrasing: "tìm sản phẩm ...", "tìm mua ..."
        if (normalized.startsWith("tim mua ") || normalized.startsWith("tim giup toi ") ||
            normalized.contains("san pham phu hop nhat")
        ) {
            return true
        }

        return false
    }

    /**
     * Parses the raw natural language input into a [ProductSearchRequest].
     */
    fun parse(rawInput: String): ProductSearchRequest {
        val cleanInput = TextNormalizer.stripFillerWords(rawInput.trim())
        val normalized = TextNormalizer.removeAccents(cleanInput.lowercase())

        // 1. Detect budget boundary (strictly below vs inclusive max)
        val isStrictlyBelow = normalized.contains("duoi ") || normalized.contains("thap hon ") ||
                normalized.contains("nho hon ") || normalized.contains("it hon ")
        val budgetBoundary = if (isStrictlyBelow) BudgetBoundary.STRICTLY_BELOW else BudgetBoundary.INCLUSIVE_MAX

        // 2. Parse price bounds (min and max)
        val (minPrice, maxPrice) = parsePrices(cleanInput, normalized)

        // 3. Extract quality preferences
        val qualityPreferences = mutableListOf<String>()
        if (normalized.contains("danh gia tot") || normalized.contains("danh gia cao") || normalized.contains("rating cao")) {
            qualityPreferences.add("đánh giá tốt")
        }
        if (normalized.contains("chinh hang") || normalized.contains("mall")) {
            qualityPreferences.add("chính hãng")
        }
        if (normalized.contains("nhieu nguoi mua") || normalized.contains("ban chay")) {
            qualityPreferences.add("nhiều người mua")
        }

        // 4. Extract excluded terms
        val excludedTerms = mutableListOf<String>()
        val excludeRegex = Pattern.compile("(?:tru|khong lay|bo qua|loai tru)\\s+([a-zA-Z0-9_\\s]+?)(?:,|\\.|;|\\svoi|\\sgia|$)", Pattern.CASE_INSENSITIVE)
        val exMatcher = excludeRegex.matcher(cleanInput)
        while (exMatcher.find()) {
            val term = exMatcher.group(1)?.trim()
            if (!term.isNullOrBlank()) {
                excludedTerms.add(term)
            }
        }

        // 5. Extract core product search query
        val productQuery = extractProductQuery(cleanInput)

        // 6. Extract specifications
        val requiredSpecifications = mutableListOf<String>()
        if (normalized.contains("bluetooth")) requiredSpecifications.add("Bluetooth")
        if (normalized.contains("khong day")) requiredSpecifications.add("không dây")
        if (normalized.contains("type c") || normalized.contains("type-c")) requiredSpecifications.add("Type-C")
        if (normalized.contains("chong on")) requiredSpecifications.add("chống ồn")

        return ProductSearchRequest(
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
    }

    /**
     * Extracts numerical money amounts from natural Vietnamese text.
     */
    private fun parsePrices(cleanInput: String, normalized: String): Pair<Long?, Long?> {
        // Range: "từ X đến Y" or "khoảng X đến Y"
        val rangeRegex = Pattern.compile("(?:tu|khoang)\\s+([0-9.,]+(?:k|tr|nghin|ngan|trieu)?)\\s+(?:den|toi|-)\\s+([0-9.,]+(?:k|tr|nghin|ngan|trieu)?)", Pattern.CASE_INSENSITIVE)
        val rangeMatcher = rangeRegex.matcher(normalized)
        if (rangeMatcher.find()) {
            val minStr = rangeMatcher.group(1)
            val maxStr = rangeMatcher.group(2)
            val min = minStr?.let { parseSingleAmount(it) }
            val max = maxStr?.let { parseSingleAmount(it) }
            if (min != null || max != null) {
                return Pair(min, max)
            }
        }

        // Single upper bound: "dưới X", "không quá X", "tối đa X", "tầm X", "khoảng X"
        val maxRegex = Pattern.compile("(?:duoi|thap hon|khong qua|toi da|tam|khoang|gia)\\s+([0-9.,]+(?:\\s*(?:k|tr|nghin|ngan|trieu|dong|vnd))?)", Pattern.CASE_INSENSITIVE)
        val maxMatcher = maxRegex.matcher(normalized)
        if (maxMatcher.find()) {
            val amountStr = maxMatcher.group(1)
            val amount = amountStr?.let { parseSingleAmount(it) }
            if (amount != null) {
                return Pair(null, amount)
            }
        }

        // Fallback: search for any standalone price like "500k", "500 nghìn", "1.5 triệu"
        val anyPriceRegex = Pattern.compile("([0-9.,]+)\\s*(k|tr|nghin|ngan|trieu)", Pattern.CASE_INSENSITIVE)
        val anyMatcher = anyPriceRegex.matcher(normalized)
        if (anyMatcher.find()) {
            val numStr = anyMatcher.group(1)
            val unitStr = anyMatcher.group(2)
            val amount = parseNumberWithUnit(numStr, unitStr)
            if (amount != null) {
                return Pair(null, amount)
            }
        }

        return Pair(null, null)
    }

    /**
     * Parses an individual amount string such as "500k", "500 nghìn", "1,5 triệu", "1tr5".
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

        val pattern = Pattern.compile("^([0-9.,]+)(k|tr|nghin|ngan|trieu|dong|vnd)?$")
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
        val unit = unitStr?.lowercase()?.trim() ?: ""

        val multiplier = when {
            unit.contains("k") -> 1_000L
            unit.contains("nghin") || unit.contains("ngan") -> 1_000L
            unit.contains("tr") || unit.contains("trieu") -> 1_000_000L
            else -> {
                if (num < 1000) 1_000L else 1L
            }
        }

        return (num * multiplier).toLong()
    }

    /**
     * Cleans up the product query by removing app mentions, search verbs, price clauses, and filler.
     */
    private fun extractProductQuery(cleanInput: String): String {
        var query = cleanInput

        // Remove opening/action prefixes
        val prefixRegex = Pattern.compile("^(?:mo\\s+shopee|vao\\s+shopee|shopee|tim\\s+giup\\s+toi|tim\\s+kiem|tim|kiem|mua|hay\\s+tim|giup\\s+toi)\\s*,?\\s*", Pattern.CASE_INSENSITIVE)
        query = prefixRegex.matcher(query).replaceFirst("")

        // Remove "trên shopee" or "ở shopee"
        query = query.replace("(?i)\\s+(?:tren|o|tai)\\s+shopee".toRegex(), "")

        // Remove trailing action directives like "rồi mở sản phẩm phù hợp nhất", "chọn cái tốt nhất"
        query = query.replace("(?i),?\\s*(?:roi|va)?\\s*(?:mo|chon|xem)\\s+(?:san pham|cai|mon)\\s+(?:phu hop|tot|nhat).*$".toRegex(), "")

        // Remove price clause
        query = query.replace("(?i)\\s*(?:duoi|thap hon|khong qua|toi da|tam|khoang|gia|tu)\\s+[0-9.,]+\\s*(?:k|tr|nghin|ngan|trieu|dong|vnd)?(?:\\s*(?:den|toi|-)\\s*[0-9.,]+\\s*(?:k|tr|nghin|ngan|trieu|dong|vnd)?)?".toRegex(), "")

        // Remove preference clauses
        query = query.replace("(?i),?\\s*(?:danh gia tot|danh gia cao|rating cao|chinh hang|nhieu nguoi mua|ban chay)".toRegex(), "")

        // Clean punctuation and excess whitespace
        query = query.replace("[,.?!;:]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        return query.ifBlank { "tai nghe" }
    }
}
