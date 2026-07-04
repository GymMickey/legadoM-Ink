# 订阅源调试功能设计文档

## 一、设计目标

让订阅源调试具备与书源调试同等的能力，能够清晰展示：
1. 规则执行的完整路径和嵌套关系
2. 每一步的输入输出数据
3. JS 执行环境状态
4. 网络请求完整详情
5. 执行耗时和性能分析

## 二、界面布局设计

### 2.1 整体结构

```
┌─────────────────────────────────────────────────────────────┐
│  订阅源调试 - [源名称]                          [展开/收缩] │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─ 执行概览 ─────────────────────────────────────────────┐ │
│  │  ✔ 12 成功   ✘ 1 失败   ⊘ 3 跳过   总耗时: 1.2s      │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ 配置检查 ─────────────────────────────────────────────┐ │
│  │  ✔ 源URL      https://example.com/rss                  │ │
│  │  ✔ 列表规则   @css:.article-list li                    │ │
│  │  ⊘ 下一页规则 (为空跳过)                                │ │
│  │  ✔ 标题规则   @css:.title@text                         │ │
│  │  ...                                                    │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ 网络请求 ─────────────────────────────────────────────┐ │
│  │  [展开] GET https://example.com/rss                    │ │
│  │  ├─ 请求头                                              │ │
│  │  │  User-Agent: Mozilla/5.0...                         │ │
│  │  │  Cookie: session=abc123                             │ │
│  │  ├─ 响应头                                              │ │
│  │  │  Content-Type: text/html; charset=utf-8            │ │
│  │  │  Content-Length: 45678                              │ │
│  │  └─ 耗时: 320ms  状态码: 200                           │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ 规则执行路径 ─────────────────────────────────────────┐ │
│  │                                                         │ │
│  │  🌳 列表规则: @css:.article-list li                    │ │
│  │  ├── [1] @css:.article-list (CSS选择器)    15ms       │ │
│  │  │   ├── 输入: <html>...全文...</html>                 │ │
│  │  │   ├── 匹配: 10 个元素                               │ │
│  │  │   └── 输出: <li class="article">...</li> × 10      │ │
│  │  └── [2] li (子选择器)                      2ms        │ │
│  │      └── 输出: 10 个 <li> 元素                         │ │
│  │                                                         │ │
│  │  🌳 标题规则: @css:.title@text##{{js}}                 │ │
│  │  ├── [1] @css:.title (CSS选择器)            3ms        │ │
│  │  │   └── 输出: <h2 class="title">文章标题</h2>         │ │
│  │  ├── [2] @text (文本提取)                   1ms        │ │
│  │  │   └── 输出: "文章标题"                              │ │
│  │  └── [3] ##{{js}} (JS替换)                  5ms        │ │
│  │      ├── 输入: "文章标题"                              │ │
│  │      ├── JS环境:                                       │ │
│  │      │   result = "文章标题"                           │ │
│  │      │   src = "https://example.com/rss"              │ │
│  │      │   source = RssSource{...}                      │ │
│  │      │   variables = {}                               │ │
│  │      └── 输出: "【文章标题】"                          │ │
│  │                                                         │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ 提取结果 ─────────────────────────────────────────────┐ │
│  │  ✔ 标题: "【文章标题】"                                 │ │
│  │  ✔ 发布日期: "2024-01-15"                              │ │
│  │  ⊘ 描述: (规则为空，将解析内容页)                       │ │
│  │  ✘ 图片: 未匹配到元素                                  │ │
│  │  ✔ 链接: https://example.com/article/123              │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 规则执行路径树

每个规则展示为一个可展开的树形结构：

```
🌳 规则: @css:.content@textNodes##{{js}}##regex
│
├── [1] 🎨 @css:.content (CSS选择器)
│   ├── ⬇️ 输入: <html>...(前200字符)...</html>
│   ├── 📊 匹配: 3 个元素
│   └── ⬆️ 输出: <div class="content">...</div>
│
├── [2] 📝 @textNodes (文本节点提取)
│   ├── ⬇️ 输入: <div class="content">...</div>
│   └── ⬆️ 输出: "第一段\n第二段\n第三段"
│
├── [3] 📜 ##{{js}} (JS替换)
│   ├── ⬇️ 输入: "第一段\n第二段\n第三段"
│   ├── 🔧 JS环境:
│   │   ├── result: "第一段\n第二段\n第三段"
│   │   ├── src: "https://example.com/article"
│   │   ├── baseUrl: "https://example.com"
│   │   ├── source: RssSource{sourceUrl, sourceName, ...}
│   │   └── variables: {key: "value"}
│   └── ⬆️ 输出: "处理后的内容"
│
└── [4] 🔄 ##regex (正则替换)
    ├── ⬇️ 输入: "处理后的内容"
    ├── 📊 匹配分组: ["group1", "group2"]
    └── ⬆️ 输出: "最终结果"
