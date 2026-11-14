package io.legado.app.help

import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.RestoreStageTimer
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavCheckResult
import io.legado.app.lib.webdav.WebDavConnectionPolicy
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.lib.webdav.WebDavStatus
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**webDav初始化会访问网络,不要放到主线程
 * WebDav云同步管理类
 * 
 * 提供WebDav协议的云端备份和同步功能，包括：
 * - 备份文件上传/下载
 * - 书籍进度同步
 * - 背景图片同步
 * 
 * WebDav配置：
 * - URL: WebDav服务器地址（默认为坚果云）
 * - 账号: WebDav账号
 * - 密码: WebDav密码
 * - 目录: 备份存储目录
 * - 设备名: 用于区分不同设备的备份
 * 
 * 目录结构：
 * - rootWebDavUrl/: 根目录
 *   - bookProgress/: 书籍进度目录
 *   - books/: 书籍导出目录
 *   - background/: 背景图片目录
 *   - backup*.zip: 备份文件
 * 
 * 注意：WebDav初始化会访问网络，不要在主线程调用
 */
object AppWebDav {

    /** 默认WebDav服务器地址（坚果云） */
    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"

    /** 书籍进度同步URL */
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"

    /** 书籍导出URL */
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"

    /** 背景图片URL */
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"

    private fun appWebDav(path: String, authorization: Authorization): WebDav {
        return WebDav(
            path,
            authorization,
            requireHttps = AppConfig.webDavHttpsOnly,
            allowInsecure = AppConfig.unsafeWebDav
        )
    }

    /** WebDav授权信息 */
    @Volatile
    var authorization: Authorization? = null
        private set

    /** 默认书籍WebDav客户端 */
    @Volatile
    var defaultBookWebDav: RemoteBookWebDav? = null

    /** WebDav当前连接是否已验证可用 */
    val isOk get() = authorization != null

    /** Whether complete WebDAV credentials are saved on this device. */
    val isConfigured: Boolean
        get() = WebDavConnectionPolicy.hasSavedCredentials(
            appCtx.getPrefString(PreferKey.webDavAccount),
            appCtx.getPrefString(PreferKey.webDavPassword)
        )

    private val _status = MutableStateFlow(
        WebDavConnectionPolicy.initialStatus(isConfigured)
    )
    val status = _status.asStateFlow()

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitorLock = Any()
    private val monitoringStarted = AtomicBoolean(false)
    private var checkGeneration = 0L
    private var checkJob: Job? = null
    private var networkDebounceJob: Job? = null
    private val networkListener by lazy {
        NetworkChangedListener(appCtx).apply {
            onNetworkChanged = { scheduleNetworkRefresh() }
        }
    }

    /** 是否为坚果云服务 */
    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    /** Start the process-scoped monitor once; it never holds an Activity. */
    fun startMonitoring() {
        if (!monitoringStarted.compareAndSet(false, true)) return
        runCatching { networkListener.register() }
        refreshConfig()
    }

    private fun scheduleNetworkRefresh() {
        synchronized(monitorLock) {
            networkDebounceJob?.cancel()
            networkDebounceJob = monitorScope.launch {
                delay(500)
                synchronized(monitorLock) { networkDebounceJob = null }
                val configured = isConfigured
                if (WebDavConnectionPolicy.shouldRefreshOnNetworkChange(configured)) {
                    refreshConfig()
                }
            }
        }
    }

    /** Queue a cancellable, serialized replacement check on IO. */
    fun refreshConfig() {
        synchronized(monitorLock) {
            networkDebounceJob?.cancel()
            networkDebounceJob = null
            checkJob?.cancel()
            checkJob = null
            val generation = ++checkGeneration
            authorization = null
            defaultBookWebDav = null
            if (!isConfigured) {
                _status.value = WebDavStatus.UNCONFIGURED
                return
            }
            _status.value = WebDavStatus.CHECKING
            checkJob = monitorScope.launch { checkConfig(generation) }
        }
    }

