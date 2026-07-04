# 订阅源机制详解

## 概述

订阅源（RSS Source）是 Legado 阅读器中用于获取和展示文章/视频/图片内容的组件。与书源不同，订阅源主要用于持续更新的内容，如新闻网站、视频平台、博客等。

***

## 核心组件

### 1. 数据实体

| 类名           | 文件位置                          | 说明               |
| ------------ | ----------------------------- | ---------------- |
| `RssSource`  | `data/entities/RssSource.kt`  | 订阅源配置实体，包含所有规则字段 |
| `RssArticle` | `data/entities/RssArticle.kt` | 文章实体，存储解析后的文章信息  |

### 2. 核心解析器

| 类名                 | 文件位置                            | 说明                                        |
| ------------------ | ------------------------------- | ----------------------------------------- |
| `Rss`              | `model/rss/Rss.kt`              | 网络请求入口，提供 `getArticles` 和 `getContent` 方法 |
| `RssParserByRule`  | `model/rss/RssParserByRule.kt`  | 规则解析器，根据订阅源规则解析文章列表                       |
| `RssParserDefault` | `model/rss/RssParserDefault.kt` | 默认XML解析器，当列表规则为空时使用                       |

### 3. UI层

| 类名                    | 文件位置                                    | 说明                    |
| --------------------- | --------------------------------------- | --------------------- |
| `ReadRssActivity`     | `ui/rss/read/ReadRssActivity.kt`        | RSS阅读界面Activity       |
| `ReadRssViewModel`    | `ui/rss/read/ReadRssViewModel.kt`       | 阅读界面的ViewModel，处理业务逻辑 |
| `RssArticlesFragment` | `ui/rss/article/RssArticlesFragment.kt` | 文章列表Fragment          |

***

## 订阅源字段说明

### 基础配置 (base)

| 字段             | 说明                            |
| -------------- | ----------------------------- |
| `sourceUrl`    | 订阅源地址（必填），可以是网页URL或RSS Feed地址 |
| `sourceName`   | 订阅源名称                         |
| `sourceIcon`   | 图标URL                         |
| `sourceGroup`  | 分组，用于分类管理                     |
| `enabled`      | 是否启用                          |
| `loginUrl`     | 登录地址（可选）                      |
| `loginCheckJs` | 登录检测JS脚本                      |

### 列表规则 (list)

| 字段                | 说明                         | 返回类型   |
| ----------------- | -------------------------- | ------ |
| `ruleArticles`    | **列表规则**（最关键），用于从页面中提取文章列表 | List   |
| `ruleNextPage`    | 下一页规则，用于翻页                 | String |
| `ruleTitle`       | 标题规则，从列表项中提取文章标题           | String |
| `rulePubDate`     | 发布时间规则                     | String |
| `ruleDescription` | **描述规则**，从列表项中提取文章描述/摘要    | String |
| `ruleImage`       | 图片规则，提取封面图                 | String |
| `ruleLink`        | **链接规则**，提取文章详情页URL        | String |

### WebView规则 (webView)

| 字段                         | 说明                      |
| -------------------------- | ----------------------- |
| `ruleContent`              | **正文规则**，用于在文章详情页提取正文内容 |
| `contentWhitelist`         | 白名单，限制WebView加载的域名      |
| `contentBlacklist`         | 黑名单，阻止加载特定域名            |
| `shouldOverrideUrlLoading` | 链接拦截JS，可拦截特定URL         |
| `style`                    | WebView样式（CSS）          |
| `injectJs`                 | 注入网页的JavaScript         |
| `preloadJs`                | 预注入Js（在网页加载前执行）         |

### 启动规则 (start)

| 字段           | 说明        |
| ------------ | --------- |
| `startHtml`  | 启动页HTML内容 |
| `startStyle` | 启动页样式     |
| `startJs`    | 启动页JS脚本   |

***

## 文章加载流程

### 首次进入文章详情

```
initData()
  │
  ├─ link存在？──否──→ startHtml存在？
  │                        ├─是→ 加载startHtml
  │                        └─否→ ruleContent存在？
  │                                    ├─是→ 用规则加载正文
  │                                    └─否→ 加载openUrl
  │
  └─link存在？──是──→ description存在？
                           ├─是→ 直接显示description
                           └─否→ ruleContent存在？
                                      ├─是→ 用规则加载正文
                                      └─否→ 加载URL
```

### 刷新流程（修复后）

```
refresh()
  │
  ├─ singleUrl？──是──→ WebView.reload()
  │
  └─否──→ viewModel.refresh()
            │
            ├─ description存在？──是──→ 直接显示description
            │
            └─否──→ ruleContent存在？
                        ├─是→ 用规则加载正文
                        └─否→ 加载URL
```

***

## description 与 ruleContent 的区别

这是理解订阅源行为的关键：

| 字段                | 作用时机       | 存储内容           | 备注         |
| ----------------- | ---------- | -------------- | ---------- |
| `ruleDescription` | **列表解析时**  | 从文章列表页提取的描述/摘要 | 可能包含视频播放代码 |
| `ruleContent`     | **详情页加载时** | 正文页的内容规则       | 用于提取正文正文   |

### 场景分析

**场景1：只有 ruleDescription，没有 ruleContent**

