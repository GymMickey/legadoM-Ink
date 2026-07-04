# 网络请求机制详解

## 快速理解

把阅读的网络请求系统比喻成**快递配送中心**：

1. **OkHttpClient** → 配送中心的总调度，管理所有配送车辆和人员
2. **AnalyzeUrl** → 订单处理员，解析URL、配置请求参数、处理特殊需求
3. **拦截器链** → 安检流程，每个包裹都要经过多层检查和处理
4. **CookieManager** → 客户档案管理，记住每个客户的偏好和历史
5. **ConcurrentRateLimiter** → 流量控制，防止某个客户请求过于频繁

***

## 一、整体架构

```mermaid
graph TB
    subgraph "应用层 Application Layer"
        A[书源规则] --> B[AnalyzeUrl]
        B --> C[WebBook]
    end

    subgraph "网络层 Network Layer"
        C --> D[OkHttpClient]
        D --> E{拦截器链}
        E --> F[OkHttpExceptionInterceptor]
        F --> G[默认请求头拦截器]
        G --> H[CookieJar拦截器]
        H --> I[DecompressInterceptor]
        I --> J[UrlRecordInterceptor]
    end

    subgraph "连接层 Connection Layer"
        J --> K[ConnectionPool]
        K --> L[DNS解析]
        L --> M[SSL/TLS握手]
        M --> N[服务器]
    end

    subgraph "辅助系统 Support Systems"
        O[CookieStore] --> H
        P[ConcurrentRateLimiter] --> B
        Q[CacheManager] --> O
        R[ProxyClient缓存] --> D
    end
```

***

## 二、核心组件详解

### 1. OkHttpClient（HTTP客户端）

**文件位置：** [HttpHelper.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/HttpHelper.kt)

OkHttpClient是所有网络请求的基础设施，采用单例模式：

```kotlin
val okHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)    // 连接超时
        .writeTimeout(15, TimeUnit.SECONDS)      // 写入超时
        .readTimeout(60, TimeUnit.SECONDS)       // 读取超时
        .callTimeout(60, TimeUnit.SECONDS)       // 整体超时
        .retryOnConnectionFailure(true)          // 连接失败自动重试
        .connectionSpecs(specs)                  // TLS配置
        .followRedirects(true)                   // 跟随重定向
        .followSslRedirects(true)               // 跟随SSL重定向
        .addInterceptor(...)                     // 添加拦截器
        .build()
}
```

**关键配置项：**

| 配置项                        | 默认值  | 说明           |
| -------------------------- | ---- | ------------ |
| `connectTimeout`           | 15秒  | 建立TCP连接的超时时间 |
| `writeTimeout`             | 15秒  | 发送请求数据的超时时间  |
| `readTimeout`              | 60秒  | 读取响应数据的超时时间  |
| `callTimeout`              | 60秒  | 整个请求的超时时间    |
| `retryOnConnectionFailure` | true | 连接失败时自动重试    |
| `followRedirects`          | true | 自动跟随HTTP重定向  |

***

### 2. AnalyzeUrl（URL解析与请求构建）

**文件位置：** [AnalyzeUrl.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt)

AnalyzeUrl是网络请求的核心类，负责：

1. **URL解析** - 处理书源规则中的URL字符串
2. **参数处理** - 解析URL参数、请求体、请求头
3. **JS执行** - 执行URL中嵌入的JavaScript代码
4. **并发控制** - 通过ConcurrentRateLimiter限制请求频率
5. **Cookie管理** - 自动加载和保存Cookie

#### 2.1 URL格式解析

阅读支持复杂的URL格式：

```
基础格式:
https://example.com/api

带参数格式:
https://example.com/api,{"method":"POST","body":"data=123"}

完整参数格式:
https://example.com/api,{
    "method": "POST",
    "headers": {"User-Agent": "xxx"},
    "body": "data=123",
    "charset": "UTF-8",
    "retry": 3,
    "webView": true,
    "webJs": "document.body.innerHTML",
    "js": "java.url = java.url + '&token=xxx'",
    "bodyJs": "result.replace(/<[^>]+>/g, '')",
    "dnsIp": "8.8.8.8"
}
```