    /**
     * WebDav根目录URL
     * 由配置URL和目录名拼接而成
     */
    private val rootWebDavUrl: String
        get() {
            val configUrl = appCtx.getPrefString(PreferKey.webDavUrl)?.trim()
            var url = if (configUrl.isNullOrEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            AppConfig.webDavDir?.trim()?.let {
                if (it.isNotEmpty()) {
                    url = "${url}${it}/"
                }
            }
            return url
        }

    /**
     * 更新WebDav配置
     * 读取SharedPreferences中的配置并初始化WebDav连接
     * 
     * 初始化时会创建必要的目录：
     * - 根目录
     * - 书籍进度目录
     * - 书籍导出目录
     * - 背景图片目录
     */
    private suspend fun checkConfig(generation: Long) {
        try {
            val account = appCtx.getPrefString(PreferKey.webDavAccount)
            val password = appCtx.getPrefString(PreferKey.webDavPassword)
            if (account.isNullOrBlank() || password.isNullOrBlank()) {
                updateCheckResult(generation, WebDavStatus.UNCONFIGURED)
                return
            }

            val currentAuthorization = Authorization(account, password)
            val result = appWebDav(rootWebDavUrl, currentAuthorization).check()
            if (result != WebDavCheckResult.AVAILABLE) {
                updateCheckResult(generation, WebDavConnectionPolicy.statusFor(result))
                return
            }

            appWebDav(rootWebDavUrl, currentAuthorization).makeAsDir()
            appWebDav(bookProgressUrl, currentAuthorization).makeAsDir()
            appWebDav(exportsWebDavUrl, currentAuthorization).makeAsDir()
            appWebDav(bgWebDavUrl, currentAuthorization).makeAsDir()
            val remoteBookWebDav = RemoteBookWebDav(
                "${rootWebDavUrl}books/",
                currentAuthorization,
                allowInsecure = AppConfig.unsafeWebDav,
                requireHttps = AppConfig.webDavHttpsOnly
            )
            synchronized(monitorLock) {
                if (generation == checkGeneration) {
                    authorization = currentAuthorization
                    defaultBookWebDav = remoteBookWebDav
                    _status.value = WebDavStatus.AVAILABLE
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            updateCheckResult(generation, WebDavConnectionPolicy.statusFor(
                WebDavConnectionPolicy.fromFailure(e)
            ))
        }
    }

    private fun updateCheckResult(generation: Long, status: WebDavStatus) {
        synchronized(monitorLock) {
            if (generation == checkGeneration) _status.value = status
        }
    }

    // ==================== 备份文件管理 ====================

    /**
     * 获取云端备份文件名列表
     * 
     * @return 备份文件名列表，按名称倒序排列
     * @throws Exception WebDav未配置或网络错误时抛出异常
     */
    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let {
            var files = appWebDav(rootWebDavUrl, it).listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    /**
     * 从WebDav下载并解压备份文件
     *
     * @param name 备份文件名
     * @throws WebDavException 下载失败时抛出异常
     */
    @Throws(WebDavException::class)
    suspend fun downloadAndUnzipBackup(name: String) {
        val timer = RestoreStageTimer()
        try {
            authorization?.let {
                val webDav = appWebDav(rootWebDavUrl + name, it)
                timer.measureSuspend("webdav_download") {
                    webDav.downloadTo(Backup.zipFilePath, true)
                }
                FileUtils.delete(Backup.backupPath)
                timer.measureSuspend("zip_unzip") {
                    ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
                }
            }
        } finally {
            timer.snapshot().forEach { timing ->
                val count = timing.itemCount?.let { ", count=$it" }.orEmpty()
                AppLog.put("恢复阶段 ${timing.stage}: ${timing.elapsedMs}ms$count")
            }
            timer.clear()
        }
    }

    /**
     * 从WebDav恢复备份
     *
     * 执行步骤：
     * 1. 下载备份文件
     * 2. 解压到临时目录
     * 3. 执行恢复逻辑
     *
     * @param name 备份文件名
     * @throws WebDavException 下载或恢复失败时抛出异常
     */
    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String) {
        downloadAndUnzipBackup(name)
        Restore.restoreLocked(Backup.backupPath)
    }

    /**
     * 检查WebDav上是否存在指定备份文件
     * 
     * @param backUpName 备份文件名
     * @return true表示存在，false表示不存在或WebDav未配置
     */
    suspend fun hasBackUp(backUpName: String): Boolean {
        authorization?.let {
            val url = "$rootWebDavUrl${backUpName}"
            return appWebDav(url, it).exists()
        }
        return false
    }

    /**
     * 获取最新的备份文件信息
     * 
     * @return Result包装的最新备份文件信息，失败时返回异常
     */
    suspend fun lastBackUp(): Result<WebDavFile?> {
        return kotlin.runCatching {
            authorization?.let {
                var lastBackupFile: WebDavFile? = null
                appWebDav(rootWebDavUrl, it).listFiles().reversed().forEach { webDavFile ->
                    if (webDavFile.displayName.startsWith("backup")) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            }
        }
    }

    /**
     * 上传备份文件到WebDav
     * 
     * @param fileName 备份文件名
     * @throws Exception 上传失败时抛出异常
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        authorization?.let {
            val putUrl = "$rootWebDavUrl$fileName"
            appWebDav(putUrl, it).upload(Backup.zipFilePath)
        }
    }

    // ==================== 背景图片同步 ====================

    /**
     * 获取云端所有背景图片文件列表
     * 
     * @return Result包装的文件列表
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("网络未连接")
            authorization.let {
                it ?: throw NoStackTraceException("webDav未配置")
                appWebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片到WebDav
     * 只上传云端不存在的图片
     * 
     * @param files 本地背景图片文件数组
     */
    suspend fun upBgs(files: Array<File>) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                appWebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
    }

    /**
     * 从WebDav下载背景图片
     * 目前未实现完整逻辑
     */
    suspend fun downBgs() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
    }

    // ==================== 书籍导出 ====================

    /**
     * 导出字节数据到WebDav
     * 
     * @param byteArray 要导出的字节数组
     * @param fileName 导出文件名
     */
    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                appWebDav(putUrl, it).upload(byteArray, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    /**
     * 导出URI指向的文件到WebDav
     * 
     * @param uri 文件URI
     * @param fileName 导出文件名
     */
    suspend fun exportWebDav(uri: Uri, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                appWebDav(putUrl, it).upload(uri, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    // ==================== 书籍进度同步 ====================

    /**
     * 上传书籍阅读进度到WebDav
     * 
     * @param book 书籍对象
     * @param toast 是否显示Toast提示
     * @param onSuccess 成功回调
     */
    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val authorization = authorization ?: return
        if (!AppConfig.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author)
            appWebDav(url, authorization).upload(json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    /**
     * 上传书籍进度对象到WebDav
     * 
     * @param bookProgress 书籍进度对象
     * @param onSuccess 成功回调
     */
    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        try {
            val authorization = authorization ?: return
            if (!AppConfig.syncBookProgress) return
            if (!NetworkUtils.isAvailable()) return
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookProgress.name, bookProgress.author)
            appWebDav(url, authorization).upload(json.toByteArray(), "application/json")
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e)
        }
    }

    /**
     * 获取书籍进度URL
     * 
     * @param name 书籍名称
     * @param author 书籍作者
     * @return WebDav URL
     */
    private fun getProgressUrl(name: String, author: String): String {
        return bookProgressUrl + getProgressFileName(name, author)
    }

    /**
     * 生成进度文件名
     * 格式：{书名}_{作者}.json
     * 
     * @param name 书籍名称
     * @param author 书籍作者
     * @return 文件名
     */
    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 从WebDav获取书籍进度
     * 
     * @param book 书籍对象
     * @return 书籍进度对象，失败返回null
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        val url = getProgressUrl(book.name, book.author)
        kotlin.runCatching {
            val authorization = authorization ?: return null
            appWebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
        }
        return null
    }

    /**
     * 下载所有书籍进度并同步到本地
     * 
     * 同步规则：
     * - 只同步云端更新时间大于本地同步时间的进度
     * - 只更新进度更靠后的记录
     */
    suspend fun downloadAllBookProgress() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookProgressFiles = appWebDav(bookProgressUrl, authorization).listFiles()
        val map = hashMapOf<String, WebDavFile>()
        bookProgressFiles.forEach {
            map[it.displayName] = it
        }
        appDb.bookDao.all.forEach { book ->
            val progressFileName = getProgressFileName(book.name, book.author)
            val webDavFile = map[progressFileName]
            webDavFile ?: return
            if (webDavFile.lastModify <= book.syncTime) {
                //本地同步时间大于上传时间不用同步
                return
            }
            getBookProgress(book)?.let { bookProgress ->
                if (bookProgress.durChapterIndex > book.durChapterIndex
                    || (bookProgress.durChapterIndex == book.durChapterIndex
                            && bookProgress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    appDb.bookDao.update(book)
                }
            }
        }
    }

}
