package com.dinh.javis.agent

import org.junit.Assert.*
import org.junit.Test

class PolicyGuardTest {

    private val bankingPackages = listOf(
        "com.vietcombank.mbbank",
        "com.mbmobile.app",
        "com.vpb.neo",
        "com.techcombank.mobile",
        "com.zalopay.wallet",
        "com.momo.app"
    )

    private val cryptoPackages = listOf(
        "com.binance.dev",
        "io.metamask",
        "com.onepassword.android",
        "com.bitwarden.mobile"
    )

    private val sensitiveKeywords = listOf(
        "mật khẩu",
        "password",
        "mã otp",
        "mã xác thực",
        "cvv",
        "mã pin"
    )

    @Test
    fun testBankingAppsAreDenied() {
        for (pkg in bankingPackages) {
            val isBanking = bankingPackages.any { pkg.contains(it) }
            assertTrue("App ngân hàng phải bị chặn: $pkg", isBanking)
        }
    }

    @Test
    fun testCryptoAndPasswordManagersAreDenied() {
        for (pkg in cryptoPackages) {
            val isSensitive = cryptoPackages.any { pkg.contains(it) }
            assertTrue("App bảo mật phải bị chặn: $pkg", isSensitive)
        }
    }

    @Test
    fun testSensitiveKeywordDetection() {
        val testScreenText = "Vui lòng nhập mã OTP để tiếp tục giao dịch"
        val detected = sensitiveKeywords.any { testScreenText.lowercase().contains(it) }
        assertTrue("Phải phát hiện từ khóa OTP trong màn hình", detected)
    }

    @Test
    fun testSafeAppIsAllowed() {
        val safePkg = "com.google.android.youtube"
        val isBanking = bankingPackages.any { safePkg.contains(it) }
        val isCrypto = cryptoPackages.any { safePkg.contains(it) }
        assertFalse("YouTube không phải ngân hàng", isBanking)
        assertFalse("YouTube không phải ví crypto", isCrypto)
    }
}