#### 2.2 UrlOption参数说明

| 参数                 | 类型            | 说明                 |
| ------------------ | ------------- | ------------------ |
| `method`           | String        | 请求方法：GET/POST/HEAD |
| `headers`          | Map           | 自定义请求头             |
| `body`             | String/Object | 请求体内容              |
| `charset`          | String        | 字符编码               |
| `retry`            | Int           | 重试次数               |
| `type`             | String        | 资源类型标识             |
| `webView`          | Boolean       | 是否使用WebView请求      |
| `webJs`            | String        | WebView中执行的JS      |
| `js`               | String        | 解析URL后执行的JS        |
| `bodyJs`           | String        | 获取响应后执行的JS         |
| `dnsIp`            | String        | 强制指定DNS解析IP        |
| `webViewDelayTime` | Long          | WebView延迟时间(毫秒)    |

#### 2.3 JS变量注入

在URL的JS代码中可使用的变量：

```javascript
// 基础变量
java        // JsExtensions实例，提供各种工具方法
baseUrl     // 当前基础URL
result      // 上一步的结果
source      // 当前书源对象
book        // 当前书籍对象
cookie      // CookieStore实例
cache       // CacheManager实例

// 搜索相关
key         // 搜索关键词
page        // 当前页码

// TTS相关
speakText   // 朗读文本
speakSpeed  // 朗读速度
```

#### 2.4 请求执行流程

```kotlin
suspend fun getStrResponseAwait(): StrResponse {
    // 1. 并发控制
    concurrentRateLimiter.withLimit {
        // 2. 设置Cookie
        setCookie()
        
        // 3. 判断是否使用WebView
        if (useWebView) {
            // WebView请求
            BackstageWebView(...).getStrResponse()
        } else {
            // OkHttp请求
            getClient().newCallStrResponse(retry) {
                addHeaders(headerMap)
                when (method) {
                    POST -> postForm/postJson(...)
                    HEAD -> head()
                    else -> get(...)
                }
            }
        }
    }
}
```

***

### 3. 拦截器链（Interceptor Chain）

拦截器按顺序执行，每个请求都会经过所有拦截器：

```
请求 → OkHttpExceptionInterceptor → 默认请求头拦截器 → CookieJar拦截器 
     → DecompressInterceptor → UrlRecordInterceptor → 服务器
     
响应 ← OkHttpExceptionInterceptor ← 默认请求头拦截器 ← CookieJar拦截器
     ← DecompressInterceptor ← UrlRecordInterceptor ← 服务器
```

#### 3.1 OkHttpExceptionInterceptor

**文件位置：** [OkHttpExceptionInterceptor.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/OkHttpExceptionInterceptor.kt)

异常捕获拦截器，将所有非IOException异常包装为IOException：

```kotlin
object OkHttpExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            return chain.proceed(chain.request())
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw IOException(e)  // 统一异常类型
        }
    }
}
```

#### 3.2 默认请求头拦截器

自动添加通用请求头：

```kotlin
.addInterceptor { chain ->
    val request = chain.request()
    val builder = request.newBuilder()
    
    // 自动添加User-Agent
    if (request.header("User-Agent") == null) {
        builder.addHeader("User-Agent", AppConfig.userAgent)
    }
    
    // 添加Keep-Alive
    builder.addHeader("Keep-Alive", "300")
    builder.addHeader("Connection", "Keep-Alive")
    builder.addHeader("Cache-Control", "no-cache")
    
    chain.proceed(builder.build())
}
```

#### 3.3 CookieJar拦截器

**触发条件：** 书源启用了`enabledCookieJar`选项

```kotlin
.addNetworkInterceptor { chain ->
    var request = chain.request()
    val enableCookieJar = request.header("CookieJar") != null

    if (enableCookieJar) {
        // 加载Cookie到请求
        request = CookieManager.loadRequest(request)
    }

    val networkResponse = chain.proceed(request)

    if (enableCookieJar) {
        // 从响应保存Cookie
        CookieManager.saveResponse(networkResponse)
    }
    networkResponse
}
```

#### 3.4 DecompressInterceptor

**文件位置：** [DecompressInterceptor.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/DecompressInterceptor.kt)

