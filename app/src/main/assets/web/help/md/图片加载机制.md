# 图片加载机制详解

## 概述

阅读APP的图片加载涉及多个场景：封面图片、正文插图、漫画章节、EPUB插图等。核心采用 **Glide** 图片加载库，并针对书源规则进行了深度定制，支持图片解密、自定义请求头、缓存策略等功能。

---

## 一、架构总览

```
图片加载场景
  │
  ├─ 封面加载
  ├─ 正文插图
  ├─ 漫画章节
  ├─ EPUB插图
  └─ RSS图片
        │
        ▼
  ImageLoader (统一入口)
    │
    └─ 自动判断: URL / File / ContentUri / DataUrl
        │
        ▼
  Glide + LegadoGlideModule
    ├─ OkHttpModelLoader (网络图片)
    ├─ LegadoDataUrlLoader (Base64图片)
    └─ FilePathLoader (本地文件)
        │
        ▼
  OkHttpStreamFetcher
    ├─ AnalyzeUrl (URL解析)
    ├─ ImageUtils (图片解密)
    └─ sourceOriginOption (请求头/代理/并发控制)
```

---

## 二、核心组件

### 1. ImageLoader（统一入口）

[ImageLoader.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/glide/ImageLoader.kt) 提供统一的图片加载接口，自动判断路径类型：

```kotlin
fun load(context: Context, path: String?): RequestBuilder<Drawable> {
    return when {
        path.isNullOrEmpty() -> Glide.with(context).load(path)
        path.isDataUrl() -> Glide.with(context).load(path)        // data:image/png;base64,xxx
        path.isAbsUrl() -> Glide.with(context).load(path)         // http:// 或 https://
        path.isContentScheme() -> Glide.with(context).load(path.toUri())  // content://
        else -> Glide.with(context).load(File(path))              // 本地文件路径
    }
}
```

**支持的路径类型：**
| 类型 | 示例 | 说明 |
|------|------|------|
| 网络URL | `https://example.com/image.jpg` | 通过OkHttp下载 |
| Base64 | `data:image/png;base64,iVBORw0...` | 直接解码显示 |
| ContentUri | `content://media/external/images/1` | 系统媒体库 |
| 本地文件 | `/storage/emulated/0/Download/img.jpg` | 直接读取 |

---

### 2. LegadoGlideModule（Glide配置）

[LegadoGlideModule.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/glide/LegadoGlideModule.kt) 是Glide的自定义配置模块：

```kotlin
@GlideModule
class LegadoGlideModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // 替换默认的网络加载器为OkHttp
        registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpModeLoaderFactory)
        // 添加Base64图片支持
        registry.prepend(String::class.java, InputStream::class.java, LegadoDataUrlLoader.Factory())
        // 添加本地文件路径支持
        registry.prepend(String::class.java, File::class.java, FilePathLoader.Factory())
    }

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // 磁盘缓存：1GB
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, 1024 * 1024 * 1000))
        // 内存缓存：自动计算
        val calculator = MemorySizeCalculator.Builder(context).build()
        builder.setMemorySizeCalculator(calculator)
    }
}
```

**关键配置：**
- **磁盘缓存**：1GB，存储在网络图片缓存目录
- **内存缓存**：根据设备自动计算大小
- **BitmapPool**：使用 `AsyncRecycleBitmapPool` 异步回收

---

### 3. OkHttpModelLoader（网络图片加载）

[OkHttpModelLoader.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/glide/OkHttpModelLoader.kt) 定义了自定义选项：

```kotlin
object OkHttpModelLoader : ModelLoader<GlideUrl?, InputStream?> {
    // 只在WiFi下加载
    val loadOnlyWifiOption = Option.memory("loadOnlyWifi", false)
    // 书源地址（用于获取请求头）
    val sourceOriginOption = Option.memory<String>("sourceOrigin")
    // 是否为漫画图片
    val mangaOption = Option.memory<Boolean>("manga", false)
}
```

---

### 4. OkHttpStreamFetcher（实际加载执行）

[OkHttpStreamFetcher.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/glide/OkHttpStreamFetcher.kt) 是图片加载的核心执行类：

```kotlin
class OkHttpStreamFetcher(
    private val url: GlideUrl,
    private val options: Options,
) : DataFetcher<InputStream>, okhttp3.Callback {

    override fun loadData(priority: Priority, callback: DataCallback<in InputStream>) {
        // 1. 检查是否跳过失败的URL
        if (failUrl.contains(url.toStringUrl())) {
            callback.onLoadFailed("跳过加载失败的图片")
            return
        }

        // 2. 检查WiFi限制
        val loadOnlyWifi = options.get(OkHttpModelLoader.loadOnlyWifiOption) ?: false
        if (loadOnlyWifi && !appCtx.isWifiConnect) {
            callback.onLoadFailed("只在wifi加载图片")
            return
        }

        // 3. 获取书源（用于请求头和解密）
        options.get(OkHttpModelLoader.sourceOriginOption)?.let { sourceUrl ->
            source = SourceHelp.getSource(sourceUrl)
        }

        // 4. 解析URL（处理重定向、添加请求头）
        analyzedUrl = AnalyzeUrl(url.toString(), source = source).getGlideUrl()

        // 5. 发起请求
        val request = Request.Builder()
            .url(analyzedUrl.toStringUrl())
            .addHeaders(analyzedUrl.headers)
            .build()
        call = if (manga) okHttpClientManga.newCall(request) 
               else okHttpClient.newCall(request)
        call?.enqueue(this)
    }

    override fun onResponse(call: Call, response: Response) {
        // 6. 图片解密
        if (ImageUtils.skipDecode(source, !manga)) {
            onStreamReady(responseBody!!.byteStream())
        } else {
            val decodeResult = ImageUtils.decode(
                url.toString(),
                responseBody!!.bytes(),
                isCover = !manga,
                source,
                ReadManga.book
            )
            onStreamReady(decodeResult?.inputStream())
        }
    }
}
```

**加载流程：**
```
1. 检查失败URL缓存 → 跳过已知失败的图片
       ↓
2. 检查WiFi限制 → 非WiFi时跳过
       ↓
3. 获取书源 → 用于请求头和解密规则
       ↓
4. AnalyzeUrl解析 → 处理重定向、添加书源请求头
       ↓
5. OkHttp请求 → 漫画用专用客户端
       ↓
6. 图片解密 → 执行书源的解密规则
       ↓
7. 返回InputStream → Glide处理显示
```

---

## 三、图片解密机制

### ImageUtils

[ImageUtils.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/utils/ImageUtils.kt) 处理加密图片的解密：

```kotlin
object ImageUtils {
    fun decode(
        src: String, bytes: ByteArray, isCover: Boolean,
        source: BaseSource?, book: Book? = null
    ): ByteArray? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return bytes  // 无解密规则，直接返回

        return source?.evalJS(ruleJs) {
            put("book", book)
            put("result", bytes)   // 待解密的图片数据
            put("src", src)        // 图片URL
        } as ByteArray
    }

    private fun getRuleJs(source: BaseSource?, isCover: Boolean): String? {
        return when (source) {
            is BookSource -> if (isCover) source.coverDecodeJs      // 封面解密规则
                             else source.getContentRule().imageDecode  // 正文图片解密规则
            is RssSource -> source.coverDecodeJs
            else -> null
        }
    }
}
```

**解密规则位置：**
| 图片类型 | 规则字段 | 说明 |
|---------|---------|------|
| 书籍封面 | `BookSource.coverDecodeJs` | 详情页封面解密 |
| 正文图片 | `BookSource.ruleContent.imageDecode` | 章节内图片解密 |
| RSS封面 | `RssSource.coverDecodeJs` | 订阅源封面解密 |

**解密规则示例：**
```javascript
// AES解密
java.createSymmetricCrypto("AES/CBC/PKCS5Padding", key, iv).decrypt(result)

// XOR解密
(function() {
    var input = new Packages.java.io.ByteArrayInputStream(result);
    var out = new Packages.java.io.ByteArrayOutputStream();
    var byte;
    while ((byte = input.read()) != -1) {
        out.write(byte ^ 0x55);
    }
    return out.toByteArray();
})()
```

---

## 四、图片缓存机制

### 1. Glide内置缓存

Glide自动管理两级缓存：

| 缓存层级 | 存储位置 | 大小限制 | 生命周期 |
|---------|---------|---------|---------|
| 内存缓存 | RAM | 自动计算 | 应用运行期间 |
| 磁盘缓存 | `cache/image_manager_disk_cache` | 1GB | 持久化 |

### 2. BookHelp图片缓存

[BookHelp.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/book/BookHelp.kt) 管理书籍章节图片的本地缓存：