```
文章列表页 → 使用 ruleDescription 提取描述（可能是视频HTML）
     ↓
点击文章 → 检查 description 不为空 → 直接显示 description
     ↓
刷新 → 同样检查 description → 直接显示 description（正确）
```

**场景2：只有 ruleContent，没有 ruleDescription**

```
文章列表页 → description 为空
     ↓
点击文章 → description 为空，检查 ruleContent
     ↓
有 ruleContent → 用 ruleContent 加载正文
     ↓
刷新 → 同样走这个流程（正确）
```

**场景3：两个规则都没有**

```
文章列表页 → description 为空
     ↓
点击文章 → 两个规则都为空 → 直接加载文章 URL
     ↓
刷新 → 同样直接加载 URL（正确）
```

***

## 刷新问题根源分析

### 原始问题

用户反馈：订阅源规则只写描述规则（ruleDescription），不写内容规则（ruleContent），首次进入能打开视频，但刷新后显示空白JSON。

### 原因分析

1. **首次进入时**：`initData()` 检查 `description` 不为空，直接显示 → 视频播放正常
2. **刷新时**：原 `refresh()` 方法只检查 `ruleContent`，不考虑 `description`
   - 跳转到 `start()` 方法重新启动Activity
   - `initData()` 收到 `openUrl` 参数，走 else 分支
   - `ruleContent` 为空，直接 `loadUrl()` → 加载原始JSON

### 修复方案

修改 `ReadRssViewModel.refresh()` 方法的判断逻辑：

```kotlin
fun refresh(finish: () -> Unit) {
    val rssArticle = rssArticle ?: return finish.invoke()
    val rssSource = rssSource ?: let {
        appCtx.toastOnUi("订阅源不存在")
        return finish.invoke()
    }
    // 优先检查 description（新增）
    if (!rssArticle.description.isNullOrBlank()) {
        contentLiveData.postValue(rssArticle.description!!)
    } else {
        val ruleContent = rssSource.ruleContent
        if (!ruleContent.isNullOrBlank()) {
            loadContent(rssArticle, ruleContent)
        } else {
            viewModelScope.launch(IO) {
                loadUrl(rssArticle.link, rssArticle.origin)
            }
        }
    }
    finish()
}
```

***

## 订阅源类型

| 类型 | type值 | 说明   | 加载方式        |
| -- | ----- | ---- | ----------- |
| 网页 | 0     | 普通文章 | WebView加载正文 |
| 图片 | 1     | 图片链接 | 点击直接显示图片    |
| 视频 | 2     | 视频链接 | 内置视频播放器播放   |

***

## 规则解析优先级

当解析规则时，按以下顺序识别类型：

1. `{{...}}` → **Js** 模式
2. `@XPath:` 开头 → **XPath** 模式
3. `@Json:` 开头 → **Json** 模式
4. `$.` 或 `$[` 开头 → **Json** 模式
5. `/` 开头 → **XPath** 模式
6. `:` 开头 → **Regex** 模式（仅限列表规则）
7. `@@` 开头 → **Default** 模式
8. `@js:...` 或 `<js>...</js>` → **Js** 模式
9. `@webjs:...` → **WebJs** 模式
10. 默认 → **Default** (JSoup/CSS选择器)

***

## 单URL源 (singleUrl)

当订阅源的 `singleUrl` 设为 `true` 时，表示这是一个单页面源：

- 整个订阅源只有一个URL
- 刷新时直接调用 `currentWebView.reload()` 刷新当前页面
- 不需要传递 `link` 参数

***

## 预注入JS (preloadJs)

预注入JS是在网页加载**之前**注入的JavaScript代码，可用于：

- 向网页暴露APP的JavaScript接口
- 修改网页行为
- 实现登录态传递等

```js
// 在预注入Js规则里
window.ajaxAwait = ajaxAwait;
window.java = java;

// 在网页内容中调用
window.ajaxAwait("https://example.com").then(r=>alert(r));
java.toast("调用java函数");
```

***

## 常见问题

### Q1: 为什么刷新后显示空白？

检查订阅源的 `ruleContent` 和 `ruleDescription` 配置：

- 如果只有 `ruleDescription`，刷新时需要确保 `description` 不被清除
- 如果两者都没有，刷新会直接加载原始URL

### Q2: 视频无法播放怎么办？

确认：

1. `ruleDescription` 中包含完整的视频播放代码（如 `<video>` 或 `<iframe>`）
2. 或者 `ruleContent` 能正确提取视频链接

### Q3: 列表规则为空会怎样？

当 `ruleArticles` 为空时，系统会使用 `RssParserDefault` 尝试用标准XML格式解析。这适用于标准的RSS/Atom Feed。

***

## 相关文件路径

```
app/src/main/java/io/legado/app/
├── data/entities/
│   ├── RssSource.kt          # 订阅源实体
│   └── RssArticle.kt         # 文章实体
├── model/rss/
│   ├── Rss.kt                # 网络请求入口
│   ├── RssParserByRule.kt    # 规则解析器
│   └── RssParserDefault.kt    # 默认XML解析器
└── ui/rss/
    ├── read/
    │   ├── ReadRssActivity.kt      # 阅读界面
    │   └── ReadRssViewModel.kt    # ViewModel
    └── article/
        └── RssArticlesFragment.kt  # 文章列表
```