自动解压响应内容：

```kotlin
object DecompressInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // 自动添加Accept-Encoding
        if (request.header("Accept-Encoding") == null) {
            requestBuilder.header("Accept-Encoding", "gzip, deflate")
        }

        val response = chain.proceed(request)
        val encoding = response.header("Content-Encoding")?.lowercase()

        // 根据编码类型解压
        val source = when (encoding) {
            "gzip" -> GZIPInputStream(body.byteStream()).source().buffer()
            "deflate" -> InflaterInputStream(...).source().buffer()
            else -> return response
        }

        return response.newBuilder()
            .removeHeader("Content-Encoding")
            .body(source.asResponseBody(...))
            .build()
    }
}
```

#### 3.5 UrlRecordInterceptor

**文件位置：** [UrlRecordInterceptor.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/UrlRecordInterceptor.kt)

记录所有网络请求信息（可通过`AppConfig.recordUrl`开关控制）：

```kotlin
object UrlRecordInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val startTime = System.currentTimeMillis()
        
        try {
            response = chain.proceed(request)
            return response
        } finally {
            val duration = System.currentTimeMillis() - startTime
            
            // 异步写入数据库
            scope.launch {
                appDb.urlRecordDao.insert(UrlRecord(
                    url = url,
                    domain = domain,
                    method = request.method,
                    responseCode = responseCode,
                    duration = duration,
                    ...
                ))
                
                // 上报到调试事件中心
                DebugEventCenter.emit(DebugEvent(...))
            }
        }
    }
}
```

***

### 4. Cookie管理机制

**核心文件：**

- [CookieManager.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/CookieManager.kt)
- [CookieStore.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/CookieStore.kt)

#### 4.1 Cookie存储层级

```
内存缓存 (CacheManager)
  ├─ ${domain}_session_cookie ← 会话期Cookie，重启失效
  └─ ${domain}_cookie ← 持久化Cookie的内存缓存
          ↕ 同步
数据库 (Room)
  ├─ Cookie表 ← 持久化存储
  │   ├─ domain: 域名
  │   └─ cookie: Cookie字符串
  └─ ↑
```

#### 4.2 Cookie加载流程

```kotlin
fun getCookie(url: String): String {
    val domain = NetworkUtils.getSubDomain(url)

    // 1. 获取持久化Cookie
    val cookie = getCookieNoSession(url)
    
    // 2. 获取会话Cookie
    val sessionCookie = CookieManager.getSessionCookie(domain)

    // 3. 合并两个Cookie
    val cookieMap = mergeCookiesToMap(cookie, sessionCookie)
    
    // 4. 限制Cookie长度（最大4096字符）
    var ck = mapToCookie(cookieMap) ?: ""
    while (ck.length > 4096) {
        val removeKey = cookieMap.keys.random()
        CookieManager.removeCookie(url, removeKey)
        cookieMap.remove(removeKey)
        ck = mapToCookie(cookieMap) ?: ""
    }
    return ck
}
```

#### 4.3 Cookie保存流程

```kotlin
private fun saveCookiesFromHeaders(url: HttpUrl, headers: Headers) {
    val domain = NetworkUtils.getSubDomain(url.toString())
    val cookies = Cookie.parseAll(url, headers)

    // 会话期Cookie（non-persistent）存到内存
    val sessionCookie = cookies.filter { !it.persistent }.getString()
    updateSessionCookie(domain, sessionCookie)

    // 持久化Cookie存到数据库
    val cookieString = cookies.filter { it.persistent }.getString()
    CookieStore.replaceCookie(domain, cookieString)
}
```

#### 4.4 JS中的Cookie操作

```javascript
// 获取Cookie
cookie.getCookie(url)           // 获取URL对应域名的所有Cookie
cookie.getKey(url, key)         // 获取特定键的Cookie值

// 设置Cookie
cookie.setCookie(url, cookie)   // 设置Cookie
cookie.replaceCookie(url, cookie) // 替换/合并Cookie

// 删除Cookie
cookie.removeCookie(url)        // 删除URL对应域名的所有Cookie

// WebView Cookie
cookie.setWebCookie(url, cookie) // 同步到WebView
```

***

