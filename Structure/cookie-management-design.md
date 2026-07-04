# Cookie 管理设计文档

## 一、现状概述

当前 Cookie 管理横跨 5 个子系统：OkHttp、Cronet、WebView、Rhino JS、NanoHTTPD。核心存储走 Room DB（`CookieStore`），会话 Cookie 走内存（`CacheManager`）。通过一个自定义哨兵 Header `"CookieJar"` 实现按请求粒度的 Cookie 处理开关。

### 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                      请求发起方                               │
│  AnalyzeUrl / JsExtensions / WebViewActivity / ...          │
│  → 添加 cookieJarHeader 哨兵 Header                          │
└──────────────┬──────────────────────────────────────────────┘
               │
    ┌──────────▼──────────┐
    │   OkHttp 拦截器      │  HttpHelper.kt network interceptor
    │   Cronet 拦截器      │  CronetInterceptor.kt
    │   ObsoleteUrlFactory │  HttpURLConnection 桥接
    └──────────┬──────────┘
               │ 检测到 cookieJarHeader
    ┌──────────▼──────────┐
    │  CookieManager       │  help/http/CookieManager.kt
    │  (HTTP 层协调器)      │  loadRequest / saveResponse
    └──────────┬──────────┘
               │
    ┌──────────▼──────────┐     ┌──────────────────────┐
    │   CookieStore        │◄───►│  android.webkit.      │
    │  (持久化存储)         │     │  CookieManager        │
    │  Room DB + 内存缓存   │     │  (WebView Cookie)     │
    └─────────────────────┘     └──────────────────────┘
