package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.DictRule
import kotlinx.coroutines.flow.Flow

/**
 * 字典规则数据访问接口
 */
@Dao
interface DictRuleDao {

    @get:Query("select * from dictRules order by sortNumber")
    val all: List<DictRule>

    @get:Query("select count(*) from dictRules")
    val count: Int

    @get:Query("select * from dictRules where enabled = 1 order by sortNumber")
    val enabled: List<DictRule>

    @Query("select * from dictRules order by sortNumber")
    fun flowAll(): Flow<List<DictRule>>

    /**
     * 按名称模糊搜索字典规则
     * @param key 搜索关键词，使用SQL的LIKE语句进行模糊匹配
     */
    @Query("select * from dictRules where name like :key order by sortNumber")
    fun flowSearch(key: String): Flow<List<DictRule>>

    /**
     * 获取所有已启用的字典规则
     */
    @Query("select * from dictRules where enabled = 1 order by sortNumber")
    fun flowEnabled(): Flow<List<DictRule>>

    /**
     * 获取所有已禁用的字典规则
     */
    @Query("select * from dictRules where enabled != 1 order by sortNumber")
    fun flowDisabled(): Flow<List<DictRule>>

    @Query("select * from dictRules where name = :name")
    fun getByName(name: String): DictRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg dictRule: DictRule)

    @Update
    fun update(vararg dictRule: DictRule)

    @Delete
    fun delete(vararg dictRule: DictRule)

    @Query("delete from dictRules")
    fun deleteAll()

}
