package io.legado.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * 数据库迁移测试
 *
 * 测试所有数据库版本迁移路径是否完整，确保用户升级应用后数据不会丢失。
 *
 * 测试策略：
 * 1. 从最早的稳定版本（43）开始测试
 * 2. 测试关键版本跳跃（99 → 100）
 * 3. 测试最新版本（100）
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    // 包含所有手动迁移
    private val ALL_MIGRATIONS = DatabaseMigrations.migrations

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * 测试从版本 43 到最新版本的完整迁移路径
     */
    @Test
    @Throws(IOException::class)
    fun migrate43ToLatest() {
        // 创建版本 43 的数据库
        helper.createDatabase(TEST_DB, 43).apply {
            close()
        }

        // 打开最新版本的数据库，Room 会验证所有迁移
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase
                // 验证首页模块表是否存在
                openHelper.writableDatabase.query("SELECT * FROM homepage_modules LIMIT 1").close()
                close()
            }
    }

    /**
     * 测试关键的 99 → 100 迁移（首页模块表创建 + homepageModules 列添加）
     * 这是用户最可能遇到的升级场景
     */
    @Test
    @Throws(IOException::class)
    fun migrate99To100() {
        // 创建版本 99 的数据库
        helper.createDatabase(TEST_DB, 99).apply {
            close()
        }

        // 打开版本 100 的数据库
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase
                // 验证首页模块表已创建
                openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='homepage_modules'").use {
                    assert(it.count == 1) { "homepage_modules 表应该存在" }
                }
                // 验证首页自定义集表已创建
                openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='homepage_custom_sets'").use {
                    assert(it.count == 1) { "homepage_custom_sets 表应该存在" }
                }
                // 验证 book_sources 表新增 homepageModules 列
                openHelper.writableDatabase.query("SELECT * FROM pragma_table_info('book_sources') WHERE name='homepageModules'").use {
                    assert(it.count == 1) { "homepageModules 列应该存在于 book_sources 表" }
                }
                close()
            }
    }

    /**
     * 测试从版本 50 到最新版本的迁移（覆盖更多用户场景）
     */
    @Test
    @Throws(IOException::class)
    fun migrate50ToLatest() {
        helper.createDatabase(TEST_DB, 50).apply {
            close()
        }

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase
                close()
            }
    }
}