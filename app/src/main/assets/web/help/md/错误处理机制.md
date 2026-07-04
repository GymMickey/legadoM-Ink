# 错误处理机制详解

## 概述

阅读APP拥有一套完善的错误处理机制，覆盖从网络请求到书源解析的全流程。错误处理系统设计遵循以下原则：

1. **轻量化** - 使用 `NoStackTraceException` 避免不必要的堆栈跟踪开销
2. **分类明确** - 不同类型的错误有专门的异常类
3. **用户友好** - 错误信息可读性强，便于用户理解问题
4. **可恢复性** - 部分错误支持自动重试或降级处理

---

## 一、异常类层次结构

```
Throwable
└── Exception
    └── NoStackTraceException (基础异常类，不记录堆栈)
        ├── ContentEmptyException (正文为空)
        ├── TocEmptyException (目录为空)
        ├── RegexTimeoutException (正则超时)
        ├── ConcurrentException (并发限制)
        ├── EmptyFileException (文件为空)
        ├── NoBooksDirException (无书籍目录)
        └── InvalidBooksDirException (无效书籍目录)
```

---

## 二、核心异常类说明

### 1. NoStackTraceException（基础异常类）

**文件位置：** `app/src/main/java/io/legado/app/exception/NoStackTraceException.kt`

```kotlin
open class NoStackTraceException(msg: String) : Exception(msg) {
    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyStackTrace
        return this
    }
}
```

**设计目的：**
- 大多数错误只需要错误消息，不需要完整的堆栈跟踪
- 避免填充堆栈跟踪的性能开销（`fillInStackTrace()` 是昂贵操作）
- 适用于预期的业务错误，而非系统级异常

**使用场景：**
- 规则解析错误
- 数据为空
- 配置缺失

---

### 2. ContentEmptyException（正文为空异常）

**文件位置：** `app/src/main/java/io/legado/app/exception/ContentEmptyException.kt`

```kotlin
class ContentEmptyException(msg: String) : NoStackTraceException(msg)
```

**触发时机：** 书源解析正文内容后，结果为空字符串

**代码示例：**
```kotlin
// BookContent.kt
if (!bookChapter.isVolume && contentStr.isBlank()) {
    throw ContentEmptyException("内容为空")
}
```

**用户提示：** "内容为空" 或 "正文获取失败"

---

### 3. TocEmptyException（目录为空异常）

**文件位置：** `app/src/main/java/io/legado/app/exception/TocEmptyException.kt`

```kotlin
class TocEmptyException(msg: String) : NoStackTraceException(msg)
```

**触发时机：** 书源解析章节目录后，列表为空

**代码示例：**
```kotlin
// BookChapterList.kt
if (chapterList.isEmpty()) {
    throw TocEmptyException(appCtx.getString(R.string.chapter_list_empty))
}
```

**用户提示：** "目录为空" 或 "章节列表为空"

---

### 4. RegexTimeoutException（正则超时异常）

**文件位置：** `app/src/main/java/io/legado/app/exception/RegexTimeoutException.kt`

```kotlin
class RegexTimeoutException(msg: String) : NoStackTraceException(msg)
```

**触发时机：** 替换规则中的正则表达式执行超时（默认3秒）

**处理机制：**
- 超时后自动禁用该规则
- 写入数据库标记为禁用
- 防止恶意或低效正则导致应用卡死

---

### 5. ConcurrentException（并发限制异常）

**文件位置：** `app/src/main/java/io/legado/app/exception/ConcurrentException.kt`

```kotlin
class ConcurrentException(msg: String, val waitTime: Long) : NoStackTraceException(msg)
```

**触发时机：** 书源并发率限制触发

**特殊属性：**
- `waitTime` - 需要等待的时间（毫秒）

**用途：** 控制对同一书源的并发访问频率

---

## 三、网络请求错误处理

### 错误码定义

**文件位置：** `app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt`

| 错误码 | 含义 | 异常类型 |
|--------|------|----------|
| -1 | 超过设定时间 | `InterruptedIOException` (timeout) |
| -2 | 超时错误 | `SocketTimeoutException` |
| -3 | 未找到域名 | `UnknownHostException` |
| -4 | 连接被拒绝 | `ConnectException` |
| -5 | Socket错误/连接重置 | `SocketException` |
| -6 | SSL证书/握手错误 | `SSLException` |
| -7 | 其它错误 | 其他异常 |

### 错误处理流程