```

### 关键组件

| 组件 | 文件 | 职责 |
|------|------|------|
| CookieStore | `help/http/CookieStore.kt` | 持久化存储（Room DB）+ 内存缓存 |
| CookieManager | `help/http/CookieManager.kt` | HTTP 层协调：解析 Set-Cookie、注入 Cookie |
| HttpHelper cookieJar | `help/http/HttpHelper.kt` | OkHttp CookieJar 实现（仅内存暂存） |
| Cronet 拦截器 | `lib/cronet/` | Cronet 引擎的 Cookie 注入/保存 |
| AnalyzeUrl | `model/analyzeRule/AnalyzeUrl.kt` | 请求构建器，添加哨兵 Header |
| BaseSource | `data/entities/BaseSource.kt` | 书源/订阅源的 JS Cookie 接口 |

---

## 二、存在的问题

### 2.1 死代码：`AnalyzeUrl.saveCookie()` 永远不会被调用

`AnalyzeUrl.saveCookie()`（line 766）设计用于将 `cookieJar` 内存缓存持久化到 DB，但**当前没有任何调用方**。`HttpHelper.cookieJar.saveFromResponse` 只是存到内存（`CacheManager.putMemory("_cookieJar", ...)`），这些 Cookie 永远不会被持久化。

**实际持久化路径**：`CookieManager.saveResponse()` → `CookieStore.replaceCookie()`，完全绕过了 `cookieJar`。

**影响**：代码中有注释掉的 `saveCookie()` 调用（line 742-748），说明曾经是工作的，但被重构后变成了死代码。清理掉可降低维护困惑。

### 2.2 Cookie 长度限制的随机淘汰策略

`CookieStore.getCookie()` 对 Cookie 字符串做了 4096 字符截断（line 78-83）：

```kotlin
while (cookie.length > 4096) {
    val map = cookieToMap(cookie)
    val key = map.keys.toTypedArray().random()  // 随机删除
    map.remove(key)
    cookie = mapToCookie(map)
}
```

**问题**：
- 随机删除可能导致关键 Cookie（如 session token）被移除
- 4096 限制来源于 HTTP 规范的保守估计，但现代服务器和客户端普遍支持更大的 Cookie
- 没有日志记录哪些 Cookie 被淘汰了，排障困难

### 2.3 WebView 与 OkHttp 的 Cookie 同步是手动且不完整的

同步发生在特定生命周期事件中：

- **WebView → CookieStore**：`onPageFinished` 时读取 WebView Cookie 写入 DB
- **CookieStore → WebView**：`applyToWebView()` 在页面加载前同步

**问题**：
- JS 动态设置的 Cookie（不触发页面导航）不会被捕获
- `onPageStarted` 和 `onPageFinished` 之间 WebView 设置的 Cookie 可能丢失
- 多个 WebView 实例（BackstageWebView、BottomWebViewDialog、WebViewActivity）各自独立同步，可能存在竞态

### 2.4 会话 Cookie 的存储不一致

会话 Cookie（没有 `Expires`/`Max-Age` 的 Set-Cookie）存储在内存 `CacheManager` 中，key 为 `<domain>_session_cookie`。

**问题**：
- 应用重启后丢失，但用户可能期望会话 Cookie 在进程存活期间一直有效
- `CookieManager.saveResponse()` 的持久/会话判断逻辑：没有 `Expires` 且没有 `Max-Age` 的 Cookie 被认为是会话 Cookie。但 RFC 6265 规定 `Max-Age=0` 应该立即过期，当前代码可能误判
- `removeCookie()` 清除会话和持久 Cookie，但 `removeCookie(url, key)` 只清除指定 key 的会话 Cookie，行为不对称

### 2.5 哨兵 Header 的脆弱性

使用 `"CookieJar"` 作为哨兵 Header 来 opt-in Cookie 处理。

**问题**：
- 如果某个书源恰好设置了一个名为 `CookieJar` 的 Header，会意外触发或干扰 Cookie 处理
- Cronet 和 OkHttp 两条路径都需要独立检查这个 Header，容易遗漏
- `ObsoleteUrlFactory` 也有独立的检查逻辑，维护三处容易不一致

### 2.6 Cronet 回调中的 Cookie 处理分散

- `AbsCallBack.onResponseStarted`：保存响应 Cookie
- `AbsCallBack.onRedirectReceived`：保存重定向 Cookie
- `AbsCallBack.onCanceled`：加载重定向目标的 Cookie
- `CronetInterceptor`：请求前注入 Cookie

这些逻辑分散在 4 个位置，与 OkHttp 拦截器中的逻辑重复。

### 2.7 按域名（subdomain）而非按 URL 存储

`CookieStore.setCookie()` 提取子域名作为 key：

```kotlin
val url = NetworkUtils.getSubDomain(url)
```

**问题**：
- `a.example.com` 和 `b.example.com` 的 Cookie 存在不同记录中，但 `example.com` 和 `www.example.com` 可能共享（取决于 `getSubDomain` 实现）
- 不支持 path 限制的 Cookie（RFC 6265 要求 path 匹配）
- 同一域名下不同书源的 Cookie 会互相污染

### 2.8 CronetCoroutineInterceptor 与主 Cookie 路径不一致

`CronetCoroutineInterceptor` 使用 `cookieJar.loadForRequest()` / `cookieJar.receiveHeaders()`，走的是 OkHttp 原生 CookieJar 接口。但主 Cookie 路径（`CronetInterceptor` + `AbsCallBack`）使用的是 `CookieManager.loadRequest()` / `saveResponse()`。

两条路径的行为不一致：`cookieJar.loadForRequest()` 返回 `emptyList()`，而 `CookieManager.loadRequest()` 会真正加载 Cookie。

---

## 三、设计方案

### 3.1 清理死代码

**改动**：删除 `AnalyzeUrl.saveCookie()` 及相关注释掉的代码块（lines 739-777 中的死代码部分）。`HttpHelper.cookieJar.saveFromResponse` 中的内存暂存如果确实没有消费方，也应清理。

**理由**：减少维护负担，避免新人误解 Cookie 持久化路径。

### 3.2 改进 Cookie 长度管理

**方案**：替换随机淘汰为 LRU（最近最少使用）淘汰 + 日志记录。

```kotlin
fun getCookie(url: String): String {
    val domain = NetworkUtils.getSubDomain(url)
    val persistent = appDb.cookieDao.get(domain)?.cookie ?: ""
    val session = CacheManager.getMemory<String>("${domain}_session_cookie") ?: ""
    var cookie = mergeCookie(persistent, session)

    if (cookie.length > 4096) {
        // 按 setCookie 的时间戳排序，淘汰最早设置的 key
        val map = cookieToMap(cookie)
        val sorted = map.entries.sortedBy { getLastSetTime(domain, it.key) }
        val trimmed = mutableMapOf<String, String>()
        var len = 0
        for ((k, v) in sorted) {
            val entryLen = k.length + v.length + 3 // "k=v; "
            if (len + entryLen > 4096) {
                AppLog.put("Cookie 超长，淘汰 $domain/$k")
                break
            }
            trimmed[k] = v
            len += entryLen
        }
        cookie = mapToCookie(trimmed)
    }
    return cookie
}
```

**简化方案**（如果不想增加复杂度）：至少将随机淘汰改为淘汰最早插入的 key，并加日志。

### 3.3 统一 WebView Cookie 同步

**方案**：引入 `WebViewCookieBridge` 单一入口，替代各处分散的同步代码。

```kotlin
object WebViewCookieBridge {
    /**
     * 页面加载前调用：CookieStore → WebView
     */
    fun syncToWebView(url: String) {
        CookieManager.applyToWebView(url)
    }

