package com.dinh.javis.agent

import android.content.Context
import android.util.Log
import com.dinh.javis.ai.capabilities.ScreenObservation
import com.dinh.javis.data.AppDatabase
import com.dinh.javis.data.PolicyRule

enum class PolicyDecision {
    ALLOW,
    DENY,
    REQUIRE_CONFIRM
}

data class PolicyCheckResult(
    val decision: PolicyDecision,
    val reason: String = ""
)

/**
 * Safety guardrail policy engine (BA-03).
 *
 * Policy evaluation order (fixed from baseline):
 * 1. Null/empty package check → ALLOW (no context to evaluate).
 * 2. **Mandatory hardcoded deny list first** (banking, crypto, password managers).
 *    Custom DB rules CANNOT override these protections.
 * 3. Custom DB rules (user-defined allow/deny/require-confirm).
 * 4. Sensitive keyword scan on visible text.
 *
 * Security invariants:
 * - Protected packages cannot be bypassed by any custom rule.
 * - Unknown or unreadable context (null package) is treated as non-actionable.
 * - Screen text and model output are untrusted; neither changes scope or policy.
 * - Upload/observation/action boundaries each check the current package before proceeding.
 * - Model output cannot modify policy lists at runtime.
 */
class PolicyGuard(private val context: Context) {

    private val TAG = "PolicyGuard"
    private val database = AppDatabase.getDatabase(context)

    // ──────────────────────────────────────────────────────────────────────
    // Hardcoded protected deny lists — CANNOT be overridden by custom rules
    // ──────────────────────────────────────────────────────────────────────

    private val PROTECTED_BANKING_PREFIXES = listOf(
        "com.vietcombank.",
        "com.mbmobile",
        "com.vpb.",
        "com.techcombank.",
        "vn.com.techcombank.",
        "com.bidv.",
        "com.agribank.",
        "com.msb.",
        "com.tpb.",
        "com.acb.",
        "com.shb.",
        "com.zalopay.",
        "com.vnpay.",
        "com.momo.",
        "vn.viettelpay"
    )

