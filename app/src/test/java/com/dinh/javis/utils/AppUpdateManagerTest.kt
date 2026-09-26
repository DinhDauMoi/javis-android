package com.dinh.javis.utils

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for versionCode extraction logic and update availability checking from GitHub Releases
 */
class AppUpdateManagerTest {

    private fun extractRemoteVersionCode(tagName: String, releaseName: String): Long {
        return Regex("""(?:build-|\.)(\d+)$""").find(tagName)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""(\d+)$""").find(tagName)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""Build\s*#?(\d+)""", RegexOption.IGNORE_CASE).find(releaseName)?.groupValues?.get(1)?.toLongOrNull()
            ?: 0L
    }

    private fun isUpdateAvailable(remoteVersionCode: Long, currentVersionCode: Long): Boolean {
        return remoteVersionCode > currentVersionCode
    }

    @Test
    fun testExtractVersionCodeFromVTag() {
        val versionCode = extractRemoteVersionCode("v1.0.14", "JAVIS v1.0.14")
        assertEquals(14L, versionCode)
    }

    @Test
    fun testExtractVersionCodeFromBuildTag() {
        val versionCode = extractRemoteVersionCode("build-14", "JAVIS v1.0.14 (Build #14)")
        assertEquals(14L, versionCode)
    }

    @Test
    fun testExtractVersionCodeFromNumericTag() {
        val versionCode = extractRemoteVersionCode("15", "JAVIS Build 15")
        assertEquals(15L, versionCode)
    }

    @Test
    fun testExtractVersionCodeFallbackFromReleaseName() {
        val versionCode = extractRemoteVersionCode("latest", "JAVIS v1.0.16 (Build #16)")
        assertEquals(16L, versionCode)
    }

    @Test
    fun testUpdateAvailableComparison() {
        assertTrue(isUpdateAvailable(remoteVersionCode = 14L, currentVersionCode = 11L))
        assertTrue(isUpdateAvailable(remoteVersionCode = 14L, currentVersionCode = 12L))
        assertFalse(isUpdateAvailable(remoteVersionCode = 14L, currentVersionCode = 14L))
        assertFalse(isUpdateAvailable(remoteVersionCode = 14L, currentVersionCode = 15L))
    }
}
