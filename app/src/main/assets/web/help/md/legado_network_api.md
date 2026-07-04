# Legado 书源规则 - 网络访问 API 参考

本文档详细介绍 Legado 阅读软件中可供书源规则使用的网络访问 API，包括各 API 的区别、返回格式和使用示例。

---

## 目录

1. [API 总览](#1-api-总览)
2. [基础网络请求 API](#2-基础网络请求-api)
3. [WebView 相关 API](#3-webview-相关-api)
4. [并发请求 API](#4-并发请求-api)
5. [浏览器交互 API](#5-浏览器交互-api)
6. [文件下载 API](#6-文件下载-api)
7. [Cookie 操作 API](#7-cookie-操作-api)
8. [返回对象 StrResponse 详解](#8-返回对象-strresponse-详解)
9. [使用示例](#9-使用示例)
10. [最佳实践](#10-最佳实践)

---

## 1. API 总览

| API 名称 | 返回类型 | 主要用途 | 是否异步 |
|----------|----------|----------|----------|
| `ajax(url)` | String | 快速获取网页内容 | 否 |
| `connect(url)` | StrResponse | 获取完整响应（含状态码、头信息） | 否 |
| `get(url, header)` | StrResponse | GET 请求，可自定义请求头 | 否 |
| `post(url, body, header)` | StrResponse | POST 请求 | 否 |
| `head(url, header)` | StrResponse | HEAD 请求，仅获取头信息 | 否 |
| `webView(...)` | String | 使用 WebView 渲染页面 | 是 |
| `ajaxAll(urls)` | StrResponse[] | 并发请求多个 URL | 是 |
| `downloadFile(url)` | String | 下载文件到本地 | 是 |

---

## 2. 基础网络请求 API

### 2.1 ajax - 快速网络请求

**最常用的网络请求方法**，直接返回网页内容字符串。

```javascript
// 基础用法
var content = java.ajax("https://example.com/api/data");

// 带超时参数（毫秒）
var content = java.ajax("https://example.com/api/data", 10000);

// 支持带参数的 URL
var content = java.ajax("https://example.com/api/data?k=keyword");
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| url | String/Array | 是 | 请求地址，支持带 JSON 参数 |
| callTimeout | Long | 否 | 超时时间（毫秒），默认使用书源配置 |

**返回值**: `String` - 网页内容（纯文本/HTML/JSON）

**特点**:
- 自动继承书源的请求头、Cookie
- 自动处理重定向
- 失败时返回错误堆栈字符串

---

### 2.2 connect - 获取完整响应

获取包含状态码、响应头等完整信息的响应对象。

```javascript
// 基础用法
var response = java.connect("https://example.com/api/data");
var body = response.body();           // 响应内容
var code = response.code();           // 状态码
var headers = response.headers();     // 响应头

// 带自定义请求头
var header = JSON.stringify({
    "User-Agent": "Mozilla/5.0...",
    "Referer": "https://example.com"
});
var response = java.connect("https://example.com/api/data", header);

// 带超时
var response = java.connect("https://example.com/api/data", header, 10000);
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| urlStr | String | 是 | 请求地址 |
| header | String | 否 | JSON 格式的请求头 |
| callTimeout | Long | 否 | 超时时间（毫秒） |

**返回值**: `StrResponse` 对象（详见第8节）

---

### 2.3 get - GET 请求

显式的 GET 请求方法。

```javascript
var header = JSON.stringify({"Referer": "https://example.com"});
var response = java.get("https://example.com/api/data", header, 10000);

var body = response.body();
var code = response.code();
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| urlStr | String | 是 | 请求地址 |
| header | String | 是 | JSON 格式的请求头 |
| timeout | Int | 否 | 超时时间（毫秒） |

**返回值**: `StrResponse.body()` - 即响应内容字符串

---

### 2.4 post - POST 请求

发送 POST 请求，用于提交表单或 JSON 数据。

```javascript
// POST JSON 数据
var body = JSON.stringify({"username": "test", "password": "123456"});
var header = JSON.stringify({"Content-Type": "application/json"});
var response = java.post("https://example.com/api/login", body, header, 10000);

// POST 表单数据
var formData = "username=test&password=123456";
var response = java.post("https://example.com/api/login", formData, null, 10000);
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| urlStr | String | 是 | 请求地址 |
| body | String | 是 | 请求体内容 |
| header | String | 是 | JSON 格式的请求头 |
| timeout | Int | 否 | 超时时间（毫秒） |

**返回值**: `StrResponse.body()` - 响应内容字符串

---

### 2.5 head - HEAD 请求

仅获取响应头，不下载响应体。常用于检测资源是否存在、获取文件大小等。

```javascript
var header = JSON.stringify({"Referer": "https://example.com"});
var response = java.head("https://example.com/file.zip", header, 10000);

// 获取响应头信息
var headers = response.headers();
var contentLength = headers.get("Content-Length");
var lastModified = headers.get("Last-Modified");
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| urlStr | String | 是 | 请求地址 |
| header | String | 是 | JSON 格式的请求头 |
| timeout | Int | 否 | 超时时间（毫秒） |

**返回值**: `StrResponse` 对象（body 为空，但 headers 有值）

---

## 3. WebView 相关 API

用于处理需要 JavaScript 渲染的页面。

### 3.1 webView - WebView 渲染

```javascript
// 基础用法 - 访问 URL 并执行 JS
var content = java.webView(null, "https://example.com", "document.body.innerHTML");

// 加载 HTML 并执行 JS
var html = "<html><body><div id='content'>动态内容</div></body></html>";
var content = java.webView(html, null, "document.getElementById('content').innerText");

// 使用缓存加速
var content = java.webView(null, "https://example.com", "document.body.innerHTML", true);
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| html | String | 否 | 直接加载的 HTML 内容 |
| url | String | 否 | 要访问的 URL（html 为空时必填） |
| js | String | 否 | 页面加载后执行的 JS，返回结果 |
| cacheFirst | Boolean | 否 | 优先使用缓存，默认 false |

**返回值**: `String` - JS 执行结果或整个页面 HTML

---

### 3.2 webViewGetSource - 获取资源 URL

监听页面加载，获取匹配正则的资源 URL。

```javascript
// 获取视频/音频资源链接
var sourceUrl = java.webViewGetSource(
    null,                                    // html
    "https://example.com/video/123",         // url
    null,                                    // js
    "https?://.*\\.mp4",                     // sourceRegex - 匹配视频链接
    false,                                   // cacheFirst
    1000                                     // delayTime - 延迟时间（毫秒）
);
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| html | String | 否 | 直接加载的 HTML |
| url | String | 否 | 访问的 URL |
| js | String | 否 | 执行的 JS |
| sourceRegex | String | 是 | 资源 URL 匹配正则 |
| cacheFirst | Boolean | 否 | 优先使用缓存 |
| delayTime | Long | 否 | 额外等待时间（毫秒） |

**返回值**: `String` - 匹配到的资源 URL

---

### 3.3 webViewGetOverrideUrl - 获取跳转 URL

监听页面跳转，获取匹配正则的跳转地址。

```javascript
// 获取跳转后的真实地址
var realUrl = java.webViewGetOverrideUrl(
    null,
    "https://example.com/redirect",
    null,
    "https?://example\\.com/play.*",         // overrideUrlRegex
    false,
    1000
);
```

**返回值**: `String` - 匹配到的跳转 URL

---

## 4. 并发请求 API

### 4.1 ajaxAll - 并发请求多个 URL

同时请求多个地址，提高效率。

```javascript
var urls = [
    "https://api.example.com/chapter/1",
    "https://api.example.com/chapter/2",
    "https://api.example.com/chapter/3"
];

// 基础用法
var responses = java.ajaxAll(urls);

// 跳过速率限制
var responses = java.ajaxAll(urls, true);

// 处理结果
for (var i = 0; i < responses.length; i++) {
    var body = responses[i].body();
    var code = responses[i].code();
}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| urlList | String[] | 是 | URL 数组 |
| skipRateLimit | Boolean | 否 | 是否跳过速率限制，默认 false |

**返回值**: `StrResponse[]` - 响应对象数组

---

### 4.2 ajaxTestAll - 并发测试网络

用于测试多个 URL 的响应情况，常用于源有效性检测。

```javascript
var urls = [
    "https://mirror1.example.com/book",
    "https://mirror2.example.com/book"
];

var responses = java.ajaxTestAll(urls, 5000);  // 5秒超时

// 找出最快的响应
for (var i = 0; i < responses.length; i++) {
    if (responses[i].code() == 200) {
        var callTime = responses[i].callTime();  // 响应时间
        // 处理...
    }
}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| urlList | String[] | 是 | URL 数组 |
| timeout | Int | 是 | 超时时间（毫秒） |
| skipRateLimit | Boolean | 否 | 是否跳过速率限制 |

**返回值**: `StrResponse[]` - 响应对象数组（包含响应时间）

---

## 5. 浏览器交互 API

### 5.1 startBrowser - 打开内置浏览器

用于手动处理验证码、登录等需要人工干预的情况。

```javascript
// 打开浏览器让用户操作
java.startBrowser("https://example.com/login", "登录验证");

// 带初始 HTML
java.startBrowser("https://example.com", "验证页面", "<html>...</html>");
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| url | String | 是 | 要打开的链接 |
| title | String | 是 | 浏览器页面标题 |
| html | String | 否 | 初始 HTML 内容 |

**返回值**: 无（打开浏览器界面）

---

### 5.2 startBrowserAwait - 等待浏览器结果

打开浏览器并等待用户操作完成后获取结果。

```javascript
var response = java.startBrowserAwait(
    "https://example.com/verify",     // url
    "请完成验证",                     // title
    true,                             // refetchAfterSuccess - 成功后重新获取
    null                              // html
);

var body = response.body();  // 用户操作完成后的页面内容
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| url | String | 是 | 要打开的链接 |
| title | String | 是 | 浏览器页面标题 |
| refetchAfterSuccess | Boolean | 否 | 成功后是否重新获取 |
| html | String | 否 | 初始 HTML 内容 |

**返回值**: `StrResponse` - 用户操作完成后的响应

---

### 5.3 getVerificationCode - 获取验证码

打开图片验证码对话框，等待用户输入。

```javascript
var code = java.getVerificationCode("https://example.com/captcha.jpg");
// 返回用户输入的验证码
```

**返回值**: `String` - 用户输入的验证码

---

## 6. 文件下载 API

### 6.1 downloadFile - 下载文件

将网络文件下载到本地缓存目录。

```javascript
// 基础用法
var localPath = java.downloadFile("https://example.com/file.zip");

// 带类型参数
var localPath = java.downloadFile("https://example.com/data, {\"type\":\"json\"}");

// 读取下载的文件
var content = java.readTxtFile(localPath);
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| url | String | 是 | 下载地址，可带 type 参数 |

**返回值**: `String` - 文件的相对路径（相对于缓存目录）

---

### 6.2 cacheFile - 缓存文件

下载并缓存文件，支持设置缓存时间。

```javascript
// 基础用法
var content = java.cacheFile("https://example.com/script.js");

// 带缓存时间（秒）
var content = java.cacheFile("https://example.com/script.js", 3600);  // 缓存1小时
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| urlStr | String | 是 | 文件链接 |
| saveTime | Int | 否 | 缓存时间（秒），0 表示永久 |

**返回值**: `String` - 文件内容

---

## 7. Cookie 操作 API

### 7.1 getCookie - 获取 Cookie

```javascript
// 获取指定域名的所有 Cookie
var allCookies = java.getCookie("example.com");

// 获取指定域名的特定 Cookie 键值
var sessionId = java.getCookie("example.com", "session_id");
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| tag | String | 是 | 域名/标识 |
| key | String | 否 | Cookie 键名 |

**返回值**: `String` - Cookie 值

---

## 8. 返回对象 StrResponse 详解

`connect`、`ajaxAll` 等方法返回 `StrResponse` 对象，包含以下属性和方法：

### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| body | String | 响应内容 |
| raw | Response | 原始 OkHttp Response 对象 |
| callTime | Int | 请求耗时（毫秒） |

### 方法

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `url()` | String | 最终请求 URL（含重定向） |
| `body()` | String | 响应内容 |
| `code()` | Int | HTTP 状态码 |
| `message()` | String | HTTP 状态消息 |
| `headers()` | Headers | 响应头对象 |
| `isSuccessful()` | Boolean | 是否成功（code 200-299） |
| `callTime()` | Int | 请求耗时 |

### 使用示例

```javascript
var response = java.connect("https://example.com/api/data");

// 获取基本信息
var code = response.code();           // 200
var body = response.body();           // 响应内容
var url = response.url();             // 最终 URL
var time = response.callTime();       // 耗时（毫秒）

// 获取响应头
var headers = response.headers();
var contentType = headers.get("Content-Type");
var contentLength = headers.get("Content-Length");

// 判断是否成功
if (response.isSuccessful()) {
    // 处理成功响应
} else {
    // 处理错误
}
```

---

## 9. 使用示例

### 9.1 简单 API 调用

```javascript
// 获取 JSON API 数据
var json = java.ajax("https://api.example.com/books");
var data = JSON.parse(json);

// 遍历数据
for (var i = 0; i < data.books.length; i++) {
    var book = data.books[i];
    // 处理每本书...
}
```

### 9.2 带请求头的请求

```javascript
var header = JSON.stringify({
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://example.com",
    "Accept": "application/json"
});

var response = java.connect("https://api.example.com/data", header);

if (response.code() == 200) {
    var body = response.body();
    // 处理数据...
}
```

### 9.3 POST 登录示例

```javascript
// 构造登录请求
var loginBody = JSON.stringify({
    "username": "user123",
    "password": "pass123"
});

var header = JSON.stringify({
    "Content-Type": "application/json",
    "Accept": "application/json"
});

var response = java.post("https://api.example.com/login", loginBody, header, 10000);

if (response.code() == 200) {
    var result = JSON.parse(response.body());
    var token = result.token;
    // 保存 token 供后续使用...
}
```

### 9.4 WebView 获取动态内容

```javascript
// 获取需要 JS 渲染的内容
var content = java.webView(
    null, 
    "https://example.com/dynamic-page",
    "document.querySelector('.content').innerText"
);

// 获取视频播放链接
var videoUrl = java.webViewGetSource(
    null,
    "https://example.com/video/123",
    null,
    "https?://.*\\.mp4",
    true,    // 使用缓存
    2000     // 等待2秒
);
```

### 9.5 并发获取多章节

```javascript
// 构造章节 URL 数组
var urls = [];
for (var i = 1; i <= 10; i++) {
    urls.push("https://api.example.com/chapter/" + i);
}

// 并发请求
var responses = java.ajaxAll(urls);

// 合并内容
var allContent = "";
for (var i = 0; i < responses.length; i++) {
    if (responses[i].code() == 200) {
        allContent += responses[i].body() + "\n";
    }
}
```

### 9.6 处理需要验证的请求

```javascript
// 尝试请求
var response = java.connect("https://example.com/protected/data");

if (response.code() == 403) {
    // 需要验证，打开浏览器让用户操作
    var verifyResponse = java.startBrowserAwait(
        "https://example.com/verify",
        "请完成验证",
        true    // 验证成功后重新获取
    );
    
    if (verifyResponse.code() == 200) {
        var data = verifyResponse.body();
        // 处理数据...
    }
}
```

---

## 10. 最佳实践

### 10.1 API 选择建议

| 场景 | 推荐 API | 原因 |
|------|----------|------|
| 简单获取内容 | `ajax` | 简洁高效 |
| 需要状态码/响应头 | `connect` | 信息完整 |
| 提交表单/JSON | `post` | 语义明确 |
| 仅需响应头 | `head` | 节省带宽 |
| JS 渲染页面 | `webView` | 支持动态内容 |
| 批量请求 | `ajaxAll` | 并发高效 |
| 需要人工验证 | `startBrowserAwait` | 用户交互 |

### 10.2 错误处理

```javascript
var response = java.connect("https://example.com/api/data");

switch (response.code()) {
    case 200:
        // 成功
        var data = response.body();
        break;
    case 404:
        // 未找到
        throw "资源不存在";
    case 403:
        // 无权限
        throw "需要登录或验证";
    case 500:
        // 服务器错误
        throw "服务器内部错误";
    default:
        throw "请求失败: " + response.code();
}
```

### 10.3 性能优化

```javascript
// 1. 使用缓存
var content = java.webView(null, url, js, true);  // cacheFirst = true

// 2. 并发请求
var responses = java.ajaxAll(urls, true);  // skipRateLimit = true

// 3. 设置合理超时
var response = java.connect(url, header, 5000);  // 5秒超时

// 4. 缓存文件
var script = java.cacheFile("https://example.com/lib.js", 86400);  // 缓存1天
```

### 10.4 URL 参数格式

Legado 支持 URL 后附加 JSON 参数：

```javascript
// 带参数的 URL
var url = "https://example.com/api/data, " + JSON.stringify({
    "method": "POST",
    "body": "{\"key\":\"value\"}",
    "headers": {"Content-Type": "application/json"},
    "webView": true,
    "webJs": "document.body.innerText",
    "charset": "utf-8",
    "retry": 3
});

var content = java.ajax(url);
```

| 参数 | 类型 | 说明 |
|------|------|------|
| method | String | 请求方法 (GET/POST/HEAD) |
| body | String | 请求体 |
| headers | Object | 请求头 |
| webView | Boolean | 是否使用 WebView |
| webJs | String | WebView 执行的 JS |
| charset | String | 字符编码 |
| retry | Int | 重试次数 |
| type | String | 返回数据类型 |
| dnsIp | String | 自定义 DNS IP |

---

## 11. 常见问题

### Q: ajax 和 connect 的区别？

| 特性 | ajax | connect |
|------|------|---------|
| 返回值 | String | StrResponse |
| 状态码 | 无法获取 | 可获取 |
| 响应头 | 无法获取 | 可获取 |
| 使用场景 | 简单获取内容 | 需要完整响应信息 |

### Q: 什么时候用 webView？

- 页面内容需要 JavaScript 渲染
- 需要执行页面中的 JS 获取数据
- 需要等待 AJAX 请求完成
- 目标网站有反爬机制需要完整浏览器环境

### Q: 如何处理跨域问题？

Legado 的网络请求不存在浏览器跨域限制，可以直接请求任何 URL。

### Q: 如何保持登录状态？

1. 书源启用 CookieJar：`enabledCookieJar: true`
2. 使用 `getCookie()` 获取保存的 Cookie
3. 在请求头中携带 Cookie

---

**提示**：合理选择 API 可以大大提高书源的性能和稳定性。对于简单场景优先使用 `ajax`，复杂场景再考虑 `connect` 或 `webView`。
