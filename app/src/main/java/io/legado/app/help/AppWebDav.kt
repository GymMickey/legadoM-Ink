package io.legado.app.help

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import io.legado.app.R
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
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.lib.webdav.WebDavHttpException
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
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    private val allowInsecureWebDav get() = AppConfig.unsafeWebDav

    private fun appWebDav(
        path: String,
        authorization: Authorization,
        allowInsecure: Boolean = allowInsecureWebDav
    ): WebDav {
        return WebDav(
            path,
            authorization,
            requireHttps = !allowInsecure,
            allowInsecure = allowInsecure
        )
    }

    /**
     * URL and credentials are kept together.  A remote operation must capture
     * one instance of this object and never combine fields from preferences
     * with fields from an older successful check.
     */
    data class WebDavConfigSnapshot(
        val rootUrl: String,
        val authorization: Authorization,
        val allowInsecure: Boolean,
        val remoteBookWebDav: RemoteBookWebDav,
        val version: Long,
        val configIdentity: String
    )

    private data class WebDavPreferenceSnapshot(
        val url: String?,
        val account: String?,
        val password: String?,
        val directory: String?,
        val allowInsecure: Boolean
    ) {
        val isConfigured: Boolean
            get() = !account.isNullOrEmpty() && !password.isNullOrEmpty()
    }

    private val checkRequestId = AtomicLong(0)
    private val configRequestLock = Any()
    private val configCheckScope = CoroutineScope(SupervisorJob() + IO)
    private val configJob = AtomicReference<Deferred<Unit>?>(null)
    private val _activeConfig = MutableStateFlow<WebDavConfigSnapshot?>(null)

    /** Last configuration whose endpoint and directories were verified together. */
    val activeConfig: StateFlow<WebDavConfigSnapshot?> = _activeConfig.asStateFlow()

    private val _status = MutableStateFlow(
        if (!appCtx.getPrefString(PreferKey.webDavAccount).isNullOrEmpty()
            && !appCtx.getPrefString(PreferKey.webDavPassword).isNullOrEmpty()
        ) {
            WebDavStatus.CHECKING
        } else {
            WebDavStatus.UNCONFIGURED
        }
    )

    /** Observable connection state; configuration presence is not inferred from availability. */
    val status: StateFlow<WebDavStatus> = _status.asStateFlow()

    /** WebDav授权信息 */
    val authorization: Authorization?
        get() = activeConfigForOperation()?.authorization

    /** 默认书籍WebDav客户端 */
    val defaultBookWebDav: RemoteBookWebDav?
        get() = activeConfigForOperation()?.remoteBookWebDav

    /** WebDav connection is currently available. Kept for existing callers. */
    val isOk get() = status.value == WebDavStatus.AVAILABLE

    /** Local credentials exist, even when the most recent check failed. */
    val isConfigured get() = readConfig().isConfigured

    /** 是否为坚果云服务 */
    val isJianGuoYun
        get() = (activeConfigForOperation()?.rootUrl ?: rootWebDavUrl(readConfig()))
            .startsWith(defaultWebDavUrl, true)

    /**
     * WebDav根目录URL
     * 由配置URL和目录名拼接而成
     */
    private fun readConfig() = WebDavPreferenceSnapshot(
        url = appCtx.getPrefString(PreferKey.webDavUrl)?.trim(),
        account = appCtx.getPrefString(PreferKey.webDavAccount),
        password = appCtx.getPrefString(PreferKey.webDavPassword),
        directory = AppConfig.webDavDir?.trim(),
        allowInsecure = allowInsecureWebDav
    )

    private fun rootWebDavUrl(config: WebDavPreferenceSnapshot): String {
        var url = if (config.url.isNullOrEmpty()) defaultWebDavUrl else config.url
        if (!url.endsWith("/")) url = "${url}/"
        config.directory?.takeIf { it.isNotEmpty() }?.let {
            url = "${url}${it}/"
        }
        return url
    }

    private fun statusFor(error: Throwable): WebDavStatus = classifyWebDavError(error)

    private fun normalizeRootUrl(rootUrl: String): String {
        val normalized = runCatching { URI(rootUrl.trim()).normalize().toString() }
            .getOrElse { rootUrl.trim() }
        return normalized.trimEnd('/') + "/"
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Opaque identity for the complete endpoint configuration. The password is only
     * represented by an in-memory digest and is never included in logs or state text.
     */
    internal fun webDavConfigIdentity(
        rootUrl: String,
        username: String?,
        password: String?,
        allowInsecure: Boolean
    ): String = sha256(
        listOf(
            normalizeRootUrl(rootUrl),
            username.orEmpty(),
            sha256(password.orEmpty()),
            allowInsecure.toString()
        ).joinToString("\u0000")
    )

    internal fun classifyWebDavError(error: Throwable): WebDavStatus = when (error) {
        is WebDavHttpException -> {
            if (error.code == 401 || error.code == 403) {
                WebDavStatus.AUTHENTICATION_FAILED
            } else {
                WebDavStatus.SERVER_ERROR
            }
        }

        is IOException -> WebDavStatus.OFFLINE
        else -> WebDavStatus.SERVER_ERROR
    }

    private fun currentPreferenceIdentity(): String? {
        val config = readConfig()
        if (!config.isConfigured) return null
        return webDavConfigIdentity(
            rootUrl = rootWebDavUrl(config),
            username = config.account,
            password = config.password,
            allowInsecure = config.allowInsecure
        )
    }

    private fun isCurrent(snapshot: WebDavConfigSnapshot): Boolean {
        return _activeConfig.value?.configIdentity == snapshot.configIdentity
            && currentPreferenceIdentity() == snapshot.configIdentity
    }

    private fun activeConfigForOperation(): WebDavConfigSnapshot? {
        val snapshot = _activeConfig.value ?: return null
        return snapshot.takeIf(::isCurrent)
    }

    /** Return only a previously verified snapshot; callers must keep its fields together. */
    internal fun currentSyncV1ConfigSnapshot(): WebDavConfigSnapshot? = activeConfigForOperation()

    internal fun isSyncV1ConfigCurrent(snapshot: WebDavConfigSnapshot): Boolean = isCurrent(snapshot)

    /** Create a client under the same verified URL/auth/TLS snapshot, never from preferences. */
    internal fun syncV1WebDav(
        snapshot: WebDavConfigSnapshot,
        relativePath: String
    ): WebDav? {
        if (!isCurrent(snapshot)) return null
        if (relativePath.startsWith('/') || '\\' in relativePath
            || relativePath.split('/').any { it == ".." }
        ) return null
        return appWebDav(
            path = snapshot.rootUrl + relativePath,
            authorization = snapshot.authorization,
            allowInsecure = snapshot.allowInsecure
        )
    }

    private fun updateOperationStatus(snapshot: WebDavConfigSnapshot, error: Throwable) {
        if (isCurrent(snapshot)) {
            _status.value = statusFor(error)
        }
    }

    private fun markOperationAvailable(snapshot: WebDavConfigSnapshot) {
        if (isCurrent(snapshot)) {
            _status.value = WebDavStatus.AVAILABLE
        }
    }

    private fun requireActiveConfig(): WebDavConfigSnapshot {
        return activeConfigForOperation() ?: if (!readConfig().isConfigured) {
            throw NoStackTraceException("webDav没有配置")
        } else {
            throw NoStackTraceException("webDav配置尚未验证")
        }
    }

    private suspend fun <T> withActiveConfig(
        operation: String,
        block: suspend (WebDavConfigSnapshot) -> T
    ): T {
        val snapshot = requireActiveConfig()
        return try {
            block(snapshot).also { markOperationAvailable(snapshot) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateOperationStatus(snapshot, e)
            AppLog.put("WebDav${operation}失败", e)
            throw e
        }
    }

    /** Start process-wide network and foreground rechecks once. */
    fun startMonitoring() {
        if (!monitoringStarted.compareAndSet(false, true)) return
        val listener = NetworkChangedListener(appCtx)
        listener.onNetworkChanged = { scheduleRecheck(force = true) }
        listener.register()
        (appCtx.applicationContext as Application).registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                scheduleRecheck(force = false)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    private val monitoringStarted = AtomicBoolean(false)
    private val monitoringScope = CoroutineScope(SupervisorJob() + IO)
    private val recheckJob = AtomicReference<Job?>(null)
    private val lastSuccessfulCheckAt = AtomicLong(0)

    internal const val recheckDebounceMs = 750L
    private const val minRecheckIntervalMs = 30_000L

    private fun scheduleRecheck(force: Boolean) {
        val config = readConfig()
        if (!config.isConfigured) {
            recheckJob.getAndSet(null)?.cancel()
            return
        }
        val activeMatchesPreferences = activeConfigForOperation() != null
        if (activeMatchesPreferences && !shouldScheduleWebDavCheck(
                status.value,
                lastSuccessfulCheckAt.get(),
                SystemClock.elapsedRealtime(),
                force
            )
        ) {
            return
        }
        val job = monitoringScope.launch {
            delay(recheckDebounceMs)
            val latestConfig = readConfig()
            val latestActiveMatches = activeConfigForOperation() != null
            if (latestConfig.isConfigured) {
                if (!NetworkUtils.isAvailable()) {
                    _status.value = WebDavStatus.OFFLINE
                } else if (!latestActiveMatches || shouldScheduleWebDavCheck(
                        status.value,
                        lastSuccessfulCheckAt.get(),
                        SystemClock.elapsedRealtime(),
                        force
                    )
                ) {
                    upConfig()
                }
            }
        }
        recheckJob.getAndSet(job)?.cancel()
    }

    internal fun shouldScheduleWebDavCheck(
        status: WebDavStatus,
        lastSuccessfulCheckAt: Long,
        now: Long,
        force: Boolean
    ): Boolean {
        if (force || status != WebDavStatus.AVAILABLE) return true
        val age = now - lastSuccessfulCheckAt
        return age !in 0 until minRecheckIntervalMs
    }

    private fun updateStatus(requestId: Long, value: WebDavStatus, identity: String? = null) {
        if (requestId == checkRequestId.get()
            && (identity == null || currentPreferenceIdentity() == identity)
        ) {
            _status.value = value
        }
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
    suspend fun upConfig() {
        val (checkTask, previousTask) = synchronized(configRequestLock) {
            val requestId = checkRequestId.incrementAndGet()
            val previousStatus = _status.value
            val newTask = configCheckScope.async(start = CoroutineStart.LAZY) {
                runConfigCheck(requestId, previousStatus)
            }
            newTask to configJob.getAndSet(newTask)
        }

        try {
            previousTask?.takeUnless { it === checkTask }?.cancelAndJoin()
            checkTask.start()
            awaitLatestConfigCheck(checkTask)
        } catch (e: CancellationException) {
            if (currentCoroutineContext()[Job]?.isActive == false) {
                withContext(NonCancellable) {
                    checkTask.cancelAndJoin()
                }
            }
            throw e
        } finally {
            configJob.compareAndSet(checkTask, null)
        }
    }

    private suspend fun awaitLatestConfigCheck(initialTask: Deferred<Unit>) {
        var task = initialTask
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                task.await()
                return
            } catch (_: CancellationException) {
                // Cancellation of the caller is distinct from a task replaced by a newer check.
                currentCoroutineContext().ensureActive()
                val latestTask = synchronized(configRequestLock) { configJob.get() }
                if (latestTask != null && latestTask !== task) {
                    task = latestTask
                    continue
                }
                // The deferred was cancelled while this caller remained active. That only
                // happens when this check was superseded (or its owner cancelled it); never
                // leak that internal cancellation to a caller that was not cancelled.
                return
            }
        }
    }

    private suspend fun runConfigCheck(requestId: Long, previousStatus: WebDavStatus) {
        val config = readConfig()

        if (!config.isConfigured) {
            updateStatus(requestId, WebDavStatus.UNCONFIGURED)
            if (requestId == checkRequestId.get()) {
                // An empty credential is an explicit user configuration change.
                _activeConfig.value = null
            }
            return
        }

        val rootUrl = rootWebDavUrl(config)
        val candidateIdentity = webDavConfigIdentity(
            rootUrl = rootUrl,
            username = config.account,
            password = config.password,
            allowInsecure = config.allowInsecure
        )
        updateStatus(requestId, WebDavStatus.CHECKING, candidateIdentity)
        if (!config.allowInsecure && !rootUrl.startsWith("https://", true)) {
            appCtx.toastOnUi(R.string.webdav_https_required)
            updateStatus(requestId, WebDavStatus.SERVER_ERROR, candidateIdentity)
            return
        }
        if (config.allowInsecure) {
            appCtx.toastOnUi(R.string.webdav_insecure_warning)
        }

        try {
            val candidateAuthorization = Authorization(
                config.account!!,
                config.password!!
            )
            when (val checkResult = appWebDav(
                rootUrl,
                candidateAuthorization,
                config.allowInsecure
            ).check()) {
                WebDavCheckResult.Available -> {
                    val candidateBookWebDav = RemoteBookWebDav(
                        "${rootUrl}books/",
                        candidateAuthorization,
                        allowInsecure = config.allowInsecure,
                        onOperationResult = { error ->
                            if (_activeConfig.value?.configIdentity == candidateIdentity
                                && currentPreferenceIdentity() == candidateIdentity
                            ) {
                                _status.value = error?.let(::statusFor)
                                    ?: WebDavStatus.AVAILABLE
                            }
                        }
                    )
                    appWebDav(rootUrl, candidateAuthorization, config.allowInsecure)
                        .makeAsDirOrThrow()
                    appWebDav("${rootUrl}bookProgress/", candidateAuthorization, config.allowInsecure)
                        .makeAsDirOrThrow()
                    candidateBookWebDav.ensureRootDirectory()
                    appWebDav("${rootUrl}background/", candidateAuthorization, config.allowInsecure)
                        .makeAsDirOrThrow()
                    currentCoroutineContext().ensureActive()

                    if (requestId == checkRequestId.get()
                        && currentPreferenceIdentity() == candidateIdentity
                    ) {
                        _activeConfig.value = WebDavConfigSnapshot(
                            rootUrl = rootUrl,
                            authorization = candidateAuthorization,
                            allowInsecure = config.allowInsecure,
                            remoteBookWebDav = candidateBookWebDav,
                            version = requestId,
                            configIdentity = candidateIdentity
                        )
                        _status.value = WebDavStatus.AVAILABLE
                        lastSuccessfulCheckAt.set(SystemClock.elapsedRealtime())
                    }
                }

                is WebDavCheckResult.AuthenticationFailed -> {
                    appCtx.toastOnUi(R.string.webdav_application_authorization_error)
                    updateStatus(requestId, WebDavStatus.AUTHENTICATION_FAILED, candidateIdentity)
                }

                is WebDavCheckResult.NetworkError -> {
                    updateStatus(requestId, WebDavStatus.OFFLINE, candidateIdentity)
                }

                is WebDavCheckResult.ServerError -> {
                    updateStatus(requestId, WebDavStatus.SERVER_ERROR, candidateIdentity)
                }
            }
        } catch (e: CancellationException) {
            if (requestId == checkRequestId.get()) {
                _status.value = previousStatus
            }
            throw e
        } catch (e: Exception) {
            updateStatus(requestId, statusFor(e), candidateIdentity)
            AppLog.put("WebDav配置检查失败", e)
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
        return withActiveConfig("备份列表") { snapshot ->
            val names = arrayListOf<String>()
            var files = appWebDav(snapshot.rootUrl, snapshot.authorization, snapshot.allowInsecure)
                .listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
            names
        }
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
            withActiveConfig("备份下载") { snapshot ->
                val webDav = appWebDav(
                    snapshot.rootUrl + name,
                    snapshot.authorization,
                    snapshot.allowInsecure
                )
                timer.measureSuspend("webdav_download") {
                    webDav.downloadTo(Backup.zipFilePath, true)
                }
            }
            FileUtils.delete(Backup.backupPath)
            timer.measureSuspend("zip_unzip") {
                ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
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
        val snapshot = requireActiveConfig()
        try {
            Restore.restoreLocked(Backup.backupPath)
            markOperationAvailable(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateOperationStatus(snapshot, e)
            throw e
        }
    }

    /**
     * 检查WebDav上是否存在指定备份文件
     * 
     * @param backUpName 备份文件名
     * @return true表示存在，false表示不存在或WebDav未配置
     */
    suspend fun hasBackUp(backUpName: String): Boolean {
        val snapshot = activeConfigForOperation() ?: return false
        if (!NetworkUtils.isAvailable()) {
            updateOperationStatus(snapshot, IOException("network unavailable"))
            return false
        }
        return try {
            appWebDav(
                "${snapshot.rootUrl}$backUpName",
                snapshot.authorization,
                snapshot.allowInsecure
            ).existsOrThrow().also { markOperationAvailable(snapshot) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateOperationStatus(snapshot, e)
            AppLog.put("WebDav备份检查失败", e)
            false
        }
    }

    /**
     * 获取最新的备份文件信息
     * 
     * @return Result包装的最新备份文件信息，失败时返回异常
     */
    suspend fun lastBackUp(): Result<WebDavFile?> {
        return try {
            Result.success(withActiveConfig("备份查询") { snapshot ->
                var lastBackupFile: WebDavFile? = null
                appWebDav(snapshot.rootUrl, snapshot.authorization, snapshot.allowInsecure)
                    .listFiles().reversed().forEach { webDavFile ->
                    if (webDavFile.displayName.startsWith("backup")) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 上传备份文件到WebDav
     * 
     * @param fileName 备份文件名
     * @return 结构化的上传结果；取消仍然向上传递
     */
    suspend fun backUpWebDav(fileName: String): WebDavUploadResult {
        val snapshot = activeConfigForOperation()
        if (snapshot == null) {
            return if (!readConfig().isConfigured) {
                WebDavUploadResult.NotConfigured
            } else {
                WebDavUploadResult.NotVerified
            }
        }
        if (!NetworkUtils.isAvailable()) {
            updateOperationStatus(snapshot, IOException("network unavailable"))
            return WebDavUploadResult.Failed(WebDavStatus.OFFLINE)
        }
        return try {
            val putUrl = "${snapshot.rootUrl}$fileName"
            appWebDav(putUrl, snapshot.authorization, snapshot.allowInsecure)
                .upload(Backup.zipFilePath)
            markOperationAvailable(snapshot)
            WebDavUploadResult.Uploaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = statusFor(e)
            updateOperationStatus(snapshot, e)
            AppLog.put("WebDav备份上传失败", e)
            WebDavUploadResult.Failed(status)
        }
    }

    // ==================== 背景图片同步 ====================

    /**
     * 获取云端所有背景图片文件列表
     * 
     * @return Result包装的文件列表
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return try {
            Result.success(withActiveConfig("背景列表") { snapshot ->
                if (!NetworkUtils.isAvailable()) throw IOException("network unavailable")
                appWebDav(
                    "${snapshot.rootUrl}background/",
                    snapshot.authorization,
                    snapshot.allowInsecure
                ).listFiles()
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 上传背景图片到WebDav
     * 只上传云端不存在的图片
     * 
     * @param files 本地背景图片文件数组
     */
    suspend fun upBgs(files: Array<File>) {
        val snapshot = activeConfigForOperation() ?: return
        if (!NetworkUtils.isAvailable()) {
            updateOperationStatus(snapshot, IOException("network unavailable"))
            return
        }
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                try {
                    appWebDav(
                        "${snapshot.rootUrl}background/${it.name}",
                        snapshot.authorization,
                        snapshot.allowInsecure
                    ).upload(it)
                    markOperationAvailable(snapshot)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateOperationStatus(snapshot, e)
                    throw e
                }
            }
        }
    }

    /**
     * 从WebDav下载背景图片
     * 目前未实现完整逻辑
     */
    suspend fun downBgs() {
        val snapshot = activeConfigForOperation() ?: return
        if (!NetworkUtils.isAvailable()) {
            updateOperationStatus(snapshot, IOException("network unavailable"))
            return
        }
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
        val snapshot = activeConfigForOperation() ?: return
        if (!NetworkUtils.isAvailable()) {
            updateOperationStatus(snapshot, IOException("network unavailable"))
            return
        }
        try {
            withActiveConfig("导出") { snapshot ->
                // 如果导出的本地文件存在,开始上传
                val putUrl = "${snapshot.rootUrl}books/$fileName"
                appWebDav(putUrl, snapshot.authorization, snapshot.allowInsecure)
                    .upload(byteArray, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败", e, true)
        }
    }

    /**
     * 导出URI指向的文件到WebDav
     * 
     * @param uri 文件URI
     * @param fileName 导出文件名
     */
    suspend fun exportWebDav(uri: Uri, fileName: String) {
        val snapshot = activeConfigForOperation() ?: return
        if (!NetworkUtils.isAvailable()) {
            updateOperationStatus(snapshot, IOException("network unavailable"))
            return
        }
        try {
            withActiveConfig("导出") { snapshot ->
                // 如果导出的本地文件存在,开始上传
                val putUrl = "${snapshot.rootUrl}books/$fileName"
                appWebDav(putUrl, snapshot.authorization, snapshot.allowInsecure)
                    .upload(uri, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败", e, true)
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
        if (!AppConfig.syncBookProgress) return
        val snapshot = activeConfigForOperation() ?: return
        if (!NetworkUtils.isAvailable()) {
            updateOperationStatus(snapshot, IOException("network unavailable"))
            return
        }
        try {
            withActiveConfig("进度上传") { current ->
                val bookProgress = BookProgress(book)
                val json = GSON.toJson(bookProgress)
                val url = getProgressUrl(current, book.name, book.author)
                appWebDav(url, current.authorization, current.allowInsecure)
                    .upload(json.toByteArray(), "application/json")
            }
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
            if (!AppConfig.syncBookProgress) return
            val snapshot = activeConfigForOperation() ?: return
            if (!NetworkUtils.isAvailable()) {
                updateOperationStatus(snapshot, IOException("network unavailable"))
                return
            }
            withActiveConfig("进度上传") { current ->
                val json = GSON.toJson(bookProgress)
                val url = getProgressUrl(current, bookProgress.name, bookProgress.author)
                appWebDav(url, current.authorization, current.allowInsecure)
                    .upload(json.toByteArray(), "application/json")
            }
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
    private fun getProgressUrl(snapshot: WebDavConfigSnapshot, name: String, author: String): String {
        return "${snapshot.rootUrl}bookProgress/" + getProgressFileName(name, author)
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
        activeConfigForOperation() ?: return null
        try {
            withActiveConfig("进度下载") { current ->
                val url = getProgressUrl(current, book.name, book.author)
                appWebDav(url, current.authorization, current.allowInsecure).download()
            }.let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍进度失败", e)
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
        val snapshot = activeConfigForOperation() ?: return
        if (!NetworkUtils.isAvailable()) {
            updateOperationStatus(snapshot, IOException("network unavailable"))
            return
        }
        val bookProgressFiles = withActiveConfig("进度列表") { current ->
            appWebDav(
                "${current.rootUrl}bookProgress/",
                current.authorization,
                current.allowInsecure
            ).listFiles()
        }
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
