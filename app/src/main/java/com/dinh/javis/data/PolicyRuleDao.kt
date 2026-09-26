package com.dinh.javis.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PolicyRuleDao {
    @Query("SELECT * FROM policy_rules")
    suspend fun getAllRules(): List<PolicyRule>

    @Query("SELECT * FROM policy_rules WHERE packageName = :packageName LIMIT 1")
    suspend fun getRuleForPackage(packageName: String): PolicyRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: PolicyRule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<PolicyRule>)

    @Delete
    suspend fun deleteRule(rule: PolicyRule)

    @Query("DELETE FROM policy_rules WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}
