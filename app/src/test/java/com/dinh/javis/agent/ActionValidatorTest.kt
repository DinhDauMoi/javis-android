package com.dinh.javis.agent

import com.dinh.javis.ai.capabilities.ScreenObservation
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ActionValidator (BA-02, BA-03).
 * Covers:
 * - Allowlist enforcement (unknown actions rejected)
 * - Missing required params per action type
 * - Coordinate validation (out-of-range, non-finite)
 * - Text input bounds and sensitive content blocking
 * - Package scope checking
 * - Scroll direction validation
 */
class ActionValidatorTest {

    private fun obs(pkg: String? = "com.example.app") = ScreenObservation(
        currentPackage = pkg,
        screenshotWidth = 1080,
        screenshotHeight = 2340
    )

    private fun proposal(
        action: String,
        x: Float = 0f,
        y: Float = 0f,
        targetText: String = "",
        inputText: String = "",
        direction: String = "DOWN"
    ) = ActionProposal(
        runId = "test-run",
        stepIndex = 1,
        action = action,
        x = x,
        y = y,
        targetText = targetText,
        inputText = inputText,
        direction = direction
    )

    // ── Allowlist ───────────────────────────────────────────────────────

    @Test
    fun `unknown action verbs are rejected`() {
        val unknownActions = listOf("INTENT", "EXECUTE", "INSTALL", "BROADCAST", "ARBITRARY")
        for (action in unknownActions) {
            val result = ActionValidator.validate(proposal(action), obs())
            assertTrue("Unknown action '$action' must be rejected",
                result is ActionValidator.ValidationResult.Invalid)
        }
    }

    @Test
    fun `allowed actions pass allowlist check`() {
        val allowed = listOf("CLICK", "SCROLL", "TYPE", "WAIT", "NAVIGATE_BACK", "NAVIGATE_HOME")
        for (action in allowed) {
            val p = when (action) {
                "CLICK" -> proposal(action, x = 540f, y = 1000f)
                "SCROLL" -> proposal(action, direction = "DOWN")
                "TYPE" -> proposal(action, inputText = "hello world")
                else -> proposal(action)
            }
            val result = ActionValidator.validate(p, obs(), screenWidth = 1080, screenHeight = 2340)
            assertTrue("Allowed action '$action' must pass: $result",
                result is ActionValidator.ValidationResult.Valid)
        }
    }

    // ── CLICK validation ────────────────────────────────────────────────

    @Test
    fun `CLICK with no coords and no target text is rejected`() {
        val result = ActionValidator.validate(proposal("CLICK"), obs())
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `CLICK with valid coordinates passes`() {
        val result = ActionValidator.validate(
            proposal("CLICK", x = 540f, y = 1000f), obs(),
            screenWidth = 1080, screenHeight = 2340
        )
        assertTrue(result is ActionValidator.ValidationResult.Valid)
    }

    @Test
    fun `CLICK with negative coordinates is rejected`() {
        val result = ActionValidator.validate(
            proposal("CLICK", x = -10f, y = 500f), obs(),
            screenWidth = 1080, screenHeight = 2340
        )
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `CLICK with out-of-screen coordinates is rejected`() {
        val result = ActionValidator.validate(
            proposal("CLICK", x = 1500f, y = 1000f), obs(),
            screenWidth = 1080, screenHeight = 2340
        )
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `CLICK with NaN coordinates is rejected`() {
        val result = ActionValidator.validate(
            proposal("CLICK", x = Float.NaN, y = 1000f), obs()
        )
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `CLICK with target text passes when no coordinates`() {
        val result = ActionValidator.validate(
            proposal("CLICK", targetText = "Xem chi tiết"), obs()
        )
        assertTrue(result is ActionValidator.ValidationResult.Valid)
    }

    // ── SCROLL validation ───────────────────────────────────────────────

    @Test
    fun `SCROLL with invalid direction is rejected`() {
        val result = ActionValidator.validate(proposal("SCROLL", direction = "DIAGONAL"), obs())
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `SCROLL with valid directions pass`() {
        for (dir in listOf("UP", "DOWN", "LEFT", "RIGHT")) {
            val result = ActionValidator.validate(proposal("SCROLL", direction = dir), obs())
            assertTrue("SCROLL $dir must be valid: $result",
                result is ActionValidator.ValidationResult.Valid)
        }
    }

    // ── TYPE validation ─────────────────────────────────────────────────

    @Test
    fun `TYPE with empty text is rejected`() {
        val result = ActionValidator.validate(proposal("TYPE", inputText = ""), obs())
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `TYPE with oversized text is rejected`() {
        val longText = "a".repeat(501)
        val result = ActionValidator.validate(proposal("TYPE", inputText = longText), obs())
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `TYPE with OTP digit pattern is rejected`() {
        val result = ActionValidator.validate(proposal("TYPE", inputText = "123456"), obs())
        assertTrue("OTP digit sequence must be blocked", result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `TYPE with password keyword is rejected`() {
        val result = ActionValidator.validate(proposal("TYPE", inputText = "my password here"), obs())
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `TYPE with normal search text passes`() {
        val result = ActionValidator.validate(proposal("TYPE", inputText = "áo thun nam đen"), obs())
        assertTrue(result is ActionValidator.ValidationResult.Valid)
    }

    // ── Package scope ───────────────────────────────────────────────────

    @Test
    fun `action on disallowed package is rejected when scope is restricted`() {
        val allowedPackages = setOf("com.google.android.youtube")
        val result = ActionValidator.validate(
            proposal("CLICK", x = 540f, y = 1000f),
            obs(pkg = "com.tiktok.unknown"),
            allowedPackages = allowedPackages,
            screenWidth = 1080,
            screenHeight = 2340
        )
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `action on allowed package passes scope check`() {
        val allowedPackages = setOf("com.google.android.youtube")
        val result = ActionValidator.validate(
            proposal("CLICK", x = 540f, y = 1000f),
            obs(pkg = "com.google.android.youtube"),
            allowedPackages = allowedPackages,
            screenWidth = 1080,
            screenHeight = 2340
        )
        assertTrue(result is ActionValidator.ValidationResult.Valid)
    }

    @Test
    fun `empty allowed packages means no scope restriction`() {
        val result = ActionValidator.validate(
            proposal("CLICK", x = 540f, y = 1000f),
            obs(pkg = "com.anything.random"),
            allowedPackages = emptySet(),
            screenWidth = 1080,
            screenHeight = 2340
        )
        assertTrue(result is ActionValidator.ValidationResult.Valid)
    }

    // ── Transaction targets (Section 8) ─────────────────────────────────

    @Test
    fun `CLICK on purchase or cart targets is strictly rejected`() {
        val forbidden = listOf(
            "Mua ngay",
            "Thêm vào giỏ hàng",
            "Mua với voucher",
            "Đặt hàng",
            "Thanh toán",
            "Checkout",
            "Chat ngay với người bán"
        )
        for (target in forbidden) {
            val result = ActionValidator.validate(
                proposal("CLICK", targetText = target),
                obs(pkg = "com.shopee.vn")
            )
            assertTrue("Target '$target' must be rejected as forbidden transaction",
                result is ActionValidator.ValidationResult.Invalid)
        }
    }
}
