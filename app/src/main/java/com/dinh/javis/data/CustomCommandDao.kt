package com.dinh.javis.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object quản lý truy vấn bảng lệnh tùy chỉnh
 */
@Dao
interface CustomCommandDao {

    @Query("SELECT * FROM custom_commands ORDER BY id DESC")
    fun getAllAsFlow(): Flow<List<CustomCommand>>

    @Query("SELECT * FROM custom_commands")
    suspend fun getAll(): List<CustomCommand>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(command: CustomCommand): Long

    @Delete
    suspend fun delete(command: CustomCommand)

    @Query("DELETE FROM custom_commands WHERE id = :id")
    suspend fun deleteById(id: Long)
}
