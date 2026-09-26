package com.dinh.javis.agent

import android.content.Context
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
 * Bộ kiểm soát an toàn bảo mật (Safety Guardrails):
 * - Danh sách đen (Denylist) tự động chặn ngân hàng, ví tiền mã hóa, trình quản lý mật khẩu.
 * - Quét các từ khóa cực kỳ nhạy cảm (OTP, mật khẩu, CVV, mã PIN).
 * - Tôn trọng các quy tắc PolicyRule do người dùng tự thiết lập trong cơ sở dữ liệu Room.
 */
class PolicyGuard(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)

    // Danh sách ứng dụng tài chính, ngân hàng, ví điện tử
    private val bankingPackages = listOf(
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

    // Danh sách ví crypto & trình quản lý mật khẩu
    private val sensitivePackages = listOf(
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

    // Từ khóa nhạy cảm trên màn hình
    private val sensitiveKeywords = listOf(
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

    suspend fun checkScreenAndPackage(packageName: String?, visibleText: String?): PolicyCheckResult {
        if (packageName.isNullOrBlank()) {
            return PolicyCheckResult(PolicyDecision.ALLOW)
        }

        // 1. Kiểm tra quy tắc lưu trong Room DB trước
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
                PolicyRule.POLICY_ALLOW -> {
                    // Tiếp tục quét từ khóa nhạy cảm
                }
            }
        }

        // 2. Kiểm tra danh sách Denylist mặc định (Ngân hàng & Ví tiền)
        for (denied in bankingPackages) {
            if (packageName.contains(denied, ignoreCase = true)) {
                return PolicyCheckResult(
                    PolicyDecision.DENY,
                    "Dừng tác vụ: Ứng dụng tài chính/ngân hàng ($packageName) được bảo vệ tuyệt đối."
                )
            }
        }

        // 3. Kiểm tra ví Crypto & Password Manager
        for (denied in sensitivePackages) {
            if (packageName.contains(denied, ignoreCase = true)) {
                return PolicyCheckResult(
                    PolicyDecision.DENY,
                    "Dừng tác vụ: Ứng dụng bảo mật mật khẩu hoặc ví điện tử ($packageName) không được phép tự động hóa."
                )
            }
        }

        // 4. Quét từ khóa nhạy cảm nếu có text hiển thị
        if (!visibleText.isNullOrBlank()) {
            val lowerText = visibleText.lowercase()
            for (keyword in sensitiveKeywords) {
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
     * Khởi tạo các quy tắc mặc định vào Room DB nếu chưa có
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