### 5. 并发控制机制

**文件位置：** [ConcurrentRateLimiter.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/ConcurrentRateLimiter.kt)

#### 5.1 并发率格式

书源的`concurrentRate`字段支持两种格式：

```
格式1: "1000"
  → 每次请求间隔1000毫秒

格式2: "20/60000"
  → 60000毫秒内最多访问20次
```

#### 5.2 并发控制原理

```kotlin
class ConcurrentRateLimiter(source: BaseSource?) {
    
    // 全局并发记录表
    companion object {
        val concurrentRecordMap = ConcurrentHashMap<String, ConcurrentRecord>()
    }
    
    data class ConcurrentRecord(
        var time: Long,         // 开始访问时间
        var accessLimit: Int,   // 限制次数
        var interval: Int,      // 间隔时间(毫秒)
        var frequency: Int      // 正在访问的个数
    )
    
    suspend fun getConcurrentRecord(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                delay(e.waitTime)  // 等待后重试
            }
        }
    }
    
    private fun fetchStart(): ConcurrentRecord? {
        val fetchRecord = concurrentRecordMap.computeIfAbsent(key) {
            ConcurrentRecord(nowTime, accessLimit, interval, 1)
        }
        
        synchronized(fetchRecord) {
            val nextTime = fetchRecord.time + fetchRecord.interval
            val nowTime = System.currentTimeMillis()
            
            if (nowTime >= nextTime) {
                // 已过限制时间，重置
                fetchRecord.time = nowTime
                fetchRecord.frequency = 1
                return 0
            }
            
            if (fetchRecord.frequency < fetchRecord.accessLimit) {
                // 未达限制次数，允许访问
                fetchRecord.frequency++
                return 0
            } else {
                // 需要等待
                throw ConcurrentException(waitTime = nextTime - nowTime)
            }
        }
    }
}
```

#### 5.3 使用方式

```kotlin
// 在AnalyzeUrl中自动应用
suspend fun getStrResponseAwait(): StrResponse {
    if (skipRateLimit) {
        return executeStrRequest(...)
    }
    concurrentRateLimiter.withLimit {
        return executeStrRequest(...)
    }
}
```

***

### 6. 代理设置

**文件位置：** [HttpHelper.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/HttpHelper.kt)

#### 6.1 代理格式

```
HTTP代理:
http://127.0.0.1:1080
http://127.0.0.1:1080@用户名@密码

SOCKS4代理:
socks4://127.0.0.1:1080

SOCKS5代理:
socks5://127.0.0.1:1080
socks5://127.0.0.1:1080@用户名@密码
```

#### 6.2 代理客户端缓存

```kotlin
private val proxyClientCache: ConcurrentHashMap<String, OkHttpClient> by lazy {
    ConcurrentHashMap()
}

fun getProxyClient(proxy: String? = null): OkHttpClient {
    if (proxy.isNullOrBlank()) {
        return okHttpClient  // 无代理，使用默认客户端
    }
    
    // 从缓存获取
    proxyClientCache[proxy]?.let { return it }
    
    // 解析代理配置
    val r = Regex("(http|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?")
    val group = r.findAll(proxy).first()
    
    val type = if (group.groupValues[1] == "http") "http" else "socks"
    val host = group.groupValues[2]
    val port = group.groupValues[3].toInt()
    
    // 构建代理客户端
    val builder = okHttpClient.newBuilder()
    if (type == "http") {
        builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
    } else {
        builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
    }
    
    // 设置代理认证
    if (username.isNotEmpty() && password.isNotEmpty()) {
        builder.proxyAuthenticator { _, response ->
            val credential = Credentials.basic(username, password)
            response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
    }
    
    val proxyClient = builder.build()
    proxyClientCache[proxy] = proxyClient  // 缓存
    return proxyClient
}
```

#### 6.3 书源中设置代理

在书源请求头中添加：

```json
{
  "proxy": "socks5://127.0.0.1:1080@user@pass"
}
```

***

### 7. SSL/TLS配置

**文件位置：** [SSLHelper.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/SSLHelper.kt)

#### 7.1 信任所有证书

当`AppConfig.unsafeSsl = true`时启用：