```kotlin
object BookHelp {
    private const val cacheFolderName = "book_cache"
    private const val cacheImageFolderName = "images"

    // 图片存储路径：book_cache/{书名MD5}/images/{图片URL的MD5}.jpg
    fun getImage(book: Book, src: String): File {
        return downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName,
            "${MD5Utils.md5Encode16(src)}.${getImageSuffix(src)}"
        )
    }

    // 保存图片
    fun writeImage(book: Book, src: String, bytes: ByteArray) {
        getImage(book, src).createFileIfNotExist().writeBytes(bytes)
    }

    // 检查图片是否存在
    fun isImageExist(book: Book, src: String): Boolean {
        return getImage(book, src).exists()
    }
}
```

**图片缓存目录结构：**
```
Android/data/io.legado.app/files/
└── book_cache/
    └── {书名MD5}/
        ├── {章节1}.txt        # 章节文本缓存
        ├── {章节2}.txt
        └── images/
            ├── a1b2c3d4e5f6g7h8.jpg   # 图片URL的MD5作为文件名
            ├── b2c3d4e5f6g7h8i9.png
            └── ...
```

### 3. 漫画图片缓存清理

```kotlin
// 清理已看过的漫画图片（保留当前章节前后指定数量）
private fun clearComicCache(book: Book) {
    if (!book.isImage || AppConfig.imageRetainNum == 0) return

    val startIndex = book.durChapterIndex - AppConfig.imageRetainNum
    val endIndex = book.durChapterIndex + AppConfig.preDownloadNum
    val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl, startIndex, endIndex)

    // 获取需要保留的图片
    val imgNames = hashSetOf<String>()
    chapterList.forEach { chapter ->
        getContent(book, chapter)?.let { content ->
            val matcher = AppPattern.imgPattern.matcher(content)
            while (matcher.find()) {
                val src = matcher.group(1) ?: continue
                val mSrc = NetworkUtils.getAbsoluteURL(chapter.url, src)
                imgNames.add("${MD5Utils.md5Encode16(mSrc)}.${getImageSuffix(mSrc)}")
            }
        }
    }

    // 删除不需要的图片
    downloadDir.getFile(cacheFolderName, book.getFolderName(), cacheImageFolderName)
        .listFiles()?.forEach { imgFile ->
            if (!imgNames.contains(imgFile.name)) {
                imgFile.delete()
            }
        }
}
```

---

## 五、正文图片加载

### 1. 图片提取

正文中的图片通过正则表达式提取：

```kotlin
// AppPattern.kt
val imgPattern: Pattern = Pattern.compile("<img[^>]*src=\"([^\"]*(?:\"[^>]+\\})?)\"[^>]*>")

// BookHelp.kt
fun flowImages(bookChapter: BookChapter, content: String): Flow<String> {
    return flow {
        val matcher = AppPattern.imgPattern.matcher(content)
        while (matcher.find()) {
            val src = matcher.group(1) ?: continue
            val mSrc = NetworkUtils.getAbsoluteURL(bookChapter.url, src)
            emit(mSrc)
        }
    }
}
```

### 2. 图片预下载

[TextChapterLayout.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt) 在布局章节时预下载图片：

```kotlin
init {
    job = Coroutine.async(scope, start = CoroutineStart.LAZY, executeContext = IO) {
        // 预下载章节内所有图片
        launch {
            val bookSource = book.getBookSource() ?: return@launch
            BookHelp.saveImages(bookSource, book, bookChapter, bookContent.toString())
        }
        getTextChapter(book, bookChapter, displayTitle, bookContent)
    }
}
```

### 3. 图片保存

```kotlin
suspend fun saveImages(
    bookSource: BookSource,
    book: Book,
    bookChapter: BookChapter,
    content: String,
    concurrency: Int = AppConfig.threadCount
) = coroutineScope {
    flowImages(bookChapter, content).onEachParallel(concurrency) { mSrc ->
        saveImage(bookSource, book, mSrc, bookChapter)
    }.collect()
}

suspend fun saveImage(
    bookSource: BookSource?,
    book: Book,
    src: String,
    chapter: BookChapter? = null
) {
    if (isImageExist(book, src)) return  // 已存在，跳过

    val mutex = downloadImages.getOrPut(src) { Mutex() }
    mutex.lock()  // 防止并发下载同一图片
    try {
        if (isImageExist(book, src)) return  // 双重检查

        val analyzeUrl = AnalyzeUrl(src, source = bookSource)
        val bytes = analyzeUrl.getByteArrayAwait()

        // 执行解密
        val decodedBytes = ImageUtils.decode(src, bytes, isCover = false, bookSource, book)
        if (checkImage(decodedBytes)) {
            writeImage(book, src, decodedBytes)
        }
    } finally {
        downloadImages.remove(src)
        mutex.unlock()
    }
}
```