```

### 2.3 JS 执行环境面板

点击 JS 步骤时展开显示：

```
┌─ JS 执行环境 ────────────────────────────────────┐
│                                                   │
│  📦 内置变量                                       │
│  ├── result:  "当前规则结果"                      │
│  ├── src:     "https://example.com/rss"          │
│  ├── baseUrl: "https://example.com"              │
│                                                   │
│  📦 源对象                                         │
│  ├── source.sourceUrl:  "https://example.com"    │
│  ├── source.sourceName: "示例订阅源"              │
│  ├── source.ruleArticles: "@css:.article-list"   │
│                                                   │
│  📦 用户变量 (@put/@get)                          │
│  ├── lastPage: "2"                                │
│  └── token: "abc123"                              │
│                                                   │
│  📜 执行的 JS 代码                                 │
│  ┌─────────────────────────────────────────────┐ │
│  │  if (result.includes('广告')) {              │ │
│  │      result = result.replace(/广告/g, '');  │ │
│  │  }                                           │ │
│  │  result;                                     │ │
│  └─────────────────────────────────────────────┘ │
│                                                   │
│  ⬆️ 返回值: "处理后的结果"                        │
│                                                   │
└───────────────────────────────────────────────────┘
```

### 2.4 网络请求详情面板

```
┌─ 网络请求 ────────────────────────────────────────┐
│                                                   │
│  🌐 GET https://example.com/rss?page=1           │
│  ⏱️ 耗时: 320ms  📦 大小: 45.6KB  ✅ 状态: 200   │
│                                                   │
│  📤 请求头                                        │
│  ├── User-Agent: Mozilla/5.0 (Windows NT 10.0)  │
│  ├── Accept: text/html,application/xhtml+xml    │
│  ├── Cookie: session=abc123; uid=456            │
│  └── Referer: https://example.com               │
│                                                   │
│  📥 响应头                                        │
│  ├── Content-Type: text/html; charset=utf-8     │
│  ├── Content-Length: 45678                      │
│  ├── Cache-Control: max-age=3600               │
│  └── Date: Mon, 15 Jan 2024 10:30:00 GMT       │
│                                                   │
│  🔄 重定向链                                      │
│  ├── 301: https://example.com/old → /rss        │
│  └── 最终: https://example.com/rss              │
│                                                   │
└───────────────────────────────────────────────────┘
```

## 三、数据结构设计

### 3.1 规则执行节点 (复用现有)

```kotlin
data class RuleExecutionNode(
    val stepIndex: Int,           // 步骤序号
    val ruleType: RuleType,       // 规则类型 (CSS/XPath/JS/Regex等)
    val ruleContent: String,      // 规则内容
    val input: String?,           // 输入数据 (截取前200字符)
    val output: String?,          // 输出数据 (截取前200字符)
    val matchCount: Int?,         // 匹配数量 (用于选择器)
    val duration: Long?,          // 执行耗时 (毫秒)
    val jsContext: JsExecutionContext?,  // JS执行环境
    val regexGroups: List<String>?,      // 正则匹配分组
    val children: List<RuleExecutionNode>, // 嵌套子规则
    val error: Throwable?         // 错误信息
)
```

### 3.2 JS 执行环境

```kotlin
data class JsExecutionContext(
    val result: String?,          // 当前结果
    val src: String?,             // 源URL
    val baseUrl: String?,         // 基硎URL
    val source: SourceInfo?,      // 源对象信息
    val variables: Map<String, String>, // 用户变量
    val jsCode: String?,          // 执行的JS代码
    val returnValue: String?      // JS返回值
)
```

### 3.3 网络请求详情

```kotlin
data class NetworkRequestDetail(
    val url: String,              // 请求URL
    val method: String,           // 请求方法
    val requestHeaders: Map<String, String>,  // 请求头
    val requestBody: String?,     // 请求体
    val statusCode: Int,          // 状态码
    val responseHeaders: Map<String, String>, // 响应头
    val contentLength: Long,      // 响应大小
    val duration: Long,           // 耗时
    val redirectChain: List<String> // 重定向链
)
```

## 四、实现要点

### 4.1 规则执行追踪

在 `RssParserByRule.kt` 中集成 `RuleExecutionTracker`：

```kotlin
// 解析列表时
val tracker = RuleExecutionTracker(rssSource, ruleArticles)
tracker.startStep(RuleType.CSS, "@css:.article-list", body)
val collections = analyzeRule.getElements(ruleArticles)
tracker.endStep(collections.size.toString(), matchCount = collections.size)

