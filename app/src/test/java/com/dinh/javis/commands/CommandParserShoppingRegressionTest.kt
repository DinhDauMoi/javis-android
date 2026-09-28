package com.dinh.javis.commands

import com.dinh.javis.agent.shopping.BudgetBoundary
import com.dinh.javis.agent.shopping.BudgetScope
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CommandParserShoppingRegressionTest {

    private lateinit var parser: CommandParser

    @Before
    fun setUp() {
        parser = CommandParser()
    }

    @Test
    fun `exact reported input find t-shurt 100k on shopee routes to FindProduct`() {
        val input = "find t-shurt 100k on shopee"
        val cmd = parser.parse(input)

        assertTrue("Command must be FindProduct, but was $cmd", cmd is Command.FindProduct)
        val req = (cmd as Command.FindProduct).request

        assertEquals("com.shopee.vn", req.targetApp)
        assertEquals("t-shirt", req.query)
        assertEquals(100_000L, req.maxPrice)
        assertNull(req.minPrice)
        assertEquals(BudgetBoundary.INCLUSIVE_MAX, req.budgetBoundary)
        assertEquals(BudgetScope.ITEM_PRICE, req.budgetScope)
        assertEquals(
            "Đang tìm áo thun trên Shopee, giá sản phẩm không quá 100.000đ, chưa gồm phí vận chuyển.",
            req.buildExplanationVi()
        )
    }

    @Test
    fun `Vietnamese shopping command with budget under 100k`() {
        val input = "tìm áo thun dưới 100k trên Shopee"
        val cmd = parser.parse(input)

        assertTrue(cmd is Command.FindProduct)
        val req = (cmd as Command.FindProduct).request
        assertEquals("com.shopee.vn", req.targetApp)
        assertEquals("áo thun", req.query)
        assertEquals(100_000L, req.maxPrice)
        assertEquals(BudgetBoundary.STRICTLY_BELOW, req.budgetBoundary)
    }

    @Test
    fun `English phrases search for and look for route to FindProduct`() {
        val cmd1 = parser.parse("search for mechanical keyboard on shopee")
        assertTrue(cmd1 is Command.FindProduct)
        assertEquals("mechanical keyboard", (cmd1 as Command.FindProduct).request.query)

        val cmd2 = parser.parse("look for shoes under 500k on shopee")
        assertTrue(cmd2 is Command.FindProduct)
        val req2 = (cmd2 as Command.FindProduct).request
        assertEquals("shoes", req2.query)
        assertEquals(500_000L, req2.maxPrice)
        assertEquals(BudgetBoundary.STRICTLY_BELOW, req2.budgetBoundary)
    }

    @Test
    fun `app open commands remain OpenApp and do not trigger shopping`() {
        val cmdVi = parser.parse("mở shopee")
        assertTrue(cmdVi is Command.OpenApp)
        assertEquals("shopee", (cmdVi as Command.OpenApp).appName)

        val cmdEn = parser.parse("open shopee")
        assertTrue(cmdEn is Command.OpenApp)
        assertEquals("shopee", (cmdEn as Command.OpenApp).appName)
    }

    @Test
    fun `informational questions do not trigger phone control shopping and remain AskAi`() {
        val cmd1 = parser.parse("what is Shopee?")
        assertTrue(cmd1 is Command.AskAi)

        val cmd2 = parser.parse("how do I find a shirt on Shopee?")
        assertTrue(cmd2 is Command.AskAi)

        val cmd3 = parser.parse("Shopee là gì?")
        assertTrue(cmd3 is Command.AskAi)

        val cmd4 = parser.parse("\"find t-shirt 100k on shopee\"")
        assertTrue(cmd4 is Command.AskAi)
    }

    @Test
    fun `empty shopping query routes to Clarify`() {
        val cmd = parser.parse("tìm trên shopee")
        assertTrue("Expected Clarify command for empty query, got $cmd", cmd is Command.Clarify)
        val msg = (cmd as Command.Clarify).clarificationVi
        assertTrue(msg.contains("Bạn muốn tìm sản phẩm gì"))
    }
}