### 4. GlideImageGetter（HTML图片加载）

[GlideImageGetter.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/GlideImageGetter.kt) 用于在TextView中显示HTML图片：

```kotlin
class GlideImageGetter(
    context: Context,
    textView: TextView,
    private val lifecycle: Lifecycle,
    private val availableWidth: Int,
    private val sourceOrigin: String? = null
) : Html.ImageGetter {

    override fun getDrawable(source: String?): Drawable {
        // 1. 处理Base64图片
        if (source.startsWith("data")) {
            val inputStream = ByteArrayInputStream(Base64.decode(source.substringAfter(",")))
            val (drawable, size) = SvgUtils.createDrawable(inputStream) ?: return emptyDrawable
            drawable.bounds = getDrawableRect(size, urlOption)
            return drawable
        }

        // 2. 检查缓存
        cacheDrawable[source]?.let { return it }

        // 3. 创建占位Drawable
        val urlDrawable = GlideUrlDrawable()
        cacheDrawable[source] = urlDrawable

        // 4. 异步加载
        var options = RequestOptions()
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        Glide.with(context).lifecycle(lifecycle)
            .load(source)
            .apply(options)
            .into(ImageTarget(urlDrawable, source, urlOption))

        return urlDrawable
    }
}
```

**支持的图片选项：**
```html
<img src="https://example.com/img.jpg,{"width":"50%","style":"center","click":"java.toast('clicked')"}">
```

| 选项 | 说明 |
|------|------|
| `width` | 宽度，支持像素值或百分比 |
| `style` | 样式：`center`/`left`/`right`/`text`/`full`/`single` |
| `click` | 点击执行的JS代码 |

---

## 六、漫画图片加载

### ReadManga模型

[ReadManga.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/model/ReadManga.kt) 是漫画阅读的核心模型：

```kotlin
object ReadManga : CoroutineScope by MainScope() {
    var book: Book? = null
    var bookSource: BookSource? = null
    var curMangaChapter: MangaChapter? = null
    var prevMangaChapter: MangaChapter? = null
    var nextMangaChapter: MangaChapter? = null

    // 预下载信号量（限制并发）
    val preDownloadSemaphore = Semaphore(ReadConstants.PRE_DOWNLOAD_CONCURRENCY)

    // 加载当前章节及前后章节
    fun loadContent() {
        clearMangaChapter()
        loadContent(durChapterIndex)
        if (AppConfig.preDownloadNum > 0) {
            loadContent(durChapterIndex + 1)  // 预加载下一章
        }
        if (AppConfig.backwardPreDownloadNum > 0) {
            loadContent(durChapterIndex - 1)  // 预加载上一章
        }
    }
}
```

### 漫画章节结构

```kotlin
data class MangaChapter(
    val chapter: BookChapter,
    val pages: List<BaseMangaPage>,
    val imageCount: Int
)

data class MangaPage(
    val chapterIndex: Int,
    val chapterSize: Int,
    val mImageUrl: String,
    val index: Int,
    val mChapterName: String,
    var imageCount: Int = 0
) : BaseMangaPage
```

### 漫画图片加载特点

1. **专用OkHttp客户端**：`okHttpClientManga`，超时时间更长
2. **预下载机制**：提前下载前后章节图片
3. **失败重试限制**：最多重试 `MAX_DOWNLOAD_FAIL_COUNT` 次
4. **并发控制**：通过信号量限制预下载并发数

---

## 七、图片样式控制

### 正文图片样式

在书源正文规则中可以设置图片样式：

```kotlin
// Book.kt
companion object {
    const val imgStyleFull = "full"      // 铺满
    const val imgStyleSingle = "single"  // 单页
    const val imgStyleText = "text"      // 文字模式（小图）
}
```

**样式说明：**
| 样式 | 效果 |
|------|------|
| `full` | 图片铺满屏幕宽度 |
| `single` | 每张图片单独一页 |
| `text` | 图片缩小到文字大小，内联显示 |
| `TEXT` | 同text，但占1.5个字符宽度 |

### 图片链接控制

```javascript
// 在书源正文规则中
var url = `https://example.com/img.jpg,{
    "width": "50%",
    "style": "center",
    "click": "java.toast('图片被点击')"
}`;
result = `<img src="${url}">`;
```

---

## 八、关键配置项

### AppConfig

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `threadCount` | 图片下载并发数 | 4 |
| `preDownloadNum` | 向前预下载章节数 | 5 |
| `backwardPreDownloadNum` | 向后预下载章节数 | 0 |
| `imageRetainNum` | 漫画图片保留数量 | 0（不清除） |