    /**
     * 页面加载完成后调用：WebView → CookieStore
     * @param sourceKey 书源/订阅源 key，用于隔离不同源的 Cookie
     */
    fun captureFromWebView(url: String, sourceKey: String? = null) {
        val cookie = android.webkit.CookieManager.getInstance().getCookie(url) ?: return
        if (sourceKey != null) {
            CookieStore.setCookie(sourceKey, cookie)
        } else {
            CookieStore.setCookie(url, cookie)
        }
    }
}
```

各 WebView 容器（WebViewActivity、BackstageWebView、BottomWebViewDialog 等）统一调用此入口，不再各自实现同步逻辑。

### 3.4 Cookie 域名隔离

**问题**：当前同域名下不同书源共享 Cookie，可能导致冲突。

**方案**：Cookie 存储 key 改为 `sourceKey + "|" + domain` 的组合，每个书源有独立的 Cookie 命名空间。

```
当前:  url = "example.com"  cookie = "k1=v1; k2=v2"
改进:  url = "mySourceKey|example.com"  cookie = "k1=v1; k2=v2"
```

`getCookie` 时优先查找 `sourceKey|domain`，找不到再 fallback 到 `domain`（兼容旧数据）。

**注意**：这是一个破坏性改动，需要数据库迁移（cookies 表的 url 列含义变化）。建议作为可选行为，通过 `enabledCookieJar` 的升级版 `cookieMode` 控制：

```kotlin
enum class CookieMode {
    SHARED,     // 同域名共享（当前行为，兼容）
    ISOLATED,   // 按源隔离（新行为）
    DISABLED    // 不处理 Cookie
}
```

### 3.5 统一哨兵 Header 处理

**方案**：将哨兵 Header 检查收拢到一个位置。

当前有三处独立检查：
1. `HttpHelper` network interceptor
2. `CronetInterceptor`
3. `ObsoleteUrlFactory`

可以改为在 `AnalyzeUrl.setCookie()` 中统一处理：不再添加哨兵 Header，而是在请求构建时直接调用 `CookieManager.loadRequest()` 注入 Cookie，响应时由统一的响应拦截器保存。

这样 Cronet 和 OkHttp 两条路径都可以删除 Cookie 相关的拦截逻辑。

### 3.6 Cronet Cookie 路径统一

**方案**：删除 `CronetCoroutineInterceptor` 中的独立 Cookie 处理（lines 38-44, 52-57），统一使用 `AbsCallBack` 中的 `CookieManager` 调用。或者反过来，让 `CronetCoroutineInterceptor` 也使用 `CookieManager` 而不是 OkHttp 原生 `CookieJar`。

### 3.7 清理 Cronet.kt 中未使用的 `getCookie`

`CronetInterceptor` 中的 `getCookie()` 方法（line 89）从 OkHttp `cookieJar` 加载 Cookie，但从未被调用。应删除。

---

## 四、优先级排序

| 优先级 | 改动 | 风险 | 收益 |
|--------|------|------|------|
| P0 | 清理死代码（saveCookie、getCookie） | 低 | 消除维护困惑 |
| P0 | Cookie 长度管理改进（日志 + LRU） | 低 | 排障能力提升，避免关键 Cookie 被误删 |
| P1 | WebView Cookie 同一入口 | 中 | 消除分散逻辑，减少遗漏和竞态 |
| P1 | Cronet Cookie 路径统一 | 中 | 消除不一致行为 |
| P2 | Cookie 域名隔离 | 高 | 解决多源 Cookie 互污染 |
| P2 | 哨兵 Header 统一 | 中 | 降低维护成本 |

---

## 五、不建议改动的部分

1. **NanoHTTPD 不加 Cookie**：内置 HTTP 服务器用 token 认证即可，加 Cookie 增加复杂度但无实际收益。
2. **不引入第三方 Cookie 库**：当前 Cookie 处理逻辑与书源规则引擎深度绑定，引入第三方库适配成本高。
3. **不做完整的 RFC 6265 Cookie 解析**：书源场景下 Cookie 格式五花八门，严格解析反而会导致兼容性问题。当前的 `key=value;` 简单解析对于阅读器场景够用。
