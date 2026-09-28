package com.dinh.javis.agent.shopping

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dinh.javis.commands.CommandParser
import com.dinh.javis.commands.Command
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumentation tests for [ShoppingTaskParser] and [CommandParser].
 *
 * These tests run on the Android runtime (ICU regex engine) to verify that
 * the shopping parser does NOT throw PatternSyntaxException — the root cause
 * of the TECNO LE7 / Android 11 / API 30 crash reported in versionCode 54.
 *
 * Previously failing: the unbounded look-behind (?<!man\s+hinh\s) in
 * ShoppingTaskParser.parsePrices() caused:
 *   java.util.regex.PatternSyntaxException:
 *     Look-behind pattern matches must have a bounded maximum length near index 30
 *   at com.android.icu.util.regex.PatternNative.compileImpl(Native Method)
 *
 * These tests must be run on an Android API 30 device/emulator to replicate the failure.
 * They complement (not replace) desktop JVM unit tests, which do not exercise Android ICU.
 *
 * Target device matrix: API 30 (primary failure), API 26 (minSdk), API 34 (targetSdk).
 */
@RunWith(AndroidJUnit4::class)
class ShoppingTaskParserAndroidTest {

    // ─── A1: Exact reported English crash input ───────────────────────────────

    /**
     * A1 — Exact device-reported crash input (English).
     * Must NOT throw PatternSyntaxException on Android ICU.
     * Must route to FindProduct with clean query "t-shirt" and max 100,000 VND.
     */
    @Test
    fun a1_exactEnglishCrashInputNoException() {
        val input = "find t-shurt 100k on shopee"
        val result = ShoppingTaskParser.parse(input)
        assertTrue("A1: parse must succeed (no PatternSyntaxException)", result is ShoppingParseResult.Success)
        val req = (result as ShoppingParseResult.Success).request
        assertEquals("A1: query must be t-shirt", "t-shirt", req.query)
        assertEquals("A1: maxPrice must be 100_000", 100_000L, req.maxPrice)
        assertNull("A1: minPrice must be null", req.minPrice)
        assertEquals("A1: boundary must be INCLUSIVE_MAX", BudgetBoundary.INCLUSIVE_MAX, req.budgetBoundary)
    }

    /**
     * A1 via CommandParser — full production routing path on Android runtime.
     */
    @Test
    fun a1_commandParserRoutesFindProduct() {
        val cmd = CommandParser().parse("find t-shurt 100k on shopee")
        assertTrue("A1: CommandParser must produce FindProduct", cmd is Command.FindProduct)
        val req = (cmd as Command.FindProduct).request
        assertEquals("t-shirt", req.query)
        assertEquals(100_000L, req.maxPrice)
    }

    // ─── A2: Exact reported Vietnamese crash input ────────────────────────────

    /**
     * A2 — Exact device-reported crash input (Vietnamese).
     * Must NOT throw PatternSyntaxException on Android ICU.
     * Must produce strict budget below 100,000 VND.
     */
    @Test
    fun a2_exactVietnameseCrashInputNoException() {
        val input = "tìm áo thun dưới 100k trên Shopee"
        val result = ShoppingTaskParser.parse(input)
        assertTrue("A2: parse must succeed (no PatternSyntaxException)", result is ShoppingParseResult.Success)
        val req = (result as ShoppingParseResult.Success).request
        assertEquals("A2: query must be áo thun", "áo thun", req.query)
        assertEquals("A2: maxPrice must be 100_000", 100_000L, req.maxPrice)
        assertEquals("A2: boundary must be STRICTLY_BELOW", BudgetBoundary.STRICTLY_BELOW, req.budgetBoundary)
    }

    // ─── Spec vs price disambiguation (Android ICU) ───────────────────────────

    /** "tivi 4k" must NOT be interpreted as a 4,000 VND budget. */
    @Test
    fun specDisambiguation_tivi4kNotBudget() {
        val req = ShoppingTaskParser.parseRequest("tìm tivi 4k trên shopee")!!
        assertNull("tivi 4k: maxPrice must be null (spec)", req.maxPrice)
        assertTrue("tivi 4k: query must contain 4k", req.query.contains("4k"))
    }

    /** "man hinh 4k" (unaccented) must NOT be interpreted as budget. */
    @Test
    fun specDisambiguation_manHinh4kNotBudget() {
        val req = ShoppingTaskParser.parseRequest("tìm man hinh 4k trên shopee")!!
        assertNull("man hinh 4k: maxPrice must be null", req.maxPrice)
    }

    /** "màn hình 4k" (accented) must NOT be interpreted as budget. */
    @Test
    fun specDisambiguation_manHinhAccented4kNotBudget() {
        val req = ShoppingTaskParser.parseRequest("tìm màn hình 4k trên shopee")!!
        assertNull("màn hình 4k: maxPrice must be null", req.maxPrice)
    }

    /** "camera 4k" must NOT be interpreted as budget. */
    @Test
    fun specDisambiguation_camera4kNotBudget() {
        val req = ShoppingTaskParser.parseRequest("tìm camera 4k trên shopee")!!
        assertNull("camera 4k: maxPrice must be null", req.maxPrice)
    }

