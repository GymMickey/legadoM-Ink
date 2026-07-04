# 订阅源字段规则类型对照表
> ⚠️ 注意：文档可能不精准，如有错误或缺少，请提交issue或Pull Request

## 规则类型说明

| 规则类型 | 标识符 | 说明 |
|---------|--------|------|
| **Default** | `@@` 前缀或直接写 | JSoup/CSS选择器（默认） |
| **XPath** | `@XPath:` 或以 `/` 开头 | XPath选择器 |
| **Json** | `@Json:` 或以 `$.` `$[` 开头 | JSONPath选择器 |
| **Regex** | `:` 开头（仅限列表规则） | 正则表达式，用于匹配HTML片段获取元素 |
| **Js** | `{{js代码}}`、`@js:js代码`、`<js>js代码</js>` | JavaScript代码 |
| **WebJs** | `@webJs:` | WebView注入JS |

> **注意**：
> - 正则规则（`:` 开头）只可以用在**列表规则**。
> - `##正则##替换` 是**替换语法**，用于对规则结果进行替换，不是规则类型标识符。

---

## 基础规则 (base)

| 字段 | 支持的规则类型 | 说明 |
|------|---------------|------|
| `sourceUrl` | 固定值 | 订阅源地址（必填） |
| `sourceName` | 固定值 | 订阅源名称 |
| `sourceIcon` | 固定值 | 图标URL |
| `sourceGroup` | 固定值 | 分组 |
| `sourceComment` | 固定值 | 注释 |
| `loginUrl` | 固定值 | 登录地址 |
| `loginUi` | 固定值 | 登录界面JSON |
| `loginCheckJs` | **Js** | 登录检测JS |
| `coverDecodeJs` | **Js** | 封面解密JS |
| `header` | 固定值 | 请求头 |
| `variableComment` | 固定值 | 变量说明 |
| `concurrentRate` | 固定值 | 并发率 |
| `jsLib` | 固定值 | JS库 |

---

## 列表规则 (list)

| 字段 | 支持的规则类型 | 返回结果 |
|------|---------------|----------|
| `ruleArticles` | XPath, Json, Default, **Regex**, Js, **WebJs** | List\<Element\> |
| `ruleNextPage` | XPath, Json, Default, Js, **WebJs** | List\<String\> 或 String |
| `ruleTitle` | XPath, Json, Default, Js, **WebJs** | String |
| `rulePubDate` | XPath, Json, Default, Js, **WebJs** | String |
| `ruleDescription` | XPath, Json, Default, Js, **WebJs** | String |
| `ruleImage` | XPath, Json, Default, Js, **WebJs** | String |
| `ruleLink` | XPath, Json, Default, Js, **WebJs** | String |

---

## WebView规则 (webView)

| 字段 | 支持的规则类型 | 返回结果 |
|------|---------------|----------|
| `ruleContent` | XPath, Json, Default, Js, **WebJs** | String |
| `style` | 固定值（CSS） | WebView样式 |
| `injectJs` | **WebJs** | 注入网页的JavaScript |
| `contentBlacklist` | 固定值 | 黑名单（逗号分隔） |
| `contentWhitelist` | 固定值 | 白名单（逗号分隔） |
| `shouldOverrideUrlLoading` | **Js** | 链接拦截JS，返回true拦截 |

---

## 启动规则 (start)

| 字段 | 支持的规则类型 | 说明 |
|------|---------------|------|
| `startHtml` | 固定值 | 启动页HTML |
| `startStyle` | 固定值（CSS） | 启动页样式 |
| `startJs` | **Js** | 启动页JS |
| `preloadJs` | **WebJs** | 预注入Js（在网页加载前执行） |

---

## 规则优先级

规则解析时按以下顺序识别类型：

1. 包含 `{{...}}` → **Js** 模式（内联JS求值）
2. 以 `@XPath:` 开头 → **XPath** 模式
3. 以 `@Json:` 开头 → **Json** 模式
4. 内容是JSON或以 `$.` / `$[` 开头 → **Json** 模式
5. 以 `/` 开头 → **XPath** 模式
6. 以 `:` 开头 → **Regex** 模式（仅限列表规则）
7. 以 `@@` 开头 → **Default** 模式
8. 包含 `@js:...` 或 `<js>...</js>` → **Js** 模式
9. 包含 `@webjs:...` → **WebJs** 模式
10. 默认 → **Default** (JSoup/CSS)

