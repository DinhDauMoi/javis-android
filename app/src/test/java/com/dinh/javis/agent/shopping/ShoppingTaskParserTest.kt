package com.dinh.javis.agent.shopping

import com.dinh.javis.commands.Command
import com.dinh.javis.commands.CommandParser
import org.junit.Assert.*
import org.junit.Test

class ShoppingTaskParserTest {

    @Test
    fun `isShoppingIntent correctly identifies shopping requests in Vietnamese and English`() {
        assertTrue(ShoppingTaskParser.isShoppingIntent("Mở Shopee tìm tai nghe bluetooth dưới 500k"))
        assertTrue(ShoppingTaskParser.isShoppingIntent("Tìm giúp tôi ốp lưng trên Shopee"))
        assertTrue(ShoppingTaskParser.isShoppingIntent("shopee tìm chuột không dây"))
        assertTrue(ShoppingTaskParser.isShoppingIntent("find t-shurt 100k on shopee"))
        assertTrue(ShoppingTaskParser.isShoppingIntent("search for t-shirt on shopee"))
        assertTrue(ShoppingTaskParser.isShoppingIntent("look for running shoes on shopee"))
        assertTrue(ShoppingTaskParser.isShoppingIntent("buy mechanical keyboard on shopee"))

        // Simple app launch is NOT shopping intent
        assertFalse(ShoppingTaskParser.isShoppingIntent("Mở Shopee"))
        assertFalse(ShoppingTaskParser.isShoppingIntent("open shopee"))
        assertFalse(ShoppingTaskParser.isShoppingIntent("Mở YouTube"))

        // Informational queries must NOT be shopping intent
        assertFalse(ShoppingTaskParser.isShoppingIntent("what is Shopee?"))
        assertFalse(ShoppingTaskParser.isShoppingIntent("how do I find a shirt on Shopee?"))
        assertFalse(ShoppingTaskParser.isShoppingIntent("how to find clothes on shopee"))
        assertFalse(ShoppingTaskParser.isShoppingIntent("Shopee là gì?"))
        assertFalse(ShoppingTaskParser.isShoppingIntent("làm sao để tìm áo trên shopee"))
        assertFalse(ShoppingTaskParser.isShoppingIntent("\"find t-shirt 100k on shopee\""))
    }

    @Test
    fun `parse exact reported input find t-shurt 100k on shopee`() {
        val input = "find t-shurt 100k on shopee"
        val result = ShoppingTaskParser.parse(input)
        assertTrue(result is ShoppingParseResult.Success)

        val req = (result as ShoppingParseResult.Success).request
        assertEquals("com.shopee.vn", req.targetApp)
        assertEquals("t-shirt", req.query)
        assertEquals(100_000L, req.maxPrice)
        assertNull(req.minPrice)
        assertEquals(BudgetBoundary.INCLUSIVE_MAX, req.budgetBoundary)
        assertEquals(BudgetScope.ITEM_PRICE, req.budgetScope)

        // Verify Vietnamese explanation
        assertEquals(
            "Đang tìm áo thun trên Shopee, giá sản phẩm không quá 100.000đ, chưa gồm phí vận chuyển.",
            req.buildExplanationVi()
        )
    }

    @Test
    fun `parse compound shopping request with budget and preferences`() {
        val input = "Mở Shopee, tìm giúp tôi tai nghe Bluetooth dưới 500 nghìn, đánh giá tốt, rồi mở sản phẩm phù hợp nhất."
        val req = ShoppingTaskParser.parseRequest(input)
        assertNotNull(req)

        assertEquals("com.shopee.vn", req!!.targetApp)
        assertEquals("tai nghe Bluetooth", req.query)
        assertEquals(500_000L, req.maxPrice)
        assertEquals(BudgetBoundary.STRICTLY_BELOW, req.budgetBoundary)
        assertTrue(req.requiredSpecifications.contains("Bluetooth"))
        assertTrue(req.qualityPreferences.contains("đánh giá tốt"))
    }

    @Test
    fun `parse currency amounts correctly`() {
        assertEquals(100_000L, ShoppingTaskParser.parseSingleAmount("100k"))
        assertEquals(500_000L, ShoppingTaskParser.parseSingleAmount("500k"))
        assertEquals(500_000L, ShoppingTaskParser.parseSingleAmount("500 nghìn"))
        assertEquals(1_500_000L, ShoppingTaskParser.parseSingleAmount("1.5 triệu"))
        assertEquals(1_500_000L, ShoppingTaskParser.parseSingleAmount("1tr5"))
        assertEquals(2_000_000L, ShoppingTaskParser.parseSingleAmount("2tr"))
    }

