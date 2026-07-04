# 书源字段规则类型对照表
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
> - 正则规则（`:` 开头）只可以用在**书籍列表和目录列表**。
> - `##正则##替换` 是**替换语法**，用于对规则结果进行替换，不是规则类型标识符。

---

## 搜索规则 (ruleSearch)

| 字段 | 支持的规则类型 | 返回结果 |
|------|---------------|----------|
| `bookList` | XPath, Json, Default, Regex, Js, **WebJs** | List\<Element\> |
| `name` | XPath, Json, Default, Js, **WebJs** | String |
| `author` | XPath, Json, Default, Js, **WebJs** | String |
| `intro` | XPath, Json, Default, Js, **WebJs** | String |
| `kind` | XPath, Json, Default, Js, **WebJs** | String |
| `lastChapter` | XPath, Json, Default, Js, **WebJs** | String |
| `updateTime` | XPath, Json, Default, Js, **WebJs** | String |
| `bookUrl` | XPath, Json, Default, Js, **WebJs** | String (URL) |
| `coverUrl` | XPath, Json, Default, Js, **WebJs** | String (URL) |
| `wordCount` | XPath, Json, Default, Js, **WebJs** | String |

---

## 发现规则 (ruleExplore)

| 字段 | 支持的规则类型 | 返回结果 |
|------|---------------|----------|
| `bookList` | XPath, Json, Default, Regex, Js, **WebJs** | List\<Element\> |
| `name` | XPath, Json, Default, Js, **WebJs** | String |
| `author` | XPath, Json, Default, Js, **WebJs** | String |
| `intro` | XPath, Json, Default, Js, **WebJs** | String |
| `kind` | XPath, Json, Default, Js, **WebJs** | String |
| `lastChapter` | XPath, Json, Default, Js, **WebJs** | String |
| `updateTime` | XPath, Json, Default, Js, **WebJs** | String |
| `bookUrl` | XPath, Json, Default, Js, **WebJs** | String (URL) |
| `coverUrl` | XPath, Json, Default, Js, **WebJs** | String (URL) |
| `wordCount` | XPath, Json, Default, Js, **WebJs** | String |

---

## 详情规则 (ruleBookInfo)

| 字段 | 支持的规则类型 | 返回结果 |
|------|---------------|----------|
| `init` | **仅 Regex (AllInOne)** | 预处理，用于加速详情信息检索 |
| `name` | XPath, Json, Default, Js, **WebJs** | String |
| `author` | XPath, Json, Default, Js, **WebJs** | String |
| `intro` | XPath, Json, Default, Js, **WebJs** | String |
| `kind` | XPath, Json, Default, Js, **WebJs** | String |
| `lastChapter` | XPath, Json, Default, Js, **WebJs** | String |
| `updateTime` | XPath, Json, Default, Js, **WebJs** | String |
| `coverUrl` | XPath, Json, Default, Js, **WebJs** | String (URL) |
| `tocUrl` | XPath, Json, Default, Js, **WebJs** | String (URL，与详情页相同时可省略) |
| `wordCount` | XPath, Json, Default, Js, **WebJs** | String |
| `canReName` | XPath, Json, Default, Js, **WebJs** | String/Bool（允许修改书名作者） |
| `downloadUrls` | XPath, Json, Default, Js, **WebJs** | String/Array（文件类书源） |

---

## 目录规则 (ruleToc)

| 字段 | 支持的规则类型 | 返回结果 |
|------|---------------|----------|
| `preUpdateJs` | **仅 Js** | 更新目录前调用JS动态更新目录链接 |
| `chapterList` | XPath, Json, Default, Regex, Js, **WebJs** | List\<Element\> |
| `chapterName` | XPath, Json, Default, Js, **WebJs** | String |
| `chapterUrl` | XPath, Json, Default, Js, **WebJs** | String (URL) |
| `formatJs` | **仅 Js** | 遍历去重后的章节列表回调 |
| `isVolume` | XPath, Json, Default, Js, **WebJs** | Bool（章节名称是否是卷名） |
| `isVip` | XPath, Json, Default, Js, **WebJs** | Bool（是否为VIP章节） |
| `isPay` | XPath, Json, Default, Js, **WebJs** | Bool（是否为已购买） |
| `updateTime` | XPath, Json, Default, Js, **WebJs** | String（章节信息如更新时间） |
| `nextTocUrl` | XPath, Json, Default, Js, **WebJs** | String/List（目录下一页链接） |

---

## 正文规则 (ruleContent)

| 字段 | 支持的规则类型 | 返回结果 |
|------|---------------|----------|
| `content` | XPath, Json, Default, Js, **WebJs** | String |
| `subContent` | XPath, Json, Default, Js, **WebJs** | String（副文，拼接在正文后面） |
| `title` | XPath, Json, Default, Js, **WebJs** | String（获取结果将覆盖章节标题） |
| `nextContentUrl` | XPath, Json, Default, Js, **WebJs** | String (URL，下一分页链接) |
| `webJs` | **WebJs** | 注入javascript，必须有返回值 |
| `sourceRegex` | **Regex** | 匹配资源的url特征，用于嗅探 |
| `replaceRegex` | **Regex** | 多页内容合并后替换，用于正文净化 |
| `imageStyle` | 固定值 | FULL:铺满，不填:默认样式 |
| `imageDecode` | **Js** | 返回解密图片的bytes |
| `payAction` | **Js** | 返回购买链接或调用购买接口 |
| `callBackJs` | **Js** | 回调JS，变量event为事件对象名称 |

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
.book-list > div

/* XPath */
@XPath://div[@class="book-list"]//div
//div[@class="book"]  /* 以/开头也可识别为XPath */

/* Json */
$.data.books[*]
@Json:$.data.books[*]

/* Regex - 仅限列表规则，用于匹配HTML片段 */
:<div class="book">[\s\S]*?</div>

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
.name{{result}}##<[^>]+>##      → 去掉name中的HTML标签

/* 规则 + 仅替换第一个 */
.content<div class="content">([\s\S]*)</div>##$1###  → 只保留第一个匹配
```

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
| `book` | 书籍对象 |
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
