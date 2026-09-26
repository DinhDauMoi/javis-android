package com.dinh.javis.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AiModelProfileDao {
    @Query("SELECT * FROM ai_profiles")
    suspend fun getAllProfiles(): List<AiModelProfile>

    @Query("SELECT * FROM ai_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProfile(): AiModelProfile?

    @Query("SELECT * FROM ai_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: String): AiModelProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: AiModelProfile)

    @Query("UPDATE ai_profiles SET isActive = 0")
    suspend fun deactivateAll()

    @Transaction
    suspend fun setActiveProfile(profileId: String) {
        deactivateAll()
        activateProfile(profileId)
    }

    @Query("UPDATE ai_profiles SET isActive = 1 WHERE id = :profileId")
    suspend fun activateProfile(profileId: String)

    @Delete
    suspend fun deleteProfile(profile: AiModelProfile)

    @Query("DELETE FROM ai_profiles WHERE id = :profileId")
    suspend fun deleteProfileById(profileId: String)
}