    @Test
    fun `parse inclusive maximum boundary vs strictly below`() {
        val inputInclusive = "Tìm bàn phím cơ không quá 1 triệu trên Shopee"
        val reqInclusive = ShoppingTaskParser.parseRequest(inputInclusive)!!
        assertEquals("bàn phím cơ", reqInclusive.query)
        assertEquals(1_000_000L, reqInclusive.maxPrice)
        assertEquals(BudgetBoundary.INCLUSIVE_MAX, reqInclusive.budgetBoundary)

        val inputStrict = "tìm áo thun dưới 100k trên Shopee"
        val reqStrict = ShoppingTaskParser.parseRequest(inputStrict)!!
        assertEquals("áo thun", reqStrict.query)
        assertEquals(100_000L, reqStrict.maxPrice)
        assertEquals(BudgetBoundary.STRICTLY_BELOW, reqStrict.budgetBoundary)

        val inputUnder = "find keyboard under 500k on shopee"
        val reqUnder = ShoppingTaskParser.parseRequest(inputUnder)!!
        assertEquals("keyboard", reqUnder.query)
        assertEquals(500_000L, reqUnder.maxPrice)
        assertEquals(BudgetBoundary.STRICTLY_BELOW, reqUnder.budgetBoundary)
    }

    @Test
    fun `parse price range correctly in Vietnamese and English`() {
        val inputVi = "Mở Shopee tìm sạc dự phòng từ 200k đến 500k"
        val reqVi = ShoppingTaskParser.parseRequest(inputVi)!!
        assertEquals("sạc dự phòng", reqVi.query)
        assertEquals(200_000L, reqVi.minPrice)
        assertEquals(500_000L, reqVi.maxPrice)

        val inputEn = "look for powerbank from 200k to 500k on shopee"
        val reqEn = ShoppingTaskParser.parseRequest(inputEn)!!
        assertEquals("powerbank", reqEn.query)
        assertEquals(200_000L, reqEn.minPrice)
        assertEquals(500_000L, reqEn.maxPrice)
    }

    @Test
    fun `preserves product models, specs and sizes without corruption`() {
        val sonyReq = ShoppingTaskParser.parseRequest("tìm tai nghe sony wh-1000xm4 5tr trên shopee")!!
        assertEquals("tai nghe sony wh-1000xm4", sonyReq.query)
        assertEquals(5_000_000L, sonyReq.maxPrice)

        val iphoneReq = ShoppingTaskParser.parseRequest("tìm iphone 15 128gb dưới 15 triệu trên shopee")!!
        assertEquals("iphone 15 128gb", iphoneReq.query)
        assertEquals(15_000_000L, iphoneReq.maxPrice)
        assertEquals(BudgetBoundary.STRICTLY_BELOW, iphoneReq.budgetBoundary)

        val tvReq = ShoppingTaskParser.parseRequest("tìm tivi 4k dưới 10 triệu trên shopee")!!
        assertEquals("tivi 4k", tvReq.query)
        assertEquals(10_000_000L, tvReq.maxPrice)

        val shoeReq = ShoppingTaskParser.parseRequest("tìm giày size 42 trên shopee")!!
        assertEquals("giày size 42", shoeReq.query)
        assertNull(shoeReq.maxPrice)
    }

    @Test
    fun `empty product query returns NeedsInput instead of invented tai nghe`() {
        val result = ShoppingTaskParser.parse("tìm trên shopee")
        assertTrue(result is ShoppingParseResult.NeedsInput)
        val clarification = (result as ShoppingParseResult.NeedsInput).clarificationVi
        assertTrue(clarification.contains("Bạn muốn tìm sản phẩm gì"))

        val resultEn = ShoppingTaskParser.parse("find on shopee")
        assertTrue(resultEn is ShoppingParseResult.NeedsInput)
    }

    @Test
    fun `CommandParser routes compound shopping to Command FindProduct`() {
        val parser = CommandParser()
        val cmd = parser.parse("find t-shurt 100k on shopee")

        assertTrue(cmd is Command.FindProduct)
        val shoppingReq = (cmd as Command.FindProduct).request
        assertEquals("com.shopee.vn", shoppingReq.targetApp)
        assertEquals("t-shirt", shoppingReq.query)
        assertEquals(100_000L, shoppingReq.maxPrice)
        assertEquals(BudgetBoundary.INCLUSIVE_MAX, shoppingReq.budgetBoundary)
    }

    @Test
    fun `CommandParser preserves simple app open command`() {
        val parser = CommandParser()
        val cmdVi = parser.parse("Mở Shopee")
        assertTrue(cmdVi is Command.OpenApp)
        assertEquals("shopee", (cmdVi as Command.OpenApp).appName)

        val cmdEn = parser.parse("open shopee")
        assertTrue(cmdEn is Command.OpenApp)
        assertEquals("shopee", (cmdEn as Command.OpenApp).appName)
    }

    @Test
    fun `CommandParser keeps informational queries as AskAi`() {
        val parser = CommandParser()
        val cmd1 = parser.parse("what is Shopee?")
        assertTrue(cmd1 is Command.AskAi)

        val cmd2 = parser.parse("how do I find a shirt on Shopee?")
        assertTrue(cmd2 is Command.AskAi)

        val cmd3 = parser.parse("Shopee là gì?")
        assertTrue(cmd3 is Command.AskAi)
    }
}
