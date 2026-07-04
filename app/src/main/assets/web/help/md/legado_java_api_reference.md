# Legado 书源规则 - 可用的 Java / Android / 第三方库 API 参考

本文档整理了 Legado 阅读软件书源规则中可调用的 Java 包、Android 包和第三方库，以及官方文档地址。

*注意：* 以下 API 想使用都要在前面加Packages，例如 `Packages.java.util.Collections`。
---

## 目录

1. [Java 标准包](#1-java-标准包)
2. [Android 包](#2-android-包)
3. [第三方库](#3-第三方库)
4. [调用方式说明](#4-调用方式说明)
5. [注意事项与限制](#5-注意事项与限制)
6. [实用代码示例](#6-实用代码示例)

---

## 1. Java 标准包

> Legado 使用 Rhino JS 引擎，支持直接调用 Java 标准库。

### 1.1 java.net — 网络编程

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `InetAddress` | `getByName()` / `getAllByName()` / `getLocalHost()` | DNS 解析、获取 IP |
| `Inet4Address` | `isLoopbackAddress()` | 判断 IPv4 |
| `Inet6Address` | — | 判断 IPv6 |
| `NetworkInterface` | `getNetworkInterfaces()` / `getHardwareAddress()` | 获取网卡信息、MAC 地址 |
| `URL` | `new URL()` / `openStream()` | URL 解析 |
| `URI` | `new URI()` | URI 解析 |
| `HttpURLConnection` | `connect()` / `getResponseCode()` | HTTP 连接 |
| `Socket` | `new Socket()` | TCP Socket |
| `ServerSocket` | `new ServerSocket()` | TCP 服务端 |

**官方文档**: [Java SE 8 - java.net](https://docs.oracle.com/javase/8/docs/api/java/net/package-summary.html)

### 1.2 java.io — 文件与流

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `File` | `exists()` / `readText()` / `writeText()` | 文件操作 |
| `FileInputStream` | `read()` | 文件读取流 |
| `FileOutputStream` | `write()` | 文件写入流 |
| `BufferedReader` | `readLine()` | 按行读取 |
| `BufferedWriter` | `write()` / `flush()` | 按行写入 |
| `ByteArrayInputStream` | — | 字节数组输入流 |
| `ByteArrayOutputStream` | — | 字节数组输出流 |
| `InputStreamReader` | — | 字符流转换 |
| `PrintWriter` | `println()` | 格式化输出 |

**官方文档**: [Java SE 8 - java.io](https://docs.oracle.com/javase/8/docs/api/java/io/package-summary.html)

### 1.3 java.util — 工具与集合

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `HashMap` / `LinkedHashMap` | `put()` / `get()` / `keySet()` | 键值对存储 |
| `ArrayList` / `LinkedList` | `add()` / `get()` / `size()` | 列表存储 |
| `HashSet` | `add()` / `contains()` | 去重集合 |
| `Properties` | `load()` / `getProperty()` | 配置文件读取 |
| `Date` | — | 日期 |
| `Calendar` | `getInstance()` / `get()` | 日历操作 |
| `TimeZone` | `getTimeZone()` / `getID()` | 时区 |
| `UUID` | `randomUUID()` | 生成 UUID |
| `Base64` | `getEncoder()` / `getDecoder()` | Base64 编解码 |
| `regex.Pattern` | `compile()` / `matcher()` | 正则表达式 |
| `regex.Matcher` | `find()` / `group()` / `replaceAll()` | 正则匹配 |
| `Collections` | `sort()` / `reverse()` | 集合排序 |
| `Arrays` | `sort()` / `toString()` | 数组工具 |

**官方文档**:
- [Java SE 8 - java.util](https://docs.oracle.com/javase/8/docs/api/java/util/package-summary.html)
- [OpenJDK 17 - Collections](https://devdocs.io/openjdk~17/java.base/java.util/collections)

### 1.4 java.lang — 基础类

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `String` | `split()` / `substring()` / `replace()` / `trim()` | 字符串操作 |
| `Integer` / `Long` / `Double` | `parseInt()` / `valueOf()` | 数值转换 |
| `Math` | `random()` / `abs()` / `round()` | 数学运算 |
| `Thread` | `sleep()` / `start()` | 线程控制 |
| `Runnable` | `run()` | 线程任务 |
| `System` | `currentTimeMillis()` / `nanoTime()` | 系统时间 |
| `Runtime` | `getRuntime()` | 运行时信息 |
| `Class` | `forName()` | 反射 |
| `Throwable` / `Exception` | `getMessage()` / `printStackTrace()` | 异常处理 |

**官方文档**: [Java SE 8 - java.lang](https://docs.oracle.com/javase/8/docs/api/java/lang/package-summary.html)

### 1.5 java.math — 精确运算

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `BigDecimal` | `add()` / `subtract()` / `multiply()` / `divide()` | 精确小数运算 |
| `BigInteger` | `add()` / `mod()` / `pow()` | 大整数运算 |
| `RoundingMode` | — | 舍入模式 |

**官方文档**: [Java SE 8 - java.math](https://docs.oracle.com/javase/8/docs/api/java/math/package-summary.html)

### 1.6 java.text — 文本格式化

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `SimpleDateFormat` | `format()` / `parse()` | 日期格式化 |
| `DecimalFormat` | `format()` | 数字格式化 |
| `NumberFormat` | `getInstance()` / `format()` | 数字格式化 |
| `Collator` | `getInstance()` / `compare()` | 文本排序 |

**官方文档**: [Java SE 8 - java.text](https://docs.oracle.com/javase/8/docs/api/java/text/package-summary.html)

### 1.7 java.security — 加密与安全

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `MessageDigest` | `getInstance()` / `digest()` | MD5 / SHA 哈希 |
| `KeyPairGenerator` | `getInstance()` / `generateKeyPair()` | 密钥生成 |
| `Cipher` | `getInstance()` / `doFinal()` | 加密解密 |
| `SecureRandom` | `nextBytes()` | 安全随机数 |

**官方文档**: [Java SE 8 - java.security](https://docs.oracle.com/javase/8/docs/api/java/security/package-summary.html)

---

## 2. Android 包

> Android 特有 API，需要在 Android 环境中运行（Legado 运行在 Android 上，所以可用）。

### 2.1 android.os — 系统核心

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `Build` | `MODEL` / `BRAND` / `VERSION.RELEASE` | 设备信息 |
| `Build.VERSION` | `SDK_INT` / `RELEASE` | 系统版本 |
| `Environment` | `getExternalStorageDirectory()` | 存储路径 |
| `Handler` | `post()` / `postDelayed()` | 消息处理 |
| `Looper` | `getMainLooper()` | 主线程 Looper |

**官方文档**: [Android - android.os](https://developer.android.com/reference/android/os/package-summary)

### 2.2 android.net — 网络工具

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `Uri` | `parse()` / `buildUpon()` | URI 解析与构建 |
| `ConnectivityManager` | `getActiveNetworkInfo()` | 网络状态检测 |
| `WifiManager` | `getConnectionInfo()` | WiFi 信息 |
| `WifiInfo` | `getIpAddress()` / `getSSID()` | WiFi IP 和名称 |
| `Proxy` | `getHost()` / `getPort()` | 代理设置 |

**官方文档**: [Android - android.net](https://developer.android.com/reference/android/net/package-summary)

### 2.3 android.util — 工具类

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `Base64` | `encodeToString()` / `decode()` | Base64 编解码 |
| `Log` | `d()` / `e()` / `i()` | 日志输出 |
| `TypedValue` | `applyDimension()` | 尺寸转换 |
| `SparseArray` | `put()` / `get()` | 高效整数键 Map |

**官方文档**: [Android - android.util](https://developer.android.com/reference/android/util/package-summary)

### 2.4 android.content — 内容管理

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `Context` | `getSharedPreferences()` / `getFilesDir()` | 应用上下文 |
| `SharedPreferences` | `edit()` / `getString()` / `putString()` | 轻量存储 |
| `Intent` | `new Intent()` / `putExtra()` | 意图跳转 |

**官方文档**: [Android - android.content](https://developer.android.com/reference/android/content/package-summary)

### 2.5 android.text — 文本处理

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `TextUtils` | `isEmpty()` / `join()` / `split()` | 文本工具 |
| `Html` | `fromHtml()` | HTML 转文本 |

**官方文档**: [Android - android.text](https://developer.android.com/reference/android/text/package-summary)

---

## 3. 第三方库

> Legado 项目引入的第三方库，在书源规则中可直接使用。

### 3.1 org.jsoup — HTML 解析

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `Jsoup` | `connect()` / `parse()` / `clean()` | HTML 解析入口 |
| `Document` | `select()` / `getElementById()` | DOM 操作 |
| `Elements` | `text()` / `attr()` / `html()` | 元素集合 |
| `Element` | `text()` / `attr()` / `select()` | 单个元素 |

**官方文档**: [jsoup.org/apidocs/](https://jsoup.org/apidocs/)

### 3.2 cn.hutool — Java 工具库

| 模块 | 常用类 | 用途 |
|------|--------|------|
| `cn.hutool.core.util` | `StrUtil` / `CharUtil` / `HexUtil` | 字符串工具 |
| `cn.hutool.core.codec` | `Base64` | 编解码 |
| `cn.hutool.core.date` | `DateUtil` | 日期工具 |
| `cn.hutool.core.io` | `FileUtil` / `IoUtil` | 文件 IO |
| `cn.hutool.crypto` | `SecureUtil` | 加密工具 |
| `cn.hutool.http` | `HttpUtil` | HTTP 工具 |
| `cn.hutool.json` | `JSONUtil` | JSON 处理 |

**官方文档**: [hutool.cn/docs/](https://hutool.cn/docs/)

### 3.3 okhttp3 — HTTP 客户端

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `OkHttpClient` | `newBuilder()` / `newCall()` | HTTP 客户端 |
| `Request` | `Builder()` | 构建请求 |
| `Response` | `body()` / `code()` / `headers()` | 响应处理 |
| `Headers` | `of()` / `get()` | 请求头 |
| `FormBody` | `add()` | 表单提交 |
| `MediaType` | `parse()` | 媒体类型 |

**官方文档**: [square.github.io/okhttp/](https://square.github.io/okhttp/)

### 3.4 com.google.gson — JSON 处理

| 类 | 常用方法 | 用途 |
|----|----------|------|
| `Gson` | `fromJson()` / `toJson()` | JSON 序列化/反序列化 |
| `JsonObject` | `add()` / `get()` / `has()` | JSON 对象 |
| `JsonArray` | `add()` / `get()` | JSON 数组 |
| `JsonParser` | `parseString()` | JSON 解析 |

**官方文档**: [javadoc.io/doc/com.google.code.gson/gson](https://javadoc.io/doc/com.google.code.gson/gson)

---

## 4. 调用方式说明

### 4.1 Java 标准包

```javascript
// java.xxx.类名.方法()
java.net.InetAddress.getByName("www.baidu.com")
java.util.regex.Pattern.compile("\\d+")
java.math.BigDecimal("3.14159")
java.security.MessageDigest.getInstance("MD5")
```

### 4.2 Android 包

```javascript
// android.xxx.类名.属性/方法()
android.os.Build.MODEL           // 手机型号
android.os.Build.VERSION.RELEASE // 系统版本号
android.util.Base64.encodeToString(bytes, 0)
```

### 4.3 第三方库

```javascript
// 直接用包名.类名.方法()
org.jsoup.Jsoup.connect("https://example.com").get()
cn.hutool.core.util.StrUtil.isBlank("")
com.google.gson.Gson().toJson(obj)
```

### 4.4 Legado 内置扩展

```javascript
// 通过 java 变量调用 JsExtensions 的方法
java.ajax("https://example.com")
java.connect("https://example.com")
java.webView(null, url, js)
java.getCookie("example.com")
```

---

## 5. 注意事项与限制

| 限制项 | 说明 |
|--------|------|
| Rhino 兼容性 | Rhino 不支持 Java 8+ 的 Lambda、Stream 等语法 |
| Android 版本 | 部分 API 需要 minSdk 版本，低版本可能报错 |
| 安全限制 | Android 10+ 限制获取 MAC 地址，可能返回随机值 |
| 文件操作 | 仅限阅读缓存目录，无法访问任意路径 |
| 主线程限制 | `webView` 等方法不能在主线程调用 |
| Context 获取 | 部分 Android API 需要 Context，书源规则中不易获取 |
| 网络请求 | 建议优先使用 Legado 内置的 `java.ajax()` / `java.connect()` |

---

## 6. 实用代码示例

### 6.1 获取设备信息

```javascript
// 获取设备信息
var info = {
    "model": android.os.Build.MODEL,             // 手机型号
    "brand": android.os.Build.BRAND,             // 品牌
    "version": android.os.Build.VERSION.RELEASE, // Android 版本
    "sdk": android.os.Build.VERSION.SDK_INT      // SDK 版本号
};
JSON.stringify(info);
```

### 6.2 获取本地 IPv4

```javascript
// 获取真实本地 IP（排除 127.0.0.1）
function getLocalIp() {
    var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
    while (interfaces.hasMoreElements()) {
        var iface = interfaces.nextElement();
        if (iface.isLoopback() || !iface.isUp()) continue;
        var addresses = iface.getInetAddresses();
        while (addresses.hasMoreElements()) {
            var addr = addresses.nextElement();
            if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                return addr.getHostAddress();
            }
        }
    }
    return null;
}
var ip = getLocalIp(); // 例如: 192.168.1.105
```

### 6.3 获取 MAC 地址

```javascript
// 获取本机 MAC 地址
function getMacAddress() {
    var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
    while (interfaces.hasMoreElements()) {
        var iface = interfaces.nextElement();
        if (iface.isLoopback() || !iface.isUp()) continue;
        var mac = iface.getHardwareAddress();
        if (mac != null && mac.length > 0) {
            var sb = [];
            for (var i = 0; i < mac.length; i++) {
                var hex = (mac[i] & 0xFF).toString(16).toUpperCase();
                if (hex.length == 1) hex = "0" + hex;
                sb.push(hex);
            }
            return sb.join(":");
        }
    }
    return null;
}
var mac = getMacAddress(); // 例如: A4:5E:60:E7:3B:2C
```

### 6.4 DNS 解析

```javascript
// 获取域名 IP（仅 IPv4）
function getIpv4(domain) {
    var all = java.net.InetAddress.getAllByName(domain);
    for (var i = 0; i < all.length; i++) {
        if (all[i] instanceof java.net.Inet4Address) {
            return all[i].getHostAddress();
        }
    }
    return null;
}
var baiduIp = getIpv4("www.baidu.com"); // 例如: 110.242.68.66
```

### 6.5 MD5 哈希

```javascript
// Java 标准方式
function md5(str) {
    var digest = java.security.MessageDigest.getInstance("MD5");
    var bytes = digest.digest(str.getBytes("UTF-8"));
    var sb = [];
    for (var i = 0; i < bytes.length; i++) {
        var hex = (bytes[i] & 0xFF).toString(16);
        if (hex.length == 1) hex = "0" + hex;
        sb.push(hex);
    }
    return sb.join("");
}
md5("hello"); // 5d41402abc4b2a76b9719d911017c592
```

### 6.6 正则匹配

```javascript
// Java 正则
var pattern = java.util.regex.Pattern.compile("\\d+");
var matcher = pattern.matcher("abc123def456");
while (matcher.find()) {
    var match = matcher.group(); // "123", "456"
}
```

### 6.7 日期格式化

```javascript
// 日期格式化
var sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
var now = sdf.format(new java.util.Date());
// "2026-06-04 15:30:00"
```

### 6.8 UUID 生成

```javascript
// 生成 UUID
var uuid = java.util.UUID.randomUUID().toString();
// "550e8400-e29b-41d4-a716-446655440000"
```

---

**提示**：优先使用 Legado 内置的 `java.ajax()` / `java.connect()` 进行网络请求，它们自动处理 Cookie、重定向和书源配置。Java 原生类适合做内置 API 无法覆盖的特殊操作。
