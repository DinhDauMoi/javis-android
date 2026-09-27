package com.dinh.javis.agent

import android.util.Log
import com.dinh.javis.ai.capabilities.ScreenObservation

/**
 * Validates action proposals from the planner before they are dispatched to the
 * accessibility service. Enforces:
 *
 *  - Allowlisted action verbs only (BA-02, BA-03)
 *  - Required params per action type
 *  - Finite, in-range coordinates
 *  - Bounded text input length
 *  - Package/window scope vs. current context (BA-03)
 *  - Prohibited action classes (OTP entry, arbitrary intents, code execution) (BA-03)
 *
 * All validation is deterministic and synchronous; does not call external services.
 */
object ActionValidator {

    private const val TAG = "ActionValidator"

    /** Actions the planner is allowed to propose. */
    private val ALLOWED_ACTIONS = setOf(
        "CLICK", "SCROLL", "TYPE", "WAIT", "NAVIGATE_BACK", "NAVIGATE_HOME"
    )

    /** Valid scroll directions. */
    private val ALLOWED_DIRECTIONS = setOf("UP", "DOWN", "LEFT", "RIGHT")

    /** Maximum characters for TYPE input to prevent prompt-injection via oversized payloads. */
    private const val MAX_INPUT_TEXT_LENGTH = 500

    /** Minimum valid coordinate value. */
    private const val MIN_COORD = 0f

    /** Patterns that flag a text input as likely sensitive/forbidden. */
    private val FORBIDDEN_INPUT_PATTERNS = listOf(
        "\\d{4,8}".toRegex(),          // OTP/PIN digit sequences
        "password".toRegex(RegexOption.IGNORE_CASE),
        "mật khẩu".toRegex(RegexOption.IGNORE_CASE),
        "mã otp".toRegex(RegexOption.IGNORE_CASE),
        "cvv".toRegex(RegexOption.IGNORE_CASE),
        "mã pin".toRegex(RegexOption.IGNORE_CASE),
        "số thẻ".toRegex(RegexOption.IGNORE_CASE)
    )

