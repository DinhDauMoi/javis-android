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
     * Đặc biệt: Tách wake word ("javis", "jarvis", "ê javis", "hey jarvis") nếu người dùng nói liền trong 1 câu:
     * Ví dụ: "javis lướt lên" -> "lướt lên"
     *        "ê javis lướt lên dùm" -> "lướt lên"
     *        "tăng âm lượng dùm tôi với" -> "tăng âm lượng"
     */
    fun stripFillerWords(input: String): String {
        var result = input.trim()

        val fillers = listOf(
            // Từ đánh thức & gọi tên
            "ê javis", "hey javis", "ơi javis", "javis ơi", "javis",
            "ê jarvis", "hey jarvis", "ơi jarvis", "jarvis ơi", "jarvis",
            // Từ đệm nhờ vả
            "hộ tao", "hộ tôi", "hộ mình", "hộ em", "hộ anh", "hộ",
            "giùm tao", "giùm tôi", "giúp tôi", "giúp với", "giúp em", "giúp mình", "giùm",
            "dùm tao", "dùm tôi", "dùm mình", "dùm với", "dùm",
            "với nhé", "đi nhé", "nhé bạn", "làm ơn", "với"
        )

        for (filler in fillers) {
            // Thay thế không phân biệt hoa thường
            result = result.replace(Regex("(?i)(?<=^|\\s)$filler(?=\\s|\$|[.,!?])"), "").trim()
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
