package com.dinh.javis.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test suite for voice-scroll command recognition.
 *
 * Covers:
 *  - Vietnamese scroll phrases (accented and unaccented)
 *  - English phrases: "scroll up", "scroll down", "swipe up", "swipe down"
 *  - Mixed Vietnamese-English: "lướt up", "lướt down", "vuốt up", "vuốt down"
 *  - Wake-word prefix and filler stripping
 *  - ASR error recovery: "nước xuống" → ScrollDown
 *  - Negative cases: arbitrary text containing "up"/"down" must NOT become a scroll command
 *  - Semantic synonyms: "tiếp theo", "previous", "xem tiếp", etc.
 */
class CommandParserScrollTest {

    private lateinit var parser: CommandParser

    @Before
    fun setUp() {
        parser = CommandParser()
    }

    // =========================================================================
    // 1. CLEAR VIETNAMESE FORMS (accented)
    // =========================================================================

    @Test
    fun `luot len with accent parses to ScrollUp`() {
        assertScrollUp("lướt lên")
    }

    @Test
    fun `vuot len with accent parses to ScrollUp`() {
        assertScrollUp("vuốt lên")
    }

    @Test
    fun `cuon len with accent parses to ScrollUp`() {
        assertScrollUp("cuộn lên")
    }

    @Test
    fun `luot xuong with accent parses to ScrollDown`() {
        assertScrollDown("lướt xuống")
    }

    @Test
    fun `vuot xuong with accent parses to ScrollDown`() {
        assertScrollDown("vuốt xuống")
    }

    @Test
    fun `cuon xuong with accent parses to ScrollDown`() {
        assertScrollDown("cuộn xuống")
    }

    // =========================================================================
    // 2. UNACCENTED VIETNAMESE
    // =========================================================================

    @Test
    fun `luot len unaccented parses to ScrollUp`() {
        assertScrollUp("luot len")
    }

    @Test
    fun `vuot len unaccented parses to ScrollUp`() {
        assertScrollUp("vuot len")
    }

    @Test
    fun `cuon len unaccented parses to ScrollUp`() {
        assertScrollUp("cuon len")
    }

    @Test
    fun `luot xuong unaccented parses to ScrollDown`() {
        assertScrollDown("luot xuong")
    }

    @Test
    fun `vuot xuong unaccented parses to ScrollDown`() {
        assertScrollDown("vuot xuong")
    }

    @Test
    fun `cuon xuong unaccented parses to ScrollDown`() {
        assertScrollDown("cuon xuong")
    }

    // =========================================================================
    // 3. ENGLISH DIRECTION PHRASES
    // =========================================================================

    @Test
    fun `scroll up English parses to ScrollUp`() {
        assertScrollUp("scroll up")
    }

    @Test
    fun `scroll down English parses to ScrollDown`() {
        assertScrollDown("scroll down")
    }

    @Test
    fun `swipe up English parses to ScrollUp`() {
        assertScrollUp("swipe up")
    }

    @Test
    fun `swipe down English parses to ScrollDown`() {
        assertScrollDown("swipe down")
    }

    // =========================================================================
    // 4. MIXED VIETNAMESE-ENGLISH ("lướt up / lướt down")
    // =========================================================================

    @Test
    fun `luot up mixed parses to ScrollUp`() {
        assertScrollUp("lướt up")
    }

    @Test
    fun `luot down mixed parses to ScrollDown`() {
        assertScrollDown("lướt down")
    }

    @Test
    fun `vuot up mixed parses to ScrollUp`() {
        assertScrollUp("vuốt up")
    }

    @Test
    fun `vuot down mixed parses to ScrollDown`() {
        assertScrollDown("vuốt down")
    }

    @Test
    fun `cuon up mixed parses to ScrollUp`() {
        assertScrollUp("cuộn up")
    }

    @Test
    fun `cuon down mixed parses to ScrollDown`() {
        assertScrollDown("cuộn down")
    }

    // =========================================================================
    // 5. WAKE-WORD AND FILLER WORD STRIPPING
    // =========================================================================

    @Test
    fun `javis luot len with wake word parses to ScrollUp`() {
        assertScrollUp("javis lướt lên")
    }

    @Test
    fun `javis luot up dùm with wake word and filler parses to ScrollUp`() {
        assertScrollUp("JAVIS lướt up dùm")
    }

    @Test
    fun `javis luot down with mixed form parses to ScrollDown`() {
        assertScrollDown("javis lướt down")
    }

    @Test
    fun `e javis scroll up parses to ScrollUp`() {
        assertScrollUp("ê javis scroll up")
    }

    @Test
    fun `hey jarvis scroll down parses to ScrollDown`() {
        assertScrollDown("hey jarvis scroll down")
    }

    @Test
    fun `javis luot xuong ho parses to ScrollDown`() {
        assertScrollDown("javis lướt xuống hộ")
    }

