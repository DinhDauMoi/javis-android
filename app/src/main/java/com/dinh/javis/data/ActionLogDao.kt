package com.dinh.javis.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_logs WHERE runId = :runId ORDER BY timestamp ASC")
    suspend fun getLogsForRun(runId: String): List<ActionLog>

    @Insert
    suspend fun insertLog(log: ActionLog)

    @Query("DELETE FROM action_logs WHERE timestamp < :cutoffTimestamp")
    suspend fun purgeOlderThan(cutoffTimestamp: Long)

    @Query("DELETE FROM action_logs")
    suspend fun clearAll()
}