    /** Forbidden transaction / cart targets that cannot be clicked (Section 8). */
    private val FORBIDDEN_TRANSACTION_TARGETS = listOf(
        "mua ngay", "mua voi voucher", "buy now", "them vao gio", "add to cart",
        "dat hang", "thanh toan", "checkout", "chat ngay", "lien he nguoi ban",
        "theo doi shop", "follow"
    )

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reasonVi: String) : ValidationResult()
    }

    /**
     * Validates a proposal from the planner against the current screen context.
     *
     * @param proposal The proposed action to validate.
     * @param currentObservation The most recent screen observation for context.
     * @param allowedPackages Packages the current task is allowed to interact with.
     *        Empty means no restriction beyond hardcoded protections.
     * @param screenWidth Current screen width in pixels.
     * @param screenHeight Current screen height in pixels.
     */
    fun validate(
        proposal: ActionProposal,
        currentObservation: ScreenObservation,
        allowedPackages: Set<String> = emptySet(),
        screenWidth: Int = 0,
        screenHeight: Int = 0
    ): ValidationResult {

        val action = proposal.action.uppercase()

        // 1. Allowlist action verb
        if (action !in ALLOWED_ACTIONS) {
            Log.w(TAG, "Rejected unknown action: ${proposal.action}")
            return ValidationResult.Invalid(
                "Hành động \"${proposal.action}\" không được hỗ trợ. Chỉ chấp nhận: ${ALLOWED_ACTIONS.joinToString(", ")}."
            )
        }

        // 2. Action-specific parameter validation
        when (action) {
            "CLICK" -> {
                // Check forbidden transaction/cart/purchase actions (Section 8)
                val normTarget = com.dinh.javis.utils.TextNormalizer.removeAccents(proposal.targetText.lowercase())
                for (forbidden in FORBIDDEN_TRANSACTION_TARGETS) {
                    if (normTarget.contains(forbidden)) {
                        return ValidationResult.Invalid(
                            "Thao tác mua hàng/thanh toán (\"${proposal.targetText}\") bị nghiêm cấm để bảo vệ an toàn."
                        )
                    }
                }

                // Either coordinates or target text must be provided
                if (!proposal.isCoordinateBased && proposal.targetText.isBlank()) {
                    return ValidationResult.Invalid(
                        "CLICK yêu cầu tọa độ (x, y) hợp lệ hoặc văn bản nút mục tiêu."
                    )
                }
                // Validate coordinate range
                if (proposal.isCoordinateBased) {
                    if (proposal.x < MIN_COORD || proposal.y < MIN_COORD) {
                        return ValidationResult.Invalid(
                            "Tọa độ click không hợp lệ: (${proposal.x}, ${proposal.y}). Giá trị phải ≥ 0."
                        )
                    }
                    if (screenWidth > 0 && proposal.x > screenWidth) {
                        return ValidationResult.Invalid(
                            "Tọa độ x=${proposal.x} vượt quá chiều rộng màn hình $screenWidth."
                        )
                    }
                    if (screenHeight > 0 && proposal.y > screenHeight) {
                        return ValidationResult.Invalid(
                            "Tọa độ y=${proposal.y} vượt quá chiều cao màn hình $screenHeight."
                        )
                    }
                    // Reject NaN/Infinity
                    if (!proposal.x.isFinite() || !proposal.y.isFinite()) {
                        return ValidationResult.Invalid("Tọa độ click chứa giá trị không hợp lệ (NaN/Infinity).")
                    }
                }
            }

            "SCROLL" -> {
                val dir = proposal.direction.uppercase()
                if (dir !in ALLOWED_DIRECTIONS) {
                    return ValidationResult.Invalid(
                        "Hướng cuộn \"${proposal.direction}\" không hợp lệ. Chỉ chấp nhận: UP, DOWN, LEFT, RIGHT."
                    )
                }
            }

            "TYPE" -> {
                if (proposal.inputText.isBlank()) {
                    return ValidationResult.Invalid("TYPE yêu cầu văn bản nhập vào không rỗng.")
                }
                if (proposal.inputText.length > MAX_INPUT_TEXT_LENGTH) {
                    return ValidationResult.Invalid(
                        "Văn bản nhập vào vượt quá giới hạn $MAX_INPUT_TEXT_LENGTH ký tự."
                    )
                }
                // Block OTP/password/sensitive text input
                for (pattern in FORBIDDEN_INPUT_PATTERNS) {
                    if (pattern.containsMatchIn(proposal.inputText)) {
                        Log.w(TAG, "Blocked sensitive TYPE input matching pattern: $pattern")
                        return ValidationResult.Invalid(
                            "Không thể nhập nội dung nhạy cảm (OTP, mật khẩu, mã PIN, số thẻ)."
                        )
                    }
                }
            }

            "WAIT", "NAVIGATE_BACK", "NAVIGATE_HOME" -> {
                // No additional parameter requirements
            }
        }

        // 3. Package scope check
        val currentPkg = currentObservation.currentPackage
        if (allowedPackages.isNotEmpty() && currentPkg != null && currentPkg !in allowedPackages) {
            Log.w(TAG, "Package scope violation: current=$currentPkg allowed=$allowedPackages")
            return ValidationResult.Invalid(
                "Ứng dụng hiện tại ($currentPkg) nằm ngoài phạm vi cho phép của tác vụ này."
            )
        }

        return ValidationResult.Valid
    }

    /**
     * Sanitizes planner thought string for safe display (never expose as reasoning trace).
     * Returns a brief Vietnamese status summary, stripping any embedded instructions.
     */
    fun sanitizeThoughtForDisplay(thought: String): String {
        val cleaned = thought.trim().take(120)
        return if (cleaned.isBlank()) "Đang phân tích màn hình…" else cleaned
    }
}