    // =========================================================================
    // 6. ASR ERROR RECOVERY — "nước xuống" → ScrollDown
    // =========================================================================

    @Test
    fun `nuoc xuong ASR mis-transcription parses to ScrollDown`() {
        // "nước xuống" is a known ASR error for "lướt xuống":
        // after removeAccents it becomes "nuoc xuong" which is unambiguous.
        assertScrollDown("nước xuống")
    }

    @Test
    fun `nuoc xuong unaccented parses to ScrollDown`() {
        assertScrollDown("nuoc xuong")
    }

    // =========================================================================
    // 7. SEMANTIC SYNONYMS
    // =========================================================================

    @Test
    fun `tiep theo parses to ScrollUp`() {
        assertScrollUp("tiếp theo")
    }

    @Test
    fun `video tiep parses to ScrollUp`() {
        assertScrollUp("video tiếp")
    }

    @Test
    fun `sang video parses to ScrollUp`() {
        assertScrollUp("sang video")
    }

    @Test
    fun `xem tiep parses to ScrollUp`() {
        assertScrollUp("xem tiếp")
    }

    @Test
    fun `next video parses to ScrollUp`() {
        assertScrollUp("next video")
    }

    @Test
    fun `previous parses to ScrollDown`() {
        assertScrollDown("previous")
    }

    @Test
    fun `video truoc parses to ScrollDown`() {
        assertScrollDown("video trước")
    }

    @Test
    fun `xem lai parses to ScrollDown`() {
        assertScrollDown("xem lại")
    }

    // =========================================================================
    // 8. BARE TOKENS ("up"/"down"/"lên"/"xuống" as sole content after strip)
    // =========================================================================

    @Test
    fun `bare up token parses to ScrollUp`() {
        assertScrollUp("up")
    }

    @Test
    fun `bare down token parses to ScrollDown`() {
        assertScrollDown("down")
    }

    @Test
    fun `bare len token parses to ScrollUp`() {
        assertScrollUp("lên")
    }

    @Test
    fun `bare xuong token parses to ScrollDown`() {
        assertScrollDown("xuống")
    }

    // =========================================================================
    // 9. NEGATIVE CASES — must NOT become a scroll command
    // =========================================================================

    @Test
    fun `set volume up should not become ScrollUp`() {
        val cmd = parser.parse("set volume up a bit")
        // Should route to volume-up, not scroll
        assertTrue(
            "Expected ChangeVolume but got $cmd",
            cmd is Command.ChangeVolume && cmd.action == Command.VolumeAction.UP
        )
    }

    @Test
    fun `what is up should not become ScrollUp`() {
        val cmd = parser.parse("what is up")
        assertTrue(
            "Arbitrary question containing 'up' as substring must not become ScrollUp, got $cmd",
            cmd !is Command.ScrollUp
        )
    }

    @Test
    fun `turn down the volume should not become ScrollDown`() {
        val cmd = parser.parse("turn down the volume")
        assertTrue(
            "Volume phrase must not become ScrollDown, got $cmd",
            cmd !is Command.ScrollDown
        )
    }

    @Test
    fun `download app should not become ScrollDown`() {
        // "download" contains "down" as prefix substring; must not match bare token rule
        val cmd = parser.parse("download the app")
        assertTrue(
            "'download' must not trigger ScrollDown, got $cmd",
            cmd !is Command.ScrollDown
        )
    }

    @Test
    fun `uproar should not trigger ScrollUp`() {
        val cmd = parser.parse("uproar")
        assertTrue("'uproar' must not trigger ScrollUp, got $cmd", cmd !is Command.ScrollUp)
    }

    @Test
    fun `open youtube should not trigger scroll`() {
        val cmd = parser.parse("mở youtube")
        assertTrue("Open YouTube must not trigger scroll, got $cmd",
            cmd is Command.OpenApp)
    }

    @Test
    fun `empty string returns Unknown`() {
        val cmd = parser.parse("  ")
        assertTrue("Empty input should return Unknown, got $cmd", cmd is Command.Unknown)
    }

    // =========================================================================
    // 10. CASE INSENSITIVITY
    // =========================================================================

    @Test
    fun `SCROLL UP uppercase parses to ScrollUp`() {
        assertScrollUp("SCROLL UP")
    }

    @Test
    fun `Scroll Down mixed case parses to ScrollDown`() {
        assertScrollDown("Scroll Down")
    }

    @Test
    fun `LUOT LEN uppercase unaccented parses to ScrollUp`() {
        assertScrollUp("LUOT LEN")
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun assertScrollUp(input: String) {
        val cmd = parser.parse(input)
        assertEquals(
            "Expected ScrollUp for input '$input', got $cmd",
            Command.ScrollUp,
            cmd
        )
    }

    private fun assertScrollDown(input: String) {
        val cmd = parser.parse(input)
        assertEquals(
            "Expected ScrollDown for input '$input', got $cmd",
            Command.ScrollDown,
            cmd
        )
    }
}
