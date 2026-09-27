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

    @Test
    fun testStripHotword_removesWakeWordPrefixes() {
        assertEquals("lướt lên", TextNormalizer.stripHotword("javis lướt lên"))
        assertEquals("mở tiktok", TextNormalizer.stripHotword("Jarvis mở tiktok"))
        assertEquals("tìm tai nghe bluetooth", TextNormalizer.stripHotword("Ê javis tìm tai nghe bluetooth"))
        assertEquals("tăng âm lượng", TextNormalizer.stripHotword("hey jarvis tăng âm lượng"))
        assertEquals("cho nhỏ lại", TextNormalizer.stripHotword("javis ơi cho nhỏ lại"))
        assertEquals("mở youtube", TextNormalizer.stripHotword("e javis mở youtube"))
        assertEquals("javis", TextNormalizer.stripHotword("javis"))
    }
}