---

## 示例

```css
/* Default (JSoup) - 默认模式 */
.article-list > div

/* XPath */
@XPath://div[@class="article-list"]//div
//div[@class="article"]  /* 以/开头也可识别为XPath */

/* Json */
$.data.articles[*]
@Json:$.data.articles[*]

/* Regex - 仅限列表规则，用于匹配HTML片段 */
:<div class="article">[\s\S]*?</div>

/* Js - 三种格式 */
{{result.replace(/<[^>]+>/g, "")}}
@js:result.replace(/<[^>]+>/g, "")
<js>result.replace(/<[^>]+>/g, "")</js>
```

## 替换语法

在规则末尾使用 `##` 拼接替换内容：

```
##正则匹配##替换内容      （全局替换）
##正则匹配##替换内容###  （仅替换第一个匹配）
```

**示例：**
```css
/* 规则 + 全局替换 */
.title##<[^>]+>##      → 去掉title中的HTML标签

/* 规则 + 仅替换第一个 */
.content<div class="content">([\s\S]*)</div>##$1###  → 只保留第一个匹配
```

---

## 订阅源特有功能

### 预注入Js (preloadJs)

在网页加载前注入到网页的JavaScript，可注入阅读JS函数到网页中：

```js
// 在预注入Js规则里
window.ajaxAwait = ajaxAwait;
window.java = java;

// 在网页内容中调用
window.ajaxAwait("https://example.com")
  .then(r=>alert(r));
java.toast("调用java函数");
```

### 链接拦截 (shouldOverrideUrlLoading)

填写JS，变量 `url` 为当前资源链接，返回 `true` 拦截：

```js
// 示例：拦截特定链接
if (url.includes("login")) {
  java.toast("拦截登录链接");
  result = true;
}
```

### WebView 内置函数

预注入Js后可使用以下异步函数：

| 函数 | 说明 |
|------|------|
| `run(code)` | 执行阅读函数代码字符串 |
| `ajaxAwait(url, timeout)` | 异步访问URL |
| `connectAwait(url, header, timeout)` | 异步访问返回序列化响应 |
| `getAwait(url, header, timeout)` | 返回响应体结果 |
| `headAwait(url, header, timeout)` | 返回序列化后的响应头 |
| `postAwait(url, body, header, timeout)` | POST请求 |
| `webViewAwait(html, url, js, cacheFirst)` | WebView异步访问 |
| `downloadFileAwait(url)` | 下载文件 |
| `getStringAwait(ruleStr, mContent)` | 获取字符串规则结果 |

---

## WebJs 用法详解

`@webJs:` 是在 **WebView 环境** 中执行 JavaScript，可以访问网页的 DOM 和执行异步操作。

### 与 `@js:` 的区别

| 特性 | `@js:` | `@webjs:` |
|------|--------|-----------|
| 执行环境 | Rhino JS引擎 | WebView |
| DOM访问 | ❌ 不能访问网页DOM | ✅ 可以操作网页DOM |
| 异步支持 | ❌ 同步执行 | ✅ 支持异步（如`fetch`） |
| 适用场景 | 数据处理、字符串操作 | 模拟点击、DOM操作、动态内容 |

### 传入的变量

| 变量 | 说明 |
|------|------|
| `result` | 上一个规则/步骤的结果 |
| `baseUrl` | 当前页面基础URL |
| `src` | 原始HTML内容 |
| `java` | Java接口（可调用APP功能） |

### 示例

```js
/* 获取网页标题 */
@webjs:document.title

/* 处理上一个规则的结果 */
@webjs:result + ',处理后'

/* 模拟点击获取动态内容 */
@webjs:(function(){
  var btn = document.querySelector('.more-btn');
  if(btn) btn.click();
  return document.body.innerHTML;
})()

/* 等待动态加载后获取内容 */
@webjs:new Promise(resolve =>
  setTimeout(() => resolve(document.documentElement.outerHTML), 1000)
)
```

### 适用场景

- 网页内容是 **JavaScript 动态渲染** 的
- 需要 **模拟用户操作**（点击、滚动等）
- 需要 **等待异步加载** 后再获取内容
- 需要 **访问网页DOM** 获取特定元素
