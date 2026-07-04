# Legado Plus 内置浏览器下载功能详解

## 快速理解

想象一下，内置浏览器就像一个**智能快递接收站**：

1. **WebViewActivity** - 完整的浏览器窗口（用于书源验证、登录等）
2. **BottomWebViewDialog** - 底部弹出的迷你浏览器（用于订阅源阅读）
3. **ReadRssActivity** - RSS 阅读专用浏览器
4. **DownloadService** - 下载管理器（真正的"快递员"）

当你在网页上点击一个下载链接时，WebView 会自动拦截这个请求，弹出提示让你确认是否下载。

---

## 架构图解

```
用户点击下载链接
       │
       ▼
WebView setDownloadListener
       │
       ▼
URLUtil.guessFileName 解析文件名
       │
       ▼
longSnackbar 显示下载提示
       │
       ▼
用户确认下载
       │
       ▼
Download.start 启动下载服务
       │
       ▼
DownloadService
       │
       ▼
Android DownloadManager
       │
       ▼
保存到 Downloads 文件夹
```

---

## 代码走读

### 1. WebView 下载监听器设置

**文件位置**: `app/src/main/java/io/legado/app/ui/browser/WebViewActivity.kt`

```kotlin
currentWebView.setDownloadListener { url, _, contentDisposition, _, _ ->
    // ① 从 URL 和 Content-Disposition 头部推断文件名
    var fileName = URLUtil.guessFileName(url, contentDisposition, null)
    // ② URL 解码（处理中文文件名）
    fileName = URLDecoder.decode(fileName, "UTF-8")
    // ③ 弹出 Snackbar 询问用户是否下载
    currentWebView.longSnackbar(fileName, getString(R.string.action_download)) {
        // ④ 用户确认后启动下载
        Download.start(this, url, fileName)
    }
}
```

**关键点理解**：

- `setDownloadListener` 是 Android WebView 的标准 API，用于拦截浏览器的下载请求
- `URLUtil.guessFileName()` 会尝试从 URL 路径和 `Content-Disposition` 头中提取合理的文件名
- `longSnackbar` 是 Legado Plus 封装的长时间显示的 Snackbar，比普通 Snackbar 显示更久

---

### 2. Download 启动器对象

**文件位置**: `app/src/main/java/io/legado/app/model/Download.kt`

```kotlin
object Download {
    fun start(context: Context, url: String, fileName: String) {
        context.startService<DownloadService> {
            action = IntentAction.start
            putExtra("url", url)
            putExtra("fileName", fileName)
        }
    }
}
```

**关键点理解**：

- 这是一个单例对象，封装了启动下载服务的逻辑
- 使用 `startService` 而非 bindService，实现"启动后即断开"的效果
- 通过 Intent 传递下载 URL 和文件名

---

### 3. DownloadService 下载服务

**文件位置**: `app/src/main/java/io/legado/app/service/DownloadService.kt`

```kotlin
@Synchronized
private fun startDownload(url: String?, fileName: String?) {
    // ① 检查参数
    if (url == null || fileName == null) {
        if (downloads.isEmpty()) stopSelf()
        return
    }

    // ② 避免重复下载
    if (downloads.values.any { it.url == url }) {
        toastOnUi("已在下载列表")
        return
    }

    // ③ 使用 Android 系统下载管理器
    val request = DownloadManager.Request(Uri.parse(url))
    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
    val downloadId = downloadManager.enqueue(request)

    // ④ 记录并监控下载状态
    downloads[downloadId] = DownloadInfo(url, fileName, NotificationId.Download + downloads.size)
    queryState()
    checkDownloadState()
}
```

**关键点理解**：

- `@Synchronized` 确保多线程安全
- 使用 `DownloadManager` 是 Android 官方的下载方案，支持后台下载、通知显示
- `enqueue` 返回一个 downloadId，可用于查询下载状态
- `setDestinationInExternalPublicDir` 指定保存到 `Downloads/` 目录

---

### 4. 更新对话框的下载功能

**文件位置**: `app/src/main/java/io/legado/app/ui/about/UpdateDialog.kt`

```kotlin
R.id.menu_download -> {
    val url = arguments?.getString("url")
    val name = arguments?.getString("name")
    if (url != null && name != null) {
        Download.start(requireContext(), url, name)
        toastOnUi(R.string.download_start)
    }
}
```

---

## 涉及的 WebView 位置

| 位置 | 文件 | 用途 |
|------|------|------|
| WebViewActivity.kt | 主浏览器 | 书源验证、登录 |
| BottomWebViewDialog.kt | 底部对话框 | 订阅源网页阅读 |
| ReadRssActivity.kt | RSS 阅读器 | RSS 内容中的网页 |

---

## 总结

Legado Plus 内置浏览器支持下载功能：

1. **更新下载** - 在 `UpdateDialog` 中点击下载菜单，会调用 `Download.start()` 启动系统下载
2. **网页下载** - 在 `WebViewActivity`、`BottomWebViewDialog`、`ReadRssActivity` 中，当网页触发下载时，WebView 的 `setDownloadListener` 会拦截并弹出下载确认

下载的文件会保存到 Android 系统的 `Downloads` 文件夹中，使用 Android 原生的 `DownloadManager` 管理。