```kotlin
// AnalyzeUrl.kt
try {
    // 执行网络请求
    return strResponse
} catch (e: Exception) {
    if (!isTest) {
        throw e  // 非测试模式，直接抛出异常
    }
    // 测试模式，返回带错误码的响应
    val errorCode = when (e) {
        is SocketTimeoutException -> -2
        is UnknownHostException -> -3
        is ConnectException -> -4
        is SocketException -> -5
        is SSLException -> -6
        is InterruptedIOException -> if (e.message?.contains("timeout") == true) -1 else -7
        else -> -7
    }
    return StrResponse(url, e.message).apply {
        putCallTime(errorCode)
    }
}
```

### 错误响应构建

```kotlin
// 构建错误Response
fun getErrResponse(e: Throwable): Response = Response.Builder()
    .request(Request.Builder().url(url).build())
    .protocol(okhttp3.Protocol.HTTP_1_1)
    .code(500)
    .message(e.message ?: "Error Response")
    .body(e.stackTraceStr.toResponseBody(null))
    .build()

// 构建错误StrResponse
fun getErrStrResponse(e: Throwable): StrResponse =
    StrResponse(getErrResponse(e), e.stackTraceStr)
```

---

## 四、书源解析错误处理

### WebBook 核心错误处理模式

**文件位置：** `app/src/main/java/io/legado/app/model/webBook/WebBook.kt`

```kotlin
// 登录检测JS + 错误恢复模式
val res = kotlin.runCatching {
    analyzeUrl.getStrResponseAwait().let {
        if (!checkJs.isNullOrBlank()) {
            analyzeUrl.evalJS(checkJs, it) as StrResponse
        } else {
            it
        }
    }
}.getOrElse { throwable ->
    // 尝试通过登录检测JS恢复
    if (!checkJs.isNullOrBlank()) {
        val errResponse = analyzeUrl.getErrStrResponse(throwable)
        try {
            (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                if (it.code() == 500) {
                    throw throwable  // 恢复失败，抛出原异常
                }
            }
        } catch (_: Throwable) {
            throw throwable
        }
    } else {
        throw throwable
    }
}
```

**关键设计：**
1. **首次尝试** - 正常发起请求
2. **错误恢复** - 如果配置了 `loginCheckJs`，尝试通过JS重新登录
3. **最终失败** - 如果恢复后仍返回500，抛出原异常

### 各阶段错误处理

| 阶段 | 错误类型 | 处理方式 |
|------|----------|----------|
| 搜索 | `NoStackTraceException` | URL为空时抛出 |
| 发现 | `NoStackTraceException` | URL为空时抛出 |
| 详情 | `NoStackTraceException` | 内容为空时抛出 |
| 目录 | `TocEmptyException` | 目录为空时抛出 |
| 正文 | `ContentEmptyException` | 正文为空时抛出 |

---

## 五、全局崩溃处理

### CrashHandler

**文件位置：** `app/src/main/java/io/legado/app/help/CrashHandler.kt`

**功能：**
1. 捕获未处理的异常
2. 保存崩溃日志到文件
3. 可选复制日志到剪贴板
4. OOM时自动进行堆转储

**初始化：**
```kotlin
class CrashHandler(val context: Context) : Thread.UncaughtExceptionHandler {
    private var mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }
}
```

**崩溃处理流程：**
```kotlin
override fun uncaughtException(thread: Thread, ex: Throwable) {
    if (shouldAbsorb(ex)) {
        // 某些异常可以吸收，不导致崩溃
        AppLog.put("发生未捕获的异常\n${ex.localizedMessage}", ex)
        Looper.loop()
    } else {
        ReadAloud.stop(context)  // 停止朗读
        handleException(ex)       // 保存日志
        mDefaultHandler?.uncaughtException(thread, ex)  // 交给系统处理
    }
}
```

**可吸收的异常：**
- `CannotDeliverBroadcastException`
- 特定的 `SecurityException`

**崩溃日志保存：**
- 保存路径：`备份路径/crash/` 或 `外部缓存/crash/`
- 文件名格式：`crash-yyyy-MM-dd-HH-mm-ss-timestamp.log`
- 自动清理7天前的日志

---

## 六、日志系统

### AppLog

**文件位置：** `app/src/main/java/io/legado/app/constant/AppLog.kt`

**日志类型：**
- `mLogs` - 应用日志（最多100条）
- `mSourceLogs` - 书源日志（最多200条）

**主要方法：**

```kotlin
// 记录日志（可选toast提示）
fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false)

// 记录书源日志
fun putSource(message: String?, throwable: Throwable? = null, subCategory: SourceSubCategory = SourceSubCategory.UPDATE)

// 调试日志（受recordLog配置控制）
fun putDebug(message: String?, throwable: Throwable? = null)
```

