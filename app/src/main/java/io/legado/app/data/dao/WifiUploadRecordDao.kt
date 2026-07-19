package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.WifiUploadRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface WifiUploadRecordDao {

    @Query("SELECT * FROM wifi_upload_records ORDER BY uploadTime DESC")
    fun flowAll(): Flow<List<WifiUploadRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: WifiUploadRecord)

    @Query("DELETE FROM wifi_upload_records WHERE id NOT IN (SELECT id FROM wifi_upload_records ORDER BY uploadTime DESC LIMIT :max)")
    suspend fun trimToMax(max: Int)

    @Query("DELETE FROM wifi_upload_records")
    suspend fun deleteAll()
}