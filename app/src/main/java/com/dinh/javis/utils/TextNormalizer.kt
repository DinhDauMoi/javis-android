package com.dinh.javis.utils

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Tiện ích chuẩn hóa văn bản tiếng Việt
 * Giúp nhận diện giọng nói chính xác ngay cả khi người dùng nói không dấu,
 * nói thừa từ đệm ("ê javis", "làm ơn", "dùm", "hộ") hoặc sai chính tả nhẹ.
 */
object TextNormalizer {

    private val DIACRITICS_PATTERN: Pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")

    /**
     * Chuyển chuỗi tiếng Việt có dấu thành không dấu và chữ thường
     * Ví dụ: "Mở YouTube dùm tôi" -> "mo youtube dum toi"
     */
    fun removeAccents(input: String?): String {
        if (input.isNullOrBlank()) return ""
        
        var normalized = input.trim().lowercase()
        // Thay thế ký tự Đ/đ đặc thù trước khi tách dấu Unicode
        normalized = normalized.replace("đ", "d")
        
        val decomposed = Normalizer.normalize(normalized, Normalizer.Form.NFD)
        return DIACRITICS_PATTERN.matcher(decomposed).replaceAll("")
    }

    /**
     * Loại bỏ các từ đệm, từ xưng hô thừa trong câu lệnh nói tự nhiên
     * Ví dụ: "ê javis mở youtube hộ tao với" -> "mở youtube"
     */
    fun stripFillerWords(input: String): String {
        var result = input.trim()
        val lower = result.lowercase()

        val fillers = listOf(
            "ê javis", "hey javis", "ơi javis", "javis ơi", "javis",
            "hộ tao", "hộ tôi", "hộ mình", "giùm tao", "giùm tôi", "giúp tôi", "giúp với",
            "dùm với", "dùm tôi", "với nhé", "đi nhé", "nhé bạn", "làm ơn"
        )

        for (filler in fillers) {
            if (lower.contains(filler)) {
                // Thay thế không phân biệt hoa thường
                result = result.replace(Regex("(?i)\\b$filler\\b"), "").trim()
            }
        }

        // Dọn dẹp khoảng trắng kép
        return result.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * So khớp gần đúng (fuzzy match / contains) giữa câu nói và từ khóa
     */
    fun matchesFuzzy(userInput: String, keyword: String): Boolean {
        val normUser = removeAccents(userInput)
        val normKeyword = removeAccents(keyword)
        return normUser.contains(normKeyword)
    }
}