```kotlin
if (AppConfig.unsafeSsl) {
    builder.sslSocketFactory(unsafeSSLSocketFactory, unsafeTrustManager)
    builder.hostnameVerifier(unsafeHostnameVerifier)
}

// 信任所有证书的TrustManager
val unsafeTrustManager: X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

// 信任所有主机名
val unsafeHostnameVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }
```

#### 7.2 TLS连接规格

```kotlin
val specs = arrayListOf(
    ConnectionSpec.MODERN_TLS,     // 现代TLS（TLS 1.2+）
    ConnectionSpec.COMPATIBLE_TLS, // 兼容TLS
    ConnectionSpec.CLEARTEXT       // 明文HTTP
)
```

***

### 8. DNS配置

#### 8.1 自定义DNS缓存

```kotlin
if (AppConfig.addressCache.isNotEmpty()) {
    builder.dns { hostname ->
        val cachedAddress = AppConfig.addressCache[hostname]
        cachedAddress ?: Dns.SYSTEM.lookup(hostname)
    }
}
```

#### 8.2 强制指定IP

在URL参数中使用`dnsIp`：

```
https://example.com/api,{"dnsIp": "8.8.8.8"}
```

***

### 9. 响应处理

#### 9.1 StrResponse结构

**文件位置：** [StrResponse.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/StrResponse.kt)

```kotlin
class StrResponse {
    var raw: Response        // 原始OkHttp响应
    var body: String?        // 响应体文本
    var errorBody: ResponseBody?  // 错误响应体
    var callTime: Int        // 请求耗时(毫秒)或错误码
    
    fun url(): String        // 最终URL（可能经过重定向）
    fun code(): Int          // HTTP状态码
    fun message(): String    // 状态消息
    fun headers(): Headers   // 响应头
    fun isSuccessful(): Boolean  // 是否成功(2xx)
}
```

#### 9.2 错误码定义

在测试模式下返回的错误码：

| 错误码 | 含义       | 异常类型                             |
| --- | -------- | -------------------------------- |
| -1  | 超过设定时间   | InterruptedIOException (timeout) |
| -2  | 超时错误     | SocketTimeoutException           |
| -3  | 未找到域名    | UnknownHostException             |
| -4  | 连接被拒绝    | ConnectException                 |
| -5  | Socket错误 | SocketException                  |
| -6  | SSL证书错误  | SSLException                     |
| -7  | 其它错误     | 其他Exception                      |

#### 9.3 字符编码检测

```kotlin
fun ResponseBody.text(encode: String? = null): String {
    val responseBytes = Utf8BomUtils.removeUTF8BOM(bytes())
    
    // 1. 使用指定编码
    encode?.let { return String(responseBytes, Charset.forName(it)) }
    
    // 2. 使用HTTP头中的编码
    contentType()?.charset()?.let { 
        return String(responseBytes, it) 
    }
    
    // 3. 自动检测编码
    val charsetName = EncodingDetect.getHtmlEncode(responseBytes)
    return String(responseBytes, Charset.forName(charsetName))
}
```

***

## 三、请求流程图

### 完整请求流程

```
用户操作（搜索/打开书籍/获取正文）
    ↓
WebBook.getXXX() 创建 AnalyzeUrl
    ↓
AnalyzeUrl.initUrl()
    ├─ 执行@js/<js>代码
    ├─ 替换{{page}}、{{key}}等变量
    └─ 解析URL参数（method、headers、body等）
    ↓
AnalyzeUrl.getStrResponseAwait()
    ├─ ConcurrentRateLimiter.withLimit() ← 并发控制
    │   └─ 等待或立即执行
    ├─ setCookie() ← 加载Cookie
    └─ 判断请求方式
        ├─ WebView请求
        │   └─ BackstageWebView.getStrResponse()
        │       ├─ 加载网页
        │       ├─ 执行webJs
        │       └─ 返回结果
        └─ OkHttp请求
            └─ getClient().newCallStrResponse()
                ↓
            拦截器链处理
                ├─ OkHttpExceptionInterceptor
                ├─ 默认请求头拦截器
                ├─ CookieJar拦截器
                ├─ DecompressInterceptor
                └─ UrlRecordInterceptor
                ↓
            发送到服务器
                ↓
            接收响应
                ├─ 解压（gzip/deflate）
                ├─ 保存Cookie
                └─ 编码检测
                ↓
            返回StrResponse
    ↓
WebBook处理响应
    ├─ 解析规则提取数据
    └─ 返回给调用方
```

