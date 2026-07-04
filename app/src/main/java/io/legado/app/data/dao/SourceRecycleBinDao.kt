package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.SourceRecycleBin
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceRecycleBinDao {

    @Query("select * from source_recycle_bin order by deletedAt desc, id desc")
    fun flowAll(): Flow<List<SourceRecycleBin>>

    @Query("select * from source_recycle_bin where type = :type order by deletedAt desc, id desc")
    fun flowByType(type: String): Flow<List<SourceRecycleBin>>

    @Query("select * from source_recycle_bin where id = :id limit 1")
    fun getById(id: Long): SourceRecycleBin?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg items: SourceRecycleBin)

    @Delete
    fun delete(vararg items: SourceRecycleBin)

    @Query("delete from source_recycle_bin where id = :id")
    fun deleteById(id: Long)

    @Query("delete from source_recycle_bin where expireAt <= :now")
    fun deleteExpired(now: Long)

    @Query("delete from source_recycle_bin")
    fun deleteAll()
}

