package com.dinh.javis.commands

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CommandParserVisionTest {

    private lateinit var parser: CommandParser

    @Before
    fun setUp() {
        parser = CommandParser(emptyList())
    }

    @Test
    fun testParseAnalyzeScreen() {
        val input = "javis nhìn màn hình xem có gì"
        val cmd = parser.parse(input)
        assertTrue("Lệnh phải là AnalyzeScreen", cmd is Command.AnalyzeScreen)
    }

    @Test
    fun testParseRunBehaviorAgent() {
        val input = "tự động tìm kiếm video trên youtube"
        val cmd = parser.parse(input)
        assertTrue("Lệnh phải là RunBehaviorAgent", cmd is Command.RunBehaviorAgent)
        val agentCmd = cmd as Command.RunBehaviorAgent
        assertTrue("Mục tiêu phải chứa thông tin", agentCmd.goal.contains("youtube"))
    }

    @Test
    fun testPreserveLegacyCommands() {
        val scrollUp = parser.parse("lướt lên")
        assertTrue(scrollUp is Command.ScrollUp)

        val scrollDown = parser.parse("lướt xuống")
        assertTrue(scrollDown is Command.ScrollDown)

        val openYoutube = parser.parse("mở youtube")
        assertTrue(openYoutube is Command.OpenApp)
    }
}
