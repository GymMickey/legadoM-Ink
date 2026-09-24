package io.legado.app.help.storage

import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx

private const val readConfigKey = "readConfig"
private const val themeConfigKey = "themeConfig"
private const val coverConfigKey = "coverConfig"
private const val localBookKey = "localBook"
private const val bookCacheKey = "bookCache"

/** 只依赖配置 key 的备份/恢复策略，便于在 JVM 测试中验证。 */
internal object BackupPreferencePolicy {

    val deviceLocalPrefKeys = setOf(
        PreferKey.defaultCover,
        PreferKey.defaultCoverDark,
        PreferKey.backupPath,
        PreferKey.defaultBookTreeUri,
        PreferKey.webDavDeviceName,
        PreferKey.launcherIcon,
        PreferKey.bitmapCacheSize,
        PreferKey.webServiceWakeLock,
        PreferKey.themeMode,
        PreferKey.iReaderPageHEnabled,
        PreferKey.iReaderPageHDirection,
        PreferKey.iReaderPageHSpeed
    )

    val userIgnoreKeys = listOf(
        readConfigKey,
        themeConfigKey,
        coverConfigKey,
        PreferKey.bookshelfLayout,
        PreferKey.showRss,
        PreferKey.threadCount,
        localBookKey,
        bookCacheKey
    )

    private val readPrefKeys = setOf(
        PreferKey.readStyleSelect,
        PreferKey.comicStyleSelect,
        PreferKey.shareLayout,
        PreferKey.hideStatusBar,
        PreferKey.hideNavigationBar,
        PreferKey.autoReadSpeed,
        PreferKey.clickActionTL,
        PreferKey.clickActionTC,
        PreferKey.clickActionTR,
        PreferKey.clickActionML,
        PreferKey.clickActionMC,
        PreferKey.clickActionMR,
        PreferKey.clickActionBL,
        PreferKey.clickActionBC,
        PreferKey.clickActionBR
    )

    val themePrefKeys = setOf(
        PreferKey.dThemeName,
        PreferKey.dNThemeName,
        PreferKey.cPrimary,
        PreferKey.cAccent,
        PreferKey.cBackground,
        PreferKey.cBBackground,
        PreferKey.bgImage,
        PreferKey.bgImageBlurring,
        PreferKey.tNavBar,
        PreferKey.cNPrimary,
        PreferKey.cNAccent,
        PreferKey.cNBackground,
        PreferKey.cNBBackground,
        PreferKey.bgImageN,
        PreferKey.bgImageNBlurring,
        PreferKey.tNavBarN
    )

    private val coverPrefKeys = setOf(
        PreferKey.useDefaultCover,
        PreferKey.loadCoverOnlyWifi,
        PreferKey.coverShowName,
        PreferKey.coverShowAuthor,
        PreferKey.coverShowNameN,
        PreferKey.coverShowAuthorN
    )

    fun shouldBackupPreference(key: String): Boolean {
        return key !in deviceLocalPrefKeys
    }

    fun shouldRestorePreference(
        key: String,
        ignored: Map<String, Boolean>
    ): Boolean {
        if (key in deviceLocalPrefKeys) return false
        return when {
            ignored[readConfigKey] == true && key in readPrefKeys -> false
            ignored[themeConfigKey] == true && key in themePrefKeys -> false
            ignored[coverConfigKey] == true && key in coverPrefKeys -> false
            key == PreferKey.bookshelfLayout && ignored[PreferKey.bookshelfLayout] == true -> false
            key == PreferKey.folderLayout && ignored[PreferKey.bookshelfLayout] == true -> false
            key == PreferKey.bookLayout && ignored[PreferKey.bookshelfLayout] == true -> false
            key == PreferKey.showRss && ignored[PreferKey.showRss] == true -> false
            key == PreferKey.threadCount && ignored[PreferKey.threadCount] == true -> false
            else -> true
        }
    }
}

