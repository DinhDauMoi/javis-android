package com.dinh.javis.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test suite for CommandParser voice command recognition.
 * Verifies volume adjustments, app launches, screen perception intents,
 * and Vietnamese diacritic handling.
 */
class CommandParserVoiceRegressionTest {

    private lateinit var parser: CommandParser

    @Before
    fun setUp() {
        parser = CommandParser()
    }

    @Test
    fun volumeUp_withAccentsAndUnaccented_parsesCorrectly() {
        assertEquals(Command.ChangeVolume(Command.VolumeAction.UP), parser.parse("tăng âm lượng"))
        assertEquals(Command.ChangeVolume(Command.VolumeAction.UP), parser.parse("tang am luong"))
        assertEquals(Command.ChangeVolume(Command.VolumeAction.UP), parser.parse("to lên"))
        assertEquals(Command.ChangeVolume(Command.VolumeAction.UP), parser.parse("volume up"))
        assertEquals(Command.ChangeVolume(Command.VolumeAction.UP), parser.parse("louder"))
    }

    @Test
    fun volumeDown_withAccentsAndUnaccented_parsesCorrectly() {
        assertEquals(Command.ChangeVolume(Command.VolumeAction.DOWN), parser.parse("giảm âm lượng"))
        assertEquals(Command.ChangeVolume(Command.VolumeAction.DOWN), parser.parse("giam am luong"))
        assertEquals(Command.ChangeVolume(Command.VolumeAction.DOWN), parser.parse("nhỏ lại"))
        assertEquals(Command.ChangeVolume(Command.VolumeAction.DOWN), parser.parse("volume down"))
        assertEquals(Command.ChangeVolume(Command.VolumeAction.DOWN), parser.parse("quieter"))
    }

    @Test
    fun popularApps_openWithOrWithoutKeyword() {
        assertEquals(Command.OpenApp("youtube"), parser.parse("mở youtube"))
        assertEquals(Command.OpenApp("tiktok"), parser.parse("mở tik tok"))
        assertEquals(Command.OpenApp("facebook"), parser.parse("mở facebook"))
        assertEquals(Command.OpenApp("zalo"), parser.parse("mở zalo"))
        assertEquals(Command.OpenApp("camera"), parser.parse("máy ảnh"))
        assertEquals(Command.OpenApp("settings"), parser.parse("cài đặt"))
    }

    @Test
    fun screenAnalysis_intentsRecognized() {
        val cmd1 = parser.parse("nhìn màn hình xem có gì")
        assertTrue(cmd1 is Command.AnalyzeScreen)

        val cmd2 = parser.parse("doc man hinh giup toi")
        assertTrue(cmd2 is Command.AnalyzeScreen)
    }
}