    /** "tv 4k" must NOT be interpreted as budget. */
    @Test
    fun specDisambiguation_tv4kNotBudget() {
        val req = ShoppingTaskParser.parseRequest("tìm tv 4k trên shopee")!!
        assertNull("tv 4k: maxPrice must be null", req.maxPrice)
    }

    // ─── Multi-space/tab in context — must not throw ──────────────────────────

    /** Multiple spaces between "màn" and "hình" must not throw and must not produce a budget. */
    @Test
    fun multiSpace_screenContextNotBudget() {
        val req = ShoppingTaskParser.parseRequest("tìm màn  hình   4k trên shopee")!!
        assertNull("màn  hình  4k (multi-space): maxPrice must be null", req.maxPrice)
    }

    // ─── Resolution + later explicit price ────────────────────────────────────

    /** "màn hình 4k dưới 5tr": keep 4k as spec; extract 5,000,000 VND budget. */
    @Test
    fun resolutionPlusLaterRealPrice() {
        val req = ShoppingTaskParser.parseRequest("tìm màn hình 4k dưới 5tr trên shopee")!!
        assertNotNull("Should extract explicit budget 5tr", req.maxPrice)
        assertEquals("Budget must be 5_000_000", 5_000_000L, req.maxPrice)
        assertEquals("Boundary must be STRICTLY_BELOW", BudgetBoundary.STRICTLY_BELOW, req.budgetBoundary)
        assertTrue("Query must retain 4k spec", req.query.contains("4k"))
    }

    // ─── Standard money format coverage ──────────────────────────────────────

    @Test
    fun moneyFormat_nghìn() {
        val req = ShoppingTaskParser.parseRequest("tìm áo sơ mi dưới 500 nghìn trên shopee")!!
        assertEquals(500_000L, req.maxPrice)
    }

    @Test
    fun moneyFormat_triệu() {
        val req = ShoppingTaskParser.parseRequest("tìm laptop dưới 1.5 triệu trên shopee")!!
        assertEquals(1_500_000L, req.maxPrice)
    }

    @Test
    fun moneyFormat_2tr() {
        val req = ShoppingTaskParser.parseRequest("tìm balo dưới 2tr trên shopee")!!
        assertEquals(2_000_000L, req.maxPrice)
    }

    @Test
    fun moneyFormat_1tr5() {
        // Compound "1tr5" correctly parsed to 1.5M via parseSingleAmount
        assertEquals(1_500_000L, ShoppingTaskParser.parseSingleAmount("1tr5"))
        // Standalone "2tr" fallback path in end-to-end parse
        val req = ShoppingTaskParser.parseRequest("tìm giày 2tr trên shopee")!!
        assertEquals(2_000_000L, req.maxPrice)
    }

    // ─── Missing / malformed input safety ────────────────────────────────────

    /** Blank input must return NeedsInput — no crash, no unsafe dispatch. */
    @Test
    fun blankInputReturnsNeedsInput() {
        val r = ShoppingTaskParser.parse("   ")
        assertTrue("Blank input: must return NeedsInput", r is ShoppingParseResult.NeedsInput)
    }

    /** Price-only query (no product): must return NeedsInput. */
    @Test
    fun priceOnlyQueryReturnsNeedsInput() {
        val r = ShoppingTaskParser.parse("find 100k on shopee")
        assertTrue("Price-only: must return NeedsInput", r is ShoppingParseResult.NeedsInput)
    }

    /** Product without price: must succeed with null maxPrice. */
    @Test
    fun productWithoutPriceSucceedsNullBudget() {
        val req = ShoppingTaskParser.parseRequest("tìm giày thể thao trên shopee")!!
        assertNull("No price in query: maxPrice must be null", req.maxPrice)
        assertFalse("Query must be non-empty", req.query.isBlank())
    }

    // ─── English strict/inclusive boundary ───────────────────────────────────

    @Test
    fun englishUnder500kStrictlyBelow() {
        val req = ShoppingTaskParser.parseRequest("find keyboard under 500k on shopee")!!
        assertEquals(500_000L, req.maxPrice)
        assertEquals(BudgetBoundary.STRICTLY_BELOW, req.budgetBoundary)
    }

    @Test
    fun englishMax500kInclusive() {
        val req = ShoppingTaskParser.parseRequest("find keyboard max 500k on shopee")!!
        assertEquals(500_000L, req.maxPrice)
        assertEquals(BudgetBoundary.INCLUSIVE_MAX, req.budgetBoundary)
    }

    // ─── Price range ──────────────────────────────────────────────────────────

    @Test
    fun priceRangeVietnamese() {
        val req = ShoppingTaskParser.parseRequest("tìm sạc dự phòng từ 200k đến 500k trên shopee")!!
        assertEquals(200_000L, req.minPrice)
        assertEquals(500_000L, req.maxPrice)
    }

    @Test
    fun priceRangeEnglish() {
        val req = ShoppingTaskParser.parseRequest("look for powerbank from 200k to 500k on shopee")!!
        assertEquals(200_000L, req.minPrice)
        assertEquals(500_000L, req.maxPrice)
    }
}
