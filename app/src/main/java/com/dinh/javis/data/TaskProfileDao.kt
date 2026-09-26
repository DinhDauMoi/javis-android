package com.dinh.javis.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TaskProfileDao {
    @Query("SELECT * FROM task_profiles")
    suspend fun getAllProfiles(): List<TaskProfile>

    @Query("SELECT * FROM task_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: String): TaskProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: TaskProfile)

    @Delete
    suspend fun deleteProfile(profile: TaskProfile)
}