    private val PROTECTED_CRYPTO_PASSWORD_PREFIXES = listOf(
        "vip.mytoken.",
        "com.binance.",
        "com.wallet.crypto.trustapp",
        "io.metamask",
        "com.onepassword.",
        "com.lastpass.",
        "com.bitwarden.",
        "com.dashlane.",
        "keepass",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    // ──────────────────────────────────────────────────────────────────────
    // Sensitive on-screen keywords (require confirmation)
    // ──────────────────────────────────────────────────────────────────────

    private val SENSITIVE_KEYWORDS = listOf(
        "mật khẩu",
        "password",
        "mã otp",
        "mã xác thực",
        "cvv",
        "mã pin",
        "số thẻ tín dụng",
        "chuyển tiền",
        "xác nhận thanh toán"
    )

    /**
     * Quick pre-observation check: can we proceed with this package at all?
     * Must be called BEFORE reading full UI content or capturing images.
     *
     * Returns DENY for protected packages, ALLOW otherwise (further checks happen
     * after observation in [checkActionDispatch]).
     */
    fun checkPackagePreObservation(packageName: String?): PolicyCheckResult {
        if (packageName.isNullOrBlank()) {
            // Unknown context: treat as non-actionable; wait safely
            return PolicyCheckResult(
                PolicyDecision.DENY,
                "Không xác định được ứng dụng đang chạy. JAVIS tạm dừng để đảm bảo an toàn."
            )
        }

        // 1. Protected banking packages — absolute deny
        for (prefix in PROTECTED_BANKING_PREFIXES) {
            if (packageName.contains(prefix, ignoreCase = true)) {
                Log.w(TAG, "PRE-OBS DENY (banking): $packageName")
                return PolicyCheckResult(
                    PolicyDecision.DENY,
                    "Dừng tác vụ: Ứng dụng tài chính/ngân hàng ($packageName) được bảo vệ tuyệt đối."
                )
            }
        }

        // 2. Protected crypto/password packages — absolute deny
        for (prefix in PROTECTED_CRYPTO_PASSWORD_PREFIXES) {
            if (packageName.contains(prefix, ignoreCase = true)) {
                Log.w(TAG, "PRE-OBS DENY (crypto/password): $packageName")
                return PolicyCheckResult(
                    PolicyDecision.DENY,
                    "Dừng tác vụ: Ứng dụng bảo mật mật khẩu hoặc ví điện tử ($packageName) không được phép tự động hóa."
                )
            }
        }

        return PolicyCheckResult(PolicyDecision.ALLOW)
    }

    /**
     * Full policy check after observation. Called before sending content to a model or
     * dispatching any action.
     *
     * Evaluation order (BA-03 fixed):
     * 1. Mandatory protected-package deny (cannot be overridden).
     * 2. Custom DB rules.
     * 3. Sensitive keyword scan.
     */
    suspend fun checkScreenAndPackage(packageName: String?, visibleText: String?): PolicyCheckResult {
        if (packageName.isNullOrBlank()) {
            return PolicyCheckResult(
                PolicyDecision.DENY,
                "Không xác định được ứng dụng đang chạy. JAVIS tạm dừng để đảm bảo an toàn."
            )
        }

        // Step 1: Mandatory protected deny lists (evaluated BEFORE custom rules)
        val preCheck = checkPackagePreObservation(packageName)
        if (preCheck.decision == PolicyDecision.DENY) return preCheck

        // Step 2: Custom DB rules (user-defined, cannot bypass step 1)
        val customRule = database.policyRuleDao().getRuleForPackage(packageName)
        if (customRule != null) {
            when (customRule.policy) {
                PolicyRule.POLICY_DENY -> return PolicyCheckResult(
                    PolicyDecision.DENY,
                    "Ứng dụng $packageName nằm trong danh sách cấm tự động hóa của bạn."
                )
                PolicyRule.POLICY_REQUIRE_CONFIRM -> return PolicyCheckResult(
                    PolicyDecision.REQUIRE_CONFIRM,
                    "Ứng dụng $packageName yêu cầu bạn xác nhận trước khi tiếp tục."
                )
                PolicyRule.POLICY_ALLOW -> { /* continue to keyword scan */ }
                else -> { /* unknown policy — continue */ }
            }
        }

        // Step 3: Sensitive on-screen keyword scan
        if (!visibleText.isNullOrBlank()) {
            val lowerText = visibleText.lowercase()
            for (keyword in SENSITIVE_KEYWORDS) {
                if (lowerText.contains(keyword)) {
                    return PolicyCheckResult(
                        PolicyDecision.REQUIRE_CONFIRM,
                        "Màn hình hiển thị nội dung nhạy cảm (\"$keyword\"). Bạn có muốn JAVIS tiếp tục không?"
                    )
                }
            }
        }

        return PolicyCheckResult(PolicyDecision.ALLOW)
    }

    /**
     * Pre-upload check: should we send this observation's text/image to an external model?
     * Blocks if the current package is protected or the screen contains sensitive content.
     *
     * @param observation The observation to check before uploading to a cloud VLM.
     */
    suspend fun checkUploadPermission(observation: ScreenObservation): PolicyCheckResult {
        // Check package-level protection first
        val pkgCheck = checkPackagePreObservation(observation.currentPackage)
        if (pkgCheck.decision == PolicyDecision.DENY) return pkgCheck

        // Also check screen text for sensitive content
        val textToCheck = observation.ocrText ?: observation.nodeHierarchyText
        return checkScreenAndPackage(observation.currentPackage, textToCheck)
    }

    /**
     * Pre-dispatch action check: validate that we're allowed to act on the current context
     * immediately before gesture/action dispatch (recheck at boundary).
     */
    fun checkActionDispatch(packageName: String?): PolicyCheckResult {
        return checkPackagePreObservation(packageName)
    }

    /**
     * Initializes default policy rules into Room DB if empty.
     * Only called once during first run. Does not overwrite existing rules.
     */
    suspend fun initDefaultRulesIfEmpty() {
        val rules = database.policyRuleDao().getAllRules()
        if (rules.isEmpty()) {
            val defaultRules = listOf(
                PolicyRule("com.google.android.youtube", PolicyRule.POLICY_ALLOW, "YouTube xem video"),
                PolicyRule("com.zhiliaoapp.musically", PolicyRule.POLICY_ALLOW, "TikTok lướt video"),
                PolicyRule("com.ss.android.ugc.trill", PolicyRule.POLICY_ALLOW, "TikTok Asia"),
                PolicyRule("com.facebook.katana", PolicyRule.POLICY_ALLOW, "Facebook mạng xã hội"),
                PolicyRule("com.vietcombank.mbbank", PolicyRule.POLICY_DENY, "Vietcombank Banking"),
                PolicyRule("com.mbmobile", PolicyRule.POLICY_DENY, "MB Bank"),
                PolicyRule("com.vpb.neo", PolicyRule.POLICY_DENY, "VPBank Neo"),
                PolicyRule("com.binance.dev", PolicyRule.POLICY_DENY, "Binance Exchange")
            )
            database.policyRuleDao().insertRules(defaultRules)
        }
    }
}