***

## 四、配置项汇总

### AppConfig相关配置

| 配置项            | 说明           | 默认值       |
| -------------- | ------------ | --------- |
| `userAgent`    | 默认User-Agent | Chrome UA |
| `unsafeSsl`    | 是否信任所有证书     | false     |
| `addressCache` | DNS缓存映射      | 空         |
| `isCronet`     | 是否使用Cronet   | false     |
| `recordUrl`    | 是否记录URL访问    | false     |

### 书源配置

| 字段                 | 说明          |
| ------------------ | ----------- |
| `header`           | 默认请求头       |
| `concurrentRate`   | 并发率限制       |
| `enabledCookieJar` | 启用CookieJar |
| `loginUrl`         | 登录URL       |
| `loginCheckJs`     | 登录检测JS      |

### URL参数配置

| 参数        | 说明           |
| --------- | ------------ |
| `method`  | 请求方法         |
| `headers` | 请求头          |
| `body`    | 请求体          |
| `charset` | 字符编码         |
| `retry`   | 重试次数         |
| `webView` | 使用WebView    |
| `webJs`   | WebView执行的JS |
| `js`      | URL解析后执行的JS  |
| `bodyJs`  | 响应处理JS       |
| `dnsIp`   | 强制DNS IP     |

***

## 五、常见问题

### Q1: 为什么有些网站访问失败？

可能原因：

1. **SSL证书问题** - 开启`unsafeSsl`选项
2. **User-Agent被拦截** - 在书源中设置自定义UA
3. **需要登录** - 配置登录URL或手动登录
4. **并发限制** - 网站限流，调整`concurrentRate`
5. **代理问题** - 检查代理配置是否正确

### Q2: Cookie不生效怎么办？

1. 确认书源启用了`enabledCookieJar`
2. 检查Cookie域名是否匹配
3. 尝试手动设置Cookie：`cookie.setCookie(url, cookieStr)`
4. 清除旧Cookie重新获取：`cookie.removeCookie(url)`

### Q3: 如何调试网络请求？

1. 开启URL记录：`AppConfig.recordUrl = true`
2. 查看调试日志中的网络事件
3. 使用书源调试功能
4. 检查`UrlRecord`表中的请求记录

### Q4: WebView请求和OkHttp请求的区别？

| 特性   | OkHttp  | WebView   |
| ---- | ------- | --------- |
| 执行环境 | 后台线程    | WebView实例 |
| JS执行 | Rhino引擎 | 浏览器引擎     |
| 适用场景 | 普通API   | 需要执行网页JS  |
| 性能   | 快       | 较慢        |
| 功能   | 基础HTTP  | 支持复杂网页交互  |

***

## 六、核心代码文件索引

| 文件                                                                                                                                        | 说明                  |
| ----------------------------------------------------------------------------------------------------------------------------------------- | ------------------- |
| [HttpHelper.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/HttpHelper.kt)                       | OkHttpClient配置、代理设置 |
| [AnalyzeUrl.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt)               | URL解析、请求构建          |
| [CookieManager.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/CookieManager.kt)                 | Cookie管理            |
| [CookieStore.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/CookieStore.kt)                     | Cookie存储            |
| [ConcurrentRateLimiter.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/ConcurrentRateLimiter.kt)      | 并发控制                |
| [SSLHelper.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/SSLHelper.kt)                         | SSL/TLS配置           |
| [StrResponse.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/StrResponse.kt)                     | 响应封装                |
| [OkHttpUtils.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/OkHttpUtils.kt)                     | OkHttp扩展函数          |
| [DecompressInterceptor.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/DecompressInterceptor.kt) | 解压拦截器               |
| [UrlRecordInterceptor.kt](file:///g:/Project/legado_Plus/legado_Plus/app/src/main/java/io/legado/app/help/http/UrlRecordInterceptor.kt)   | URL记录拦截器            |

