package com.dinh.javis.agent.shopping

import com.dinh.javis.commands.Command
import com.dinh.javis.commands.CommandParser
import org.junit.Assert.*
import org.junit.Test

class ShoppingTaskParserTest {

    @Test
    fun `isShoppingIntent correctly identifies shopping requests`() {
        assertTrue(ShoppingTaskParser.isShoppingIntent("Mở Shopee tìm tai nghe bluetooth dưới 500k"))
        assertTrue(ShoppingTaskParser.isShoppingIntent("Tìm giúp tôi ốp lưng trên Shopee"))
        assertTrue(ShoppingTaskParser.isShoppingIntent("shopee tìm chuột không dây"))
        // Simple app launch is NOT shopping intent
        assertFalse(ShoppingTaskParser.isShoppingIntent("Mở Shopee"))
        assertFalse(ShoppingTaskParser.isShoppingIntent("Mở YouTube"))
    }

    @Test
    fun `parse compound shopping request with budget and preferences`() {
        val input = "Mở Shopee, tìm giúp tôi tai nghe Bluetooth dưới 500 nghìn, đánh giá tốt, rồi mở sản phẩm phù hợp nhất."
        val req = ShoppingTaskParser.parse(input)

        assertEquals("com.shopee.vn", req.targetApp)
        assertTrue(req.query.contains("tai nghe"))
        assertEquals(500_000L, req.maxPrice)
        assertEquals(BudgetBoundary.STRICTLY_BELOW, req.budgetBoundary)
        assertTrue(req.requiredSpecifications.contains("Bluetooth"))
        assertTrue(req.qualityPreferences.contains("đánh giá tốt"))
    }

    @Test
    fun `parse currency amounts correctly`() {
        assertEquals(500_000L, ShoppingTaskParser.parseSingleAmount("500k"))
        assertEquals(500_000L, ShoppingTaskParser.parseSingleAmount("500 nghìn"))
        assertEquals(1_500_000L, ShoppingTaskParser.parseSingleAmount("1.5 triệu"))
        assertEquals(1_500_000L, ShoppingTaskParser.parseSingleAmount("1tr5"))
        assertEquals(2_000_000L, ShoppingTaskParser.parseSingleAmount("2tr"))
    }

    @Test
    fun `parse inclusive maximum boundary`() {
        val input = "Tìm bàn phím cơ không quá 1 triệu trên Shopee"
        val req = ShoppingTaskParser.parse(input)

        assertEquals(1_000_000L, req.maxPrice)
        assertEquals(BudgetBoundary.INCLUSIVE_MAX, req.budgetBoundary)
    }

    @Test
    fun `parse price range correctly`() {
        val input = "Mở Shopee tìm sạc dự phòng từ 200k đến 500k"
        val req = ShoppingTaskParser.parse(input)

        assertEquals(200_000L, req.minPrice)
        assertEquals(500_000L, req.maxPrice)
    }

    @Test
    fun `CommandParser routes compound shopping to Command FindProduct`() {
        val parser = CommandParser()
        val cmd = parser.parse("Mở Shopee tìm tai nghe Bluetooth dưới 500k")

        assertTrue(cmd is Command.FindProduct)
        val shoppingReq = (cmd as Command.FindProduct).request
        assertEquals("com.shopee.vn", shoppingReq.targetApp)
        assertEquals(500_000L, shoppingReq.maxPrice)
    }

    @Test
    fun `CommandParser preserves simple app open command`() {
        val parser = CommandParser()
        val cmd = parser.parse("Mở Shopee")

        assertTrue(cmd is Command.OpenApp)
        assertEquals("shopee", (cmd as Command.OpenApp).appName)
    }
}
