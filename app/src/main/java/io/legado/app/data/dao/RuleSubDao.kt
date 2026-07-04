package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.RuleSub
import kotlinx.coroutines.flow.Flow
/**
 * 源订阅链接（用于自动更新书源/订阅源/替换规则）
 */
@Dao
interface RuleSubDao {

    @get:Query("select * from ruleSubs order by customOrder")
    val all: List<RuleSub>

    @get:Query("select count(*) from ruleSubs")
    val count: Int

    @Query("select * from ruleSubs order by customOrder")
    fun flowAll(): Flow<List<RuleSub>>

    @get:Query("select customOrder from ruleSubs order by customOrder limit 0,1")
    val maxOrder: Int

    @Query("select * from ruleSubs where url = :url")
    fun findByUrl(url: String): RuleSub?

    @Query("delete from ruleSubs")
    fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg ruleSub: RuleSub)

    @Delete
    fun delete(vararg ruleSub: RuleSub)

    @Update
    fun update(vararg ruleSub: RuleSub)
}
