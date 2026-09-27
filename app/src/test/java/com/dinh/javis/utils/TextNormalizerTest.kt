package com.dinh.javis.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun testStripMarkdownForSpeech_boldAndItalic() {
        val input = "**Chào anh Dinh!** Hôm nay là *Thứ Hai*."
        val expected = "Chào anh Dinh! Hôm nay là Thứ Hai."
        assertEquals(expected, TextNormalizer.stripMarkdownForSpeech(input))
    }

    @Test
    fun testStripMarkdownForSpeech_headersAndLists() {
        val input = "### Kết quả:\n- **Thời tiết**: Nắng đẹp.\n- **Nhiệt độ**: 28°C."
        val expected = "Kết quả: Thời tiết: Nắng đẹp. Nhiệt độ: 28°C."
        assertEquals(expected, TextNormalizer.stripMarkdownForSpeech(input))
    }

    @Test
    fun testStripMarkdownForSpeech_codeAndLinks() {
        val input = "Dùng lệnh `adb devices` và xem [Báo Dân Trí](https://dantri.com.vn) nhé."
        val expected = "Dùng lệnh adb devices và xem Báo Dân Trí nhé."
        assertEquals(expected, TextNormalizer.stripMarkdownForSpeech(input))
    }
}
