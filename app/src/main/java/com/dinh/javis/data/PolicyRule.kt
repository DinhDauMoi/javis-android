package com.dinh.javis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Quy tắc bảo vệ kiểm soát quyền tự động hóa theo từng ứng dụng (Whitelist / Denylist / Confirm)
 */
@Entity(tableName = "policy_rules")
data class PolicyRule(
    @PrimaryKey
    val packageName: String,
    val policy: String, // ALLOW, DENY, REQUIRE_CONFIRM
    val notes: String? = null
) {
    companion object {
        const val POLICY_ALLOW = "ALLOW"
        const val POLICY_DENY = "DENY"
        const val POLICY_REQUIRE_CONFIRM = "REQUIRE_CONFIRM"
    }
}
