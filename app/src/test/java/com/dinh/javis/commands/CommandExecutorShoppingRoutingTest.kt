package com.dinh.javis.commands

import com.dinh.javis.agent.shopping.ProductSearchRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class CommandExecutorShoppingRoutingTest {

    @Test
    fun `routing verifies exact request maps to FindProduct and executes shopping without conversational AI`() {
        val parser = CommandParser()
        val input = "find t-shurt 100k on shopee"
        val cmd = parser.parse(input)

        // 1. Verify parser outcome
        assertTrue("Expected Command.FindProduct but got $cmd", cmd is Command.FindProduct)
        assertFalse("Must never parse as AskAi", cmd is Command.AskAi)

        val req = (cmd as Command.FindProduct).request
        assertEquals("com.shopee.vn", req.targetApp)
        assertEquals("t-shirt", req.query)
        assertEquals(100_000L, req.maxPrice)

        // 2. Real CommandExecutor dispatch verification (R5)
        val shoppingInvocations = mutableListOf<ProductSearchRequest>()
        val aiInvocations = mutableListOf<String>()
        val voiceInvocations = mutableListOf<String>()

        val executor = CommandExecutor(
            context = null,
            speaker = null,
            openAiClient = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onLogMessage = { _, _, _ -> },
            shoppingDispatcher = { shoppingReq, _ ->
                shoppingInvocations.add(shoppingReq)
            },
            aiDispatcher = { prompt ->
                aiInvocations.add(prompt)
                "Mock AI response"
            },
            voiceFeedback = { text ->
                voiceInvocations.add(text)
            }
        )

        executor.execute(cmd)

        // Assert exactly one shopping invocation with expected request
        assertEquals("Must execute shopping exactly once", 1, shoppingInvocations.size)
        assertEquals("t-shirt", shoppingInvocations.first().query)
        assertEquals(100_000L, shoppingInvocations.first().maxPrice)
        assertEquals("com.shopee.vn", shoppingInvocations.first().targetApp)

        // Assert ZERO conversational AI invocations
        assertEquals("Conversational AI must NEVER be invoked for shopping control", 0, aiInvocations.size)
    }

    @Test
    fun `informational question routes to AskAi and executes conversational AI without shopping control`() {
        val parser = CommandParser()
        val input = "what is Shopee?"
        val cmd = parser.parse(input)

        assertTrue("Expected Command.AskAi for informational query, got $cmd", cmd is Command.AskAi)
        assertFalse("Informational query must never route to FindProduct", cmd is Command.FindProduct)

        val shoppingInvocations = mutableListOf<ProductSearchRequest>()
        val aiInvocations = mutableListOf<String>()

        val executor = CommandExecutor(
            context = null,
            speaker = null,
            openAiClient = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onLogMessage = { _, _, _ -> },
            shoppingDispatcher = { shoppingReq, _ ->
                shoppingInvocations.add(shoppingReq)
            },
            aiDispatcher = { prompt ->
                aiInvocations.add(prompt)
                "Shopee là sàn thương mại điện tử..."
            }
        )

        executor.execute(cmd)

        // Assert exactly one AI invocation
        assertEquals("Conversational AI must be invoked for informational question", 1, aiInvocations.size)
        assertEquals("what is Shopee?", aiInvocations.first())

        // Assert ZERO shopping invocations
        assertEquals("Shopping execution must NEVER be invoked for informational question", 0, shoppingInvocations.size)
    }

    @Test
    fun `incomplete shopping request routes to Clarify without invoking physical actions or conversational AI`() {
        val parser = CommandParser()
        val input = "tìm trên shopee"
        val cmd = parser.parse(input)

        assertTrue("Expected Command.Clarify for missing product name, got $cmd", cmd is Command.Clarify)

        val shoppingInvocations = mutableListOf<ProductSearchRequest>()
        val aiInvocations = mutableListOf<String>()
        val voiceInvocations = mutableListOf<String>()

        val executor = CommandExecutor(
            context = null,
            speaker = null,
            openAiClient = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onLogMessage = { _, _, _ -> },
            shoppingDispatcher = { shoppingReq, _ ->
                shoppingInvocations.add(shoppingReq)
            },
            aiDispatcher = { prompt ->
                aiInvocations.add(prompt)
                "AI"
            },
            voiceFeedback = { text ->
                voiceInvocations.add(text)
            }
        )

        executor.execute(cmd)

        assertEquals("No shopping physical action should be dispatched", 0, shoppingInvocations.size)
        assertEquals("No conversational AI should be invoked", 0, aiInvocations.size)
        assertEquals("Clarification voice feedback must be provided", 1, voiceInvocations.size)
        assertTrue(voiceInvocations.first().contains("Bạn muốn tìm sản phẩm gì"))
    }
}
