package com.dinh.javis.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface BehaviorAggregateDao {

    @Query("SELECT * FROM behavior_aggregates WHERE taskKey = :taskKey AND date = :date LIMIT 1")
    suspend fun getAggregate(taskKey: String, date: String): BehaviorAggregate?

    @Query("SELECT * FROM behavior_aggregates ORDER BY date DESC, runCount DESC")
    suspend fun getAllAggregates(): List<BehaviorAggregate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(aggregate: BehaviorAggregate)

    /** Atomic idempotent upsert for an existing record (BA-09). */
    @Query("""
        UPDATE behavior_aggregates
        SET runCount = :runCount,
            failureCount = :failureCount,
            cancelledCount = :cancelledCount,
            blockedCount = :blockedCount,
            interruptedCount = :interruptedCount,
            totalDurationMs = :totalDurationMs,
            totalVerifiedActions = :totalVerifiedActions
        WHERE taskKey = :taskKey AND date = :date
    """)
    suspend fun updateCounts(
        taskKey: String,
        date: String,
        runCount: Int,
        failureCount: Int,
        cancelledCount: Int,
        blockedCount: Int,
        interruptedCount: Int,
        totalDurationMs: Long,
        totalVerifiedActions: Int
    ): Int

    /** Purge analytics older than retentionCutoff for 30-day retention. */
    @Query("DELETE FROM behavior_aggregates WHERE date < :retentionCutoff")
    suspend fun purgeOlderThan(retentionCutoff: String)

    @Query("DELETE FROM behavior_aggregates")
    suspend fun clearAll()
}