// 构建执行树
val tree = tracker.buildTree()
FlowLogRecorder.record(tree)
```

### 4.2 JS 环境捕获

在 JS 执行前后捕获环境状态：

```kotlin
val jsContext = JsExecutionContext(
    result = result?.toString(),
    src = analyzeRule.baseUrl,
    baseUrl = analyzeRule.baseUrl,
    source = SourceInfo.from(rssSource),
    variables = ruleData.getVariableMap(),
    jsCode = jsCode,
    returnValue = null
)
tracker.endStep(output, jsContext = jsContext)
```

### 4.3 网络请求详情

在 `AnalyzeUrl` 中记录完整请求信息：

```kotlin
val requestDetail = NetworkRequestDetail(
    url = request.url.toString(),
    method = request.method,
    requestHeaders = request.headers.toMap(),
    statusCode = response.code,
    responseHeaders = response.headers.toMap(),
    contentLength = response.body?.contentLength() ?: 0,
    duration = duration,
    redirectChain = redirectUrls
)
```

## 五、UI 组件设计

### 5.1 规则执行树组件

```kotlin
@Composable
fun RuleExecutionTreeView(
    tree: RuleExecutionTree,
    expanded: Boolean = true,
    onNodeClick: (RuleExecutionNode) -> Unit
)
```

### 5.2 JS 环境面板组件

```kotlin
@Composable
fun JsEnvironmentPanel(
    context: JsExecutionContext,
    expanded: Boolean = false
)
```

### 5.3 网络请求面板组件

```kotlin
@Composable
fun NetworkRequestPanel(
    detail: NetworkRequestDetail,
    expanded: Boolean = false
)
```

## 六、与书源调试的统一

### 6.1 共享组件

- `RuleExecutionNode` - 规则执行节点
- `RuleExecutionTracker` - 规则执行追踪器
- `JsExecutionContext` - JS执行环境
- `RuleExecutionTreeView` - 规则执行树组件

### 6.2 差异处理

| 功能 | 书源 | 订阅源 |
|------|------|--------|
| 搜索规则 | ruleSearch | - |
| 发现规则 | ruleExplore | sortUrl |
| 目录规则 | ruleToc | - |
| 正文规则 | ruleContent | ruleContent |
| 列表规则 | - | ruleArticles |
| 下一页 | ruleNextUrl | ruleNextPage |

## 七、性能考虑

1. **数据截断**: 输入输出数据截取前 200 字符，避免内存溢出
2. **懒加载**: 树形结构默认只展开第一层
3. **异步更新**: 使用 SharedFlow 实时推送更新
4. **历史限制**: 最多保留 500 条执行记录

## 八、示例场景

### 场景 1: 列表规则调试

用户调试列表规则 `@css:.article-list li`，期望看到：
1. CSS 选择器匹配了多少元素
2. 每个元素的具体内容
3. 匹配失败时的原因（选择器错误/页面结构变化）

### 场景 2: JS 规则调试

用户调试 JS 规则 `{{result.replace(/广告/g, '')}}`，期望看到：
1. JS 执行前的 result 值
2. JS 执行后的返回值
3. JS 执行环境中的所有变量
4. JS 执行错误时的错误信息

### 场景 3: 网络问题排查

用户遇到网络请求失败，期望看到：
1. 完整的请求 URL 和参数
2. 请求头和 Cookie
3. 响应状态码和响应头
4. 重定向链路
5. 具体的错误信息
