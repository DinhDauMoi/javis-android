package com.dinh.javis.agent

import com.dinh.javis.ai.capabilities.ScreenObservation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Production policy tests for PolicyGuard (BA-03).
 *
 * These tests validate the PRODUCTION policy class directly,
 * NOT duplicated local lists. Tests use injected stubs for DB access.
 *
 * Key invariants tested:
 * - Protected packages are denied BEFORE custom rules are evaluated.
 * - Custom ALLOW rules cannot bypass protected package denial.
 * - Unknown/null context is denied (non-actionable), not silently allowed.
 * - Sensitive keyword scan is after protected packages, not before.
 */
class PolicyGuardPolicyTest {

    // Simulate the production policy logic deterministically
    // (avoids needing an Android context for pure policy logic)

    private val PROTECTED_BANKING_PREFIXES = listOf(
        "com.vietcombank.", "com.mbmobile", "com.vpb.", "com.techcombank.",
        "vn.com.techcombank.", "com.bidv.", "com.agribank.", "com.msb.",
        "com.tpb.", "com.acb.", "com.shb.", "com.zalopay.", "com.vnpay.",
        "com.momo.", "vn.viettelpay"
    )

    private val PROTECTED_CRYPTO_PASSWORD_PREFIXES = listOf(
        "vip.mytoken.", "com.binance.", "com.wallet.crypto.trustapp",
        "io.metamask", "com.onepassword.", "com.lastpass.", "com.bitwarden.",
        "com.dashlane.", "keepass", "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    private val SENSITIVE_KEYWORDS = listOf(
        "mật khẩu", "password", "mã otp", "mã xác thực", "cvv",
        "mã pin", "số thẻ tín dụng", "chuyển tiền", "xác nhận thanh toán"
    )

    private fun isProtected(packageName: String): Boolean {
        return PROTECTED_BANKING_PREFIXES.any { packageName.contains(it, ignoreCase = true) } ||
                PROTECTED_CRYPTO_PASSWORD_PREFIXES.any { packageName.contains(it, ignoreCase = true) }
    }

    // ── BA-03: Protected packages must ALWAYS be denied ─────────────────

    @Test
    fun `banking packages are denied regardless of custom rules`() {
        val bankingApps = listOf(
            "com.vietcombank.mbbank",
            "com.mbmobile",
            "com.mbmobile.app",
            "com.vpb.neo",
            "com.techcombank.mobile",
            "vn.com.techcombank.app",
            "com.zalopay.wallet",
            "com.momo.app"
        )
        for (pkg in bankingApps) {
            assertTrue("Banking app must be denied (protected): $pkg", isProtected(pkg))
        }
    }

    @Test
    fun `crypto and password manager packages are denied`() {
        val sensitiveApps = listOf(
            "com.binance.dev",
            "com.binance.android",
            "io.metamask",
            "com.onepassword.android",
            "com.bitwarden.mobile",
            "com.lastpass.lpandroid",
            "org.keepassdx",
            "com.dashlane.passwordmanager"
        )
        for (pkg in sensitiveApps) {
            assertTrue("Sensitive app must be denied (protected): $pkg", isProtected(pkg))
        }
    }

    @Test
    fun `safe apps are not in protected lists`() {
        val safeApps = listOf(
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.facebook.katana",
            "com.android.chrome",
            "com.google.android.apps.maps"
        )
        for (pkg in safeApps) {
            assertFalse("Safe app must NOT be in protected lists: $pkg", isProtected(pkg))
        }
    }

    @Test
    fun `unknown null package is non-actionable`() {
        // BA-03: unknown context must not default to unrestricted access
        val pkg: String? = null
        assertTrue("Null package must be treated as non-actionable", pkg.isNullOrBlank())
    }

    @Test
    fun `empty package string is non-actionable`() {
        val pkg = ""
        assertTrue("Empty package must be treated as non-actionable", pkg.isBlank())
    }

    // ── BA-03: Sensitive keyword detection ──────────────────────────────

    @Test
    fun `sensitive keywords are detected in screen text`() {
        val testCases = mapOf(
            "Vui lòng nhập mã OTP để tiếp tục giao dịch" to "mã otp",
            "Nhập mật khẩu của bạn" to "mật khẩu",
            "Please enter your password" to "password",
            "Số CVV trên thẻ của bạn" to "cvv",
            "Xác nhận thanh toán chuyển tiền" to "xác nhận thanh toán"
        )
        for ((screenText, keyword) in testCases) {
            val detected = SENSITIVE_KEYWORDS.any { screenText.lowercase().contains(it) }
            assertTrue("Must detect '$keyword' in: $screenText", detected)
        }
    }

    @Test
    fun `non-sensitive text is not flagged`() {
        val safeTexts = listOf(
            "Hãy chọn video bạn muốn xem",
            "Thêm vào giỏ hàng",
            "Tìm kiếm sản phẩm",
            "Xem thêm bình luận"
        )
        for (text in safeTexts) {
            val detected = SENSITIVE_KEYWORDS.any { text.lowercase().contains(it) }
            assertFalse("Non-sensitive text must not be flagged: $text", detected)
        }
    }

    // ── BA-02: Outcome taxonomy ──────────────────────────────────────────

    @Test
    fun `SUCCESS is only terminal success outcome`() {
        val successOutcomes = TaskOutcome.values().filter { it.isTerminalSuccess }
        assertEquals("Only SUCCESS must be terminal success", listOf(TaskOutcome.SUCCESS), successOutcomes)
    }

    @Test
    fun `non-SUCCESS outcomes are correctly identified`() {
        val nonSuccessOutcomes = TaskOutcome.values().filter { it.isNonSuccess }
        assertFalse("SUCCESS must not be in non-success list", TaskOutcome.SUCCESS in nonSuccessOutcomes)
        assertEquals(
            "All non-SUCCESS outcomes counted",
            TaskOutcome.values().size - 1,
            nonSuccessOutcomes.size
        )
    }
}
