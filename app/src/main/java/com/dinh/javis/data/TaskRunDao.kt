package com.dinh.javis.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TaskRunDao {
    @Query("SELECT * FROM task_runs ORDER BY startTime DESC")
    suspend fun getAllRuns(): List<TaskRun>

    @Query("SELECT * FROM task_runs WHERE runId = :runId LIMIT 1")
    suspend fun getRunById(runId: String): TaskRun?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(taskRun: TaskRun)

    @Query("DELETE FROM task_runs")
    suspend fun clearAllRuns()

    @Query("DELETE FROM task_runs WHERE startTime < :cutoffTimestamp")
    suspend fun purgeOlderThan(cutoffTimestamp: Long)
}
