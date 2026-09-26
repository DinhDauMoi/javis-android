package com.dinh.javis.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanningJsonParsingTest {

    private fun parseActionPlanJson(rawResponse: String): Pair<String, String> {
        var cleanJson = rawResponse.trim()
        if (cleanJson.startsWith("```json")) {
            cleanJson = cleanJson.removePrefix("```json")
        }
        if (cleanJson.startsWith("```")) {
            cleanJson = cleanJson.removePrefix("```")
        }
        if (cleanJson.endsWith("```")) {
            cleanJson = cleanJson.removeSuffix("```")
        }
        cleanJson = cleanJson.trim()

        return try {
            val obj = JSONObject(cleanJson)
            val thought = obj.optString("thought", "")
            val action = obj.optString("action", "TERMINATE").uppercase()
            Pair(thought, action)
        } catch (e: Exception) {
            Pair("Lỗi parse", "TERMINATE")
        }
    }

    @Test
    fun testParseStandardJson() {
        val json = """
            {
              "thought": "Chạm vào ô tìm kiếm",
              "action": "CLICK",
              "params": {
                "x": 540,
                "y": 1200,
                "text": "",
                "direction": "DOWN"
              },
              "isGoalComplete": false
            }
        """.trimIndent()

        val (thought, action) = parseActionPlanJson(json)
        assertEquals("Chạm vào ô tìm kiếm", thought)
        assertEquals("CLICK", action)
    }

    @Test
    fun testParseMarkdownWrappedJson() {
        val markdownJson = """
            ```json
            {
              "thought": "Cuộn xuống để xem thêm kết quả",
              "action": "SCROLL",
              "params": {
                "direction": "DOWN"
              },
              "isGoalComplete": false
            }
            ```
        """.trimIndent()

        val (thought, action) = parseActionPlanJson(markdownJson)
        assertEquals("Cuộn xuống để xem thêm kết quả", thought)
        assertEquals("SCROLL", action)
    }

    @Test
    fun testParseMalformedJsonFallsBackSafely() {
        val malformed = "Tôi nghĩ nên bấm nút tìm kiếm nhưng không trả về JSON"
        val (_, action) = parseActionPlanJson(malformed)
        assertEquals("TERMINATE", action)
    }
}
