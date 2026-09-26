package com.dinh.javis.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BehaviorAggregateDao {
    @Query("SELECT * FROM behavior_aggregates WHERE taskKey = :taskKey AND date = :date LIMIT 1")
    suspend fun getAggregate(taskKey: String, date: String): BehaviorAggregate?

    @Query("SELECT * FROM behavior_aggregates ORDER BY runCount DESC")
    suspend fun getAllAggregates(): List<BehaviorAggregate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(aggregate: BehaviorAggregate)

    @Query("DELETE FROM behavior_aggregates")
    suspend fun clearAll()
}