**日志事件中心：**
```kotlin
// 异步发送到调试事件中心
GlobalScope.launch(Dispatchers.IO) {
    DebugEventCenter.emit(
        DebugEvent(
            level = if (throwable != null) DebugLevel.ERROR else DebugLevel.INFO,
            category = DebugCategory.APP,
            message = message,
            detail = throwable?.stackTraceToString(),
            throwable = throwable
        )
    )
}
```

---

## 七、错误恢复策略

### 1. 登录检测恢复

当网络请求失败时，如果书源配置了 `loginCheckJs`，会尝试重新登录：

```
请求失败 → 执行loginCheckJs → 重新获取响应 → 成功/失败
```

### 2. 自动换源

当书源失效时，自动切换到其他可用书源：

```
书源失效 → 检查其他书源 → 自动切换 → 继续阅读
```

### 3. 正则超时保护

替换规则中的正则超时后：
- 自动禁用该规则
- 记录到数据库
- 防止应用卡死

### 4. 协程取消检查

在长时间操作中定期检查协程状态：

```kotlin
currentCoroutineContext().ensureActive()
```

---

## 八、用户提示错误信息

### 常见错误提示

| 场景 | 错误提示 |
|------|----------|
| 搜索URL为空 | "搜索url不能为空" |
| 网页内容获取失败 | "获取网页内容失败: {url}" |
| 目录为空 | "目录为空" / "章节列表为空" |
| 正文为空 | "内容为空" |
| 网络超时 | "网络连接超时" |
| 域名解析失败 | "无法解析域名" |
| SSL错误 | "证书验证失败" |
| 未找到书籍 | "没有搜索到<{书名}>{作者}" |

### 错误提示来源

1. **字符串资源** - `strings.xml` 中的多语言文本
2. **异常消息** - `NoStackTraceException` 的 `msg` 参数
3. **动态拼接** - 结合上下文信息生成

---

## 九、调试与排错

### 书源调试

**文件位置：** `app/src/main/java/io/legado/app/model/Debug.kt`

调试模式下会输出详细的解析日志：
- 请求URL
- 响应状态
- 规则执行结果
- 错误信息

### FlowLogRecorder

用于记录详细的流程日志，包括：
- 网络请求日志
- 规则提取日志
- 替换操作日志

### 查看日志方式

1. **应用内日志** - 我的 → 帮助 → 日志
2. **崩溃日志** - 备份目录下的 `crash/` 文件夹
3. **书源调试** - 书源编辑 → 调试

---

## 十、最佳实践

### 书源开发建议

1. **提供有意义的错误信息**
   ```javascript
   // 好的做法
   if (!result) {
       throw "未找到书籍信息，请检查书名和作者";
   }
   
   // 不好的做法
   if (!result) {
       throw "error";
   }
   ```

2. **使用 try-catch 处理可能失败的操作**
   ```javascript
   try {
       var data = JSON.parse(result);
   } catch (e) {
       java.log("JSON解析失败: " + e.message);
       return null;
   }
   ```

3. **合理使用超时设置**
   ```
   https://example.com,{"callTimeout": 30000}
   ```

### 错误处理原则

1. **区分预期错误和意外错误**
   - 预期错误：使用 `NoStackTraceException`
   - 意外错误：使用标准异常

2. **提供恢复路径**
   - 登录失效 → 重新登录
   - 书源失效 → 自动换源
   - 网络超时 → 重试

3. **记录足够的上下文**
   - 书源名称
   - 请求URL
   - 错误原因

---

## 十一、核心文件索引

| 文件 | 说明 |
|------|------|
| `exception/NoStackTraceException.kt` | 基础异常类 |
| `exception/ContentEmptyException.kt` | 正文为空异常 |
| `exception/TocEmptyException.kt` | 目录为空异常 |
| `exception/RegexTimeoutException.kt` | 正则超时异常 |
| `exception/ConcurrentException.kt` | 并发限制异常 |
| `model/analyzeRule/AnalyzeUrl.kt` | 网络请求错误处理 |
| `model/webBook/WebBook.kt` | 书源请求错误处理 |
| `model/webBook/BookContent.kt` | 正文解析错误处理 |
| `model/webBook/BookChapterList.kt` | 目录解析错误处理 |
| `help/CrashHandler.kt` | 全局崩溃处理 |
| `constant/AppLog.kt` | 日志系统 |