### ReadConstants

| 常量 | 值 | 说明 |
|------|-----|------|
| `PRE_DOWNLOAD_CONCURRENCY` | 3 | 预下载并发数 |
| `PRE_DOWNLOAD_DELAY_MS` | 500 | 预下载延迟（毫秒） |
| `MAX_DOWNLOAD_FAIL_COUNT` | 3 | 最大下载失败次数 |

---

## 九、完整加载流程图

```
┌─────────────────────────────────────────────────────────────────┐
│                        图片加载请求                              │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │   ImageLoader.load  │
                    │   判断路径类型       │
                    └──────────┬──────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
        ▼                      ▼                      ▼
   ┌─────────┐          ┌─────────────┐        ┌──────────┐
   │ 网络URL  │          │ Base64/Data │        │ 本地文件  │
   └────┬────┘          └──────┬──────┘        └────┬─────┘
        │                      │                    │
        ▼                      ▼                    ▼
┌───────────────┐      ┌─────────────────┐  ┌──────────────┐
│OkHttpModelLoader│     │LegadoDataUrlLoader│ │FilePathLoader│
└───────┬───────┘      └────────┬────────┘  └──────┬───────┘
        │                       │                  │
        ▼                       │                  │
┌───────────────────┐           │                  │
│OkHttpStreamFetcher│           │                  │
└─────────┬─────────┘           │                  │
          │                     │                  │
          ▼                     │                  │
   ┌──────────────┐             │                  │
   │ 检查失败缓存 │             │                  │
   └──────┬───────┘             │                  │
          │                     │                  │
          ▼                     │                  │
   ┌──────────────┐             │                  │
   │ 检查WiFi限制 │             │                  │
   └──────┬───────┘             │                  │
          │                     │                  │
          ▼                     │                  │
   ┌──────────────┐             │                  │
   │ 获取书源信息 │             │                  │
   └──────┬───────┘             │                  │
          │                     │                  │
          ▼                     │                  │
   ┌──────────────┐             │                  │
   │ AnalyzeUrl   │             │                  │
   │ 解析URL/请求头│            │                  │
   └──────┬───────┘             │                  │
          │                     │                  │
          ▼                     │                  │
   ┌──────────────┐             │                  │
   │ OkHttp请求   │             │                  │
   └──────┬───────┘             │                  │
          │                     │                  │
          ▼                     │                  │
   ┌──────────────┐             │                  │
   │ ImageUtils   │             │                  │
   │ 图片解密     │             │                  │
   └──────┬───────┘             │                  │
          │                     │                  │
          └─────────────────────┼──────────────────┘
                                │
                                ▼
                    ┌─────────────────────┐
                    │   Glide缓存/显示    │
                    └─────────────────────┘
```

---

## 十、核心代码文件

| 文件 | 说明 |
|------|------|
| `app/src/main/java/io/legado/app/help/glide/ImageLoader.kt` | 图片加载统一入口 |
| `app/src/main/java/io/legado/app/help/glide/LegadoGlideModule.kt` | Glide配置模块 |
| `app/src/main/java/io/legado/app/help/glide/OkHttpModelLoader.kt` | 网络图片加载器 |
| `app/src/main/java/io/legado/app/help/glide/OkHttpStreamFetcher.kt` | 网络图片获取执行 |
| `app/src/main/java/io/legado/app/utils/ImageUtils.kt` | 图片解密工具 |
| `app/src/main/java/io/legado/app/help/book/BookHelp.kt` | 书籍图片缓存管理 |
| `app/src/main/java/io/legado/app/help/GlideImageGetter.kt` | HTML图片加载器 |
| `app/src/main/java/io/legado/app/model/ReadManga.kt` | 漫画阅读模型 |

---

## 十一、常见问题

**Q1: 图片加载失败怎么办？**

1. 检查网络连接
2. 检查书源是否需要登录
3. 检查图片URL是否需要特殊请求头
4. 查看日志中的错误信息

**Q2: 如何清除图片缓存？**

- Glide缓存：设置 → 清除缓存
- 书籍图片缓存：书籍详情页 → 清理缓存

**Q3: 漫画图片加载慢怎么办？**

1. 降低预下载数量
2. 检查书源并发率设置
3. 使用更快的网络

**Q4: 图片显示乱码或损坏？**

1. 检查书源的图片解密规则是否正确
2. 检查图片URL是否正确
3. 尝试刷新章节