/**
 * 备份配置管理类
 *
 * 管理备份和恢复时的配置忽略规则，用于：
 * - 控制哪些配置项不参与备份/恢复
 * - 用户可自定义忽略特定配置
 *
 * 配置忽略分为两类：
 * 1. 自动忽略：固定不备份的配置（如备份路径、设备名等）
 * 2. 用户忽略：用户可选择忽略的配置（如阅读配置、主题配置等）
 *
 * 忽略配置存储在 restoreIgnore.json 文件中
 */
@Suppress("ConstPropertyName")
object BackupConfig {

    /** 忽略配置文件路径 */
    private val ignoreConfigPath = FileUtils.getPath(appCtx.filesDir, "restoreIgnore.json")

    /** 忽略配置映射表，key为配置项，value为是否忽略 */
    val ignoreConfig: HashMap<String, Boolean> by lazy {
        val file = FileUtils.createFileIfNotExist(ignoreConfigPath)
        val json = file.readText()
        GSON.fromJsonObject<HashMap<String, Boolean>>(json).getOrNull() ?: hashMapOf()
    }

    // ==================== 配置项Key常量 ====================

    // ==================== 用户可配置忽略项 ====================

    /** 用户可配置忽略的配置Key列表 */
    val ignoreKeys = BackupPreferencePolicy.userIgnoreKeys.toTypedArray()

    /** 用户可配置忽略的配置标题列表（用于UI显示） */
    val ignoreTitle = arrayOf(
        appCtx.getString(R.string.read_config),
        appCtx.getString(R.string.theme_config),
        appCtx.getString(R.string.cover_config),
        appCtx.getString(R.string.bookshelf_layout),
        appCtx.getString(R.string.show_rss),
        appCtx.getString(R.string.thread_count),
        appCtx.getString(R.string.local_book),
        appCtx.getString(R.string.book_cache)
    )

    // ==================== 自动忽略项 ====================

    /**
     * 自动忽略的SharedPreferences Key列表
     * 这些配置项固定不参与备份/恢复
     */
    /** 固定保留在当前设备，不参与备份或恢复的配置。 */
    val deviceLocalPrefKeys = BackupPreferencePolicy.deviceLocalPrefKeys

    /** 判断配置 Key 是否可以写入新备份。用户恢复忽略项不影响备份。 */
    fun shouldBackupPreference(key: String): Boolean {
        return BackupPreferencePolicy.shouldBackupPreference(key)
    }

    /** 判断配置 Key 是否可以从备份恢复。固定设备项和用户忽略项均排除。 */
    fun shouldRestorePreference(key: String): Boolean {
        return BackupPreferencePolicy.shouldRestorePreference(key, ignoreConfig)
    }

    // ==================== 忽略配置属性 ====================

    /** 是否忽略阅读配置 */
    val ignoreReadConfig: Boolean
        get() = ignoreConfig[readConfigKey] == true

    /** 是否忽略主题配置 */
    val ignoreThemeConfig: Boolean
        get() = ignoreConfig[themeConfigKey] == true

    /** 是否忽略封面配置 */
    private val ignoreCoverConfig: Boolean
        get() = ignoreConfig[coverConfigKey] == true

    /** 是否忽略书架布局 */
    private val ignoreBookshelfLayout: Boolean
        get() = ignoreConfig[PreferKey.bookshelfLayout] == true

    /** 是否忽略RSS显示 */
    private val ignoreShowRss: Boolean
        get() = ignoreConfig[PreferKey.showRss] == true

    /** 是否忽略线程数 */
    private val ignoreThreadCount: Boolean
        get() = ignoreConfig[PreferKey.threadCount] == true

    /** 是否忽略本地书籍 */
    val ignoreLocalBook: Boolean
        get() = ignoreConfig[localBookKey] == true

    /** 是否忽略书籍缓存备份 */
    val ignoreBookCache: Boolean
        get() = ignoreConfig[bookCacheKey] == true

    /**
     * 保存忽略配置到文件
     * 将当前的忽略配置序列化为JSON并写入文件
     */
    fun saveIgnoreConfig() {
        val json = GSON.toJson(ignoreConfig)
        FileUtils.createFileIfNotExist(ignoreConfigPath).writeText(json)
    }

}
