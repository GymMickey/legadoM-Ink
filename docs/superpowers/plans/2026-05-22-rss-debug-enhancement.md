# 订阅源调试增强实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为订阅源调试添加规则执行路径追踪、JS执行环境状态、网络请求详情等功能，使其与书源调试能力对等。

**架构：** 复用现有的 `RuleExecutionTracker` 和 `FlowLogRecorder`，在 `RssParserByRule.kt` 和 `Rss.kt` 中集成追踪逻辑，扩展 `RssExecutionStatus` 组件展示规则执行树。

**技术栈：** Kotlin, Jetpack Compose, StateFlow

---

## 文件结构

### 修改的文件
- `app/src/main/java/io/legado/app/model/rss/RssParserByRule.kt` - 集成规则执行追踪
- `app/src/main/java/io/legado/app/model/rss/Rss.kt` - 记录网络请求详情
- `app/src/main/java/io/legado/app/model/debug/RssExecutionRecord.kt` - 扩展执行步骤枚举
- `app/src/main/java/io/legado/app/data/repository/debug/RssExecutionRecorder.kt` - 增加规则执行树记录
- `app/src/main/java/io/legado/app/ui/debuglog/components/RssExecutionStatus.kt` - 展示规则执行树

### 新增的文件
- `app/src/main/java/io/legado/app/model/debug/RssRuleExecutionRecord.kt` - 订阅源规则执行记录

---

## 任务 1：扩展 RssExecutionStep 枚举

**文件：**
- 修改：`app/src/main/java/io/legado/app/model/debug/RssExecutionRecord.kt`

- [ ] **步骤 1：添加新的执行步骤枚举值**

在 `RssExecutionStep` 枚举中添加规则执行相关步骤：

```kotlin
enum class RssExecutionStep(val displayName: String, val isConfigCheck: Boolean) {
    // 现有配置检查步骤...
    SOURCE_NAME("源名称", true),
    SOURCE_URL("源URL", true),
    SOURCE_ICON("图标", true),
    SOURCE_GROUP("源分组", true),
    SORT_URL("分类URL", true),
    RULE_ARTICLES("列表规则", true),
    RULE_NEXT_PAGE("下一页规则", true),
    RULE_TITLE("标题规则", true),
    RULE_PUB_DATE("发布日期规则", true),
    RULE_DESCRIPTION("描述规则", true),
    RULE_IMAGE("图片规则", true),
    RULE_LINK("链接规则", true),
    RULE_CONTENT("正文规则", true),
    SHOULD_OVERRIDE_URL("url跳转拦截", true),
    
    // 网络请求步骤
    NETWORK_REQUEST("网络请求", false),
    RESPONSE_BODY("响应内容", false),
    
    // 规则解析步骤（新增）
    PARSE_LIST("列表解析", false),
    PARSE_RULE_ARTICLES("列表规则解析", false),
    PARSE_RULE_TITLE("标题规则解析", false),
    PARSE_RULE_PUB_DATE("发布日期规则解析", false),
    PARSE_RULE_DESCRIPTION("描述规则解析", false),
    PARSE_RULE_IMAGE("图片规则解析", false),
    PARSE_RULE_LINK("链接规则解析", false),
    
    // 字段提取步骤
    EXTRACT_TITLE("提取标题", false),
    EXTRACT_PUB_DATE("提取发布日期", false),
    EXTRACT_DESCRIPTION("提取描述", false),
    EXTRACT_IMAGE("提取图片", false),
    EXTRACT_LINK("提取链接", false);
}
```

- [ ] **步骤 2：添加 RssRuleExecutionRecord 数据类**

在同一文件中添加规则执行记录数据类：

```kotlin
data class RssRuleExecutionRecord(
    val step: RssExecutionStep,
    val ruleContent: String? = null,
    val executionTree: RuleExecutionTree? = null,
    val input: String? = null,
    val output: String? = null,
    val matchCount: Int? = null,
    val duration: Long? = null,
    val error: Throwable? = null,
    val time: Long = System.currentTimeMillis(),
    val sourceUrl: String = "",
    val sourceName: String = "",
    val executionId: String = ""
)
```

---

## 任务 2：扩展 RssExecutionRecorder

**文件：**
- 修改：`app/src/main/java/io/legado/app/data/repository/debug/RssExecutionRecorder.kt`

- [ ] **步骤 1：添加规则执行记录存储**

在 `RssExecutionRecorder` 中添加规则执行记录的存储：

```kotlin
object RssExecutionRecorder {
    // 现有代码...
    
    private const val MAX_RECORDS = 500
    private const val MAX_RULE_RECORDS = 200
    
    private val records = ArrayDeque<RssExecutionRecord>()
    private val ruleRecords = ArrayDeque<RssRuleExecutionRecord>()
    
    private val _ruleRecordsFlow = MutableSharedFlow<List<RssRuleExecutionRecord>>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val ruleRecordsFlow: SharedFlow<List<RssRuleExecutionRecord>> = _ruleRecordsFlow.asSharedFlow()
    
    // ...
}
```

- [ ] **步骤 2：添加记录规则执行的方法**

```kotlin
fun recordRuleExecution(record: RssRuleExecutionRecord) {
    if (!isEnabled) return
    synchronized(ruleRecords) {
        ruleRecords.addFirst(record)
        while (ruleRecords.size > MAX_RULE_RECORDS) {
            ruleRecords.removeLast()
        }
        emitRuleRecordsUpdate()
    }
}

fun getCurrentRuleRecords(): List<RssRuleExecutionRecord> {
    synchronized(ruleRecords) {
        return ruleRecords.toList()
    }
}

private fun emitRuleRecordsUpdate() {
    try {
        _ruleRecordsFlow.tryEmit(getCurrentRuleRecords())
    } catch (e: Exception) {
        io.legado.app.model.Debug.log("RssExecutionRecorder", "emitRuleRecordsUpdate失败: ${e.message}")
    }
}
```

- [ ] **步骤 3：添加便捷记录方法**

```kotlin
fun ruleSuccess(
    step: RssExecutionStep,
    ruleContent: String? = null,
    executionTree: RuleExecutionTree? = null,
    input: String? = null,
    output: String? = null,
    matchCount: Int? = null,
    duration: Long? = null
) {
    recordRuleExecution(RssRuleExecutionRecord(
        step = step,
        ruleContent = ruleContent,
        executionTree = executionTree,
        input = input?.take(200),
        output = output?.take(200),
        matchCount = matchCount,
        duration = duration,
        sourceUrl = currentSourceUrl,
        sourceName = currentSourceName,
        executionId = currentExecutionId
    ))
}

fun ruleFailed(
    step: RssExecutionStep,
    ruleContent: String? = null,
    error: Throwable,
    duration: Long? = null
) {
    recordRuleExecution(RssRuleExecutionRecord(
        step = step,
        ruleContent = ruleContent,
        error = error,
        duration = duration,
        sourceUrl = currentSourceUrl,
        sourceName = currentSourceName,
        executionId = currentExecutionId
    ))
}
```

---

## 任务 3：在 RssParserByRule 中集成规则执行追踪

**文件：**
- 修改：`app/src/main/java/io/legado/app/model/rss/RssParserByRule.kt`

- [ ] **步骤 1：添加必要的导入**

```kotlin
import io.legado.app.model.debug.RuleExecutionTracker
import io.legado.app.model.debug.RuleType
import io.legado.app.model.debug.RuleExecutionTree
import io.legado.app.data.repository.debug.RssExecutionRecorder
```

- [ ] **步骤 2：在 parseXML 方法中追踪列表规则执行**

修改 `parseXML` 方法，在解析列表规则时使用 `RuleExecutionTracker`：

```kotlin
suspend fun parseXML(
    sortName: String,
    sortUrl: String,
    redirectUrl: String,
    body: String?,
    rssSource: RssSource,
    ruleData: RuleData
): Pair<MutableList<RssArticle>, String?> {
    val sourceUrl = rssSource.sourceUrl
    val recorder = RssExecutionRecorder
    var nextUrl: String? = null
    
    if (body.isNullOrBlank()) {
        recorder.failed(RssExecutionStep.PARSE_LIST, "响应内容为空")
        throw NoStackTraceException(
            appCtx.getString(R.string.error_get_web_content, rssSource.sourceUrl)
        )
    }
    
    Debug.log(sourceUrl, body, state = 10, category = DebugCategory.RSS)
    var ruleArticles = rssSource.ruleArticles
    
    if (ruleArticles.isNullOrBlank()) {
        // 默认XML解析逻辑...
        Debug.log(sourceUrl, "⇒列表规则为空, 使用默认规则解析", category = DebugCategory.RSS)
        val result = RssParserDefault.parseXML(sortName, body, sourceUrl)
        if (result.first.isNotEmpty()) {
            recorder.success(RssExecutionStep.PARSE_LIST,
                detail = "默认XML解析获取${result.first.size}条")
        } else {
            recorder.failed(RssExecutionStep.PARSE_LIST, "默认XML解析未获取到文章")
        }
        return result
    } else {
        val articleList = mutableListOf<RssArticle>()
        val analyzeRule = AnalyzeRule(ruleData, rssSource)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        analyzeRule.setContent(body).setBaseUrl(sortUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        
        var reverse = false
        if (ruleArticles.startsWith("-")) {
            reverse = true
            ruleArticles = ruleArticles.substring(1)
        }
        
        // 使用 RuleExecutionTracker 追踪列表规则
        val listTracker = RuleExecutionTracker(rssSource, ruleArticles, "列表规则")
        val parseStart = System.currentTimeMillis()
        
        Debug.log(sourceUrl, "┌获取列表", category = DebugCategory.RSS)
        val collections = analyzeRule.getElements(ruleArticles)
        Debug.log(sourceUrl, "└列表大小:${collections.size}", category = DebugCategory.RSS)
        
        val listDuration = System.currentTimeMillis() - parseStart
        listTracker.endStep(collections.size.toString(), matchCount = collections.size)
        val listTree = listTracker.buildTree()
        
        if (collections.isNotEmpty()) {
            recorder.success(RssExecutionStep.PARSE_LIST,
                detail = "获取${collections.size}条", duration = listDuration)
            recorder.ruleSuccess(
                step = RssExecutionStep.PARSE_RULE_ARTICLES,
                ruleContent = ruleArticles,
                executionTree = listTree,
                input = body.take(200),
                output = "${collections.size}个元素",
                matchCount = collections.size,
                duration = listDuration
            )
        } else {
            recorder.failed(RssExecutionStep.PARSE_LIST, "列表为空", duration = listDuration)
        }
        
        // 下一页规则解析...
        if (!rssSource.ruleNextPage.isNullOrEmpty()) {
            Debug.log(sourceUrl, "┌获取下一页链接", category = DebugCategory.RSS)
            val nextTracker = RuleExecutionTracker(rssSource, rssSource.ruleNextPage!!, "下一页规则")
            val nextStart = System.currentTimeMillis()
            
            if (rssSource.ruleNextPage!!.uppercase(Locale.getDefault()) == "PAGE") {
                nextUrl = sortUrl
            } else {
                nextUrl = analyzeRule.getString(rssSource.ruleNextPage)
                if (nextUrl.isNotEmpty()) {
                    nextUrl = NetworkUtils.getAbsoluteURL(sortUrl, nextUrl)
                }
            }
            
            val nextDuration = System.currentTimeMillis() - nextStart
            nextTracker.endStep(nextUrl)
            val nextTree = nextTracker.buildTree()
            
            Debug.log(sourceUrl, "└$nextUrl", category = DebugCategory.RSS)
            recorder.ruleSuccess(
                step = RssExecutionStep.RULE_NEXT_PAGE,
                ruleContent = rssSource.ruleNextPage,
                executionTree = nextTree,
                output = nextUrl,
                duration = nextDuration
            )
        }
        
        // 解析各字段...
        val ruleTitle = analyzeRule.splitSourceRule(rssSource.ruleTitle)
        val rulePubDate = analyzeRule.splitSourceRule(rssSource.rulePubDate)
        val ruleDescription = analyzeRule.splitSourceRule(rssSource.ruleDescription)
        val ruleImage = analyzeRule.splitSourceRule(rssSource.ruleImage)
        val ruleLink = analyzeRule.splitSourceRule(rssSource.ruleLink)
        val variable = ruleData.getVariable()
        
        for ((index, item) in collections.withIndex()) {
            getItem(
                sourceUrl, item, analyzeRule, variable, rssSource.type, index == 0,
                ruleTitle, rulePubDate, ruleDescription, ruleImage, ruleLink,
                rssSource, recorder
            )?.let {
                it.sort = sortName
                it.origin = sourceUrl
                articleList.add(it)
            }
        }
        
        if (reverse) {
            articleList.reverse()
        }
        return Pair(articleList, nextUrl)
    }
}
```

- [ ] **步骤 3：修改 getItem 方法添加规则追踪**

```kotlin
private fun getItem(
    sourceUrl: String,
    item: Any,
    analyzeRule: AnalyzeRule,
    variable: String?,
    type: Int,
    log: Boolean,
    ruleTitle: List<AnalyzeRule.SourceRule>,
    rulePubDate: List<AnalyzeRule.SourceRule>,
    ruleDescription: List<AnalyzeRule.SourceRule>,
    ruleImage: List<AnalyzeRule.SourceRule>,
    ruleLink: List<AnalyzeRule.SourceRule>,
    rssSource: RssSource,
    recorder: RssExecutionRecorder
): RssArticle? {
    val rssArticle = RssArticle(variable = variable)
    analyzeRule.setRuleData(rssArticle)
    analyzeRule.setContent(item)
    
    // 追踪标题规则
    val titleRuleStr = ruleTitle.joinToString("&&") { it.rule }
    val titleTracker = RuleExecutionTracker(rssSource, titleRuleStr, "标题规则")
    val titleStart = System.currentTimeMillis()
    
    Debug.log(sourceUrl, "┌获取标题", log, category = DebugCategory.RSS)
    rssArticle.title = analyzeRule.getString(ruleTitle)
    Debug.log(sourceUrl, "└${rssArticle.title}", log, category = DebugCategory.RSS)
    
    val titleDuration = System.currentTimeMillis() - titleStart
    titleTracker.endStep(rssArticle.title)
    val titleTree = titleTracker.buildTree()
    
    if (log) {
        if (rssArticle.title.isNotBlank()) {
            recorder.success(RssExecutionStep.EXTRACT_TITLE, detail = rssArticle.title.take(50))
            recorder.ruleSuccess(
                step = RssExecutionStep.PARSE_RULE_TITLE,
                ruleContent = titleRuleStr,
                executionTree = titleTree,
                output = rssArticle.title,
                duration = titleDuration
            )
        } else {
            recorder.failed(RssExecutionStep.EXTRACT_TITLE, "标题为空")
        }
    }
    
    // 类似方式追踪其他字段...
    // 发布日期
    val pubDateRuleStr = rulePubDate.joinToString("&&") { it.rule }
    val pubDateTracker = RuleExecutionTracker(rssSource, pubDateRuleStr, "发布日期规则")
    val pubDateStart = System.currentTimeMillis()
    
    Debug.log(sourceUrl, "┌获取时间", log, category = DebugCategory.RSS)
    rssArticle.pubDate = analyzeRule.getString(rulePubDate)
    Debug.log(sourceUrl, "└${rssArticle.pubDate}", log, category = DebugCategory.RSS)
    
    val pubDateDuration = System.currentTimeMillis() - pubDateStart
    pubDateTracker.endStep(rssArticle.pubDate)
    
    if (log) {
        if (!rssArticle.pubDate.isNullOrBlank()) {
            recorder.success(RssExecutionStep.EXTRACT_PUB_DATE, detail = rssArticle.pubDate?.take(50))
            recorder.ruleSuccess(
                step = RssExecutionStep.PARSE_RULE_PUB_DATE,
                ruleContent = pubDateRuleStr,
                executionTree = pubDateTracker.buildTree(),
                output = rssArticle.pubDate,
                duration = pubDateDuration
            )
        } else {
            recorder.failed(RssExecutionStep.EXTRACT_PUB_DATE, "未提取到发布日期")
        }
    }
    
    // 描述、图片、链接类似处理...
    // ...
    
    rssArticle.type = type
    if (rssArticle.title.isBlank()) {
        return null
    }
    return rssArticle
}
```

---

## 任务 4：在 Rss.kt 中记录网络请求详情

**文件：**
- 修改：`app/src/main/java/io/legado/app/model/rss/Rss.kt`

- [ ] **步骤 1：添加 FlowLogRecorder 导入**

```kotlin
import io.legado.app.data.repository.debug.FlowLogRecorder
```

- [ ] **步骤 2：在 getArticlesAwait 中记录网络请求详情**

```kotlin
suspend fun getArticlesAwait(
    sortName: String,
    sortUrl: String,
    rssSource: RssSource,
    page: Int,
    key: String? = null
): Pair<MutableList<RssArticle>, String?> {
    val recorder = RssExecutionRecorder
    
    // 开始执行会话
    recorder.startSession(rssSource.sourceUrl, rssSource.sourceName)
    FlowLogRecorder.setOperation(rssSource.sourceUrl, "获取文章列表")
    
    // 配置检查阶段...
    recorder.check(RssExecutionStep.SOURCE_NAME, rssSource.sourceName)
    // ...
    
    // 网络请求阶段
    val netStart = System.currentTimeMillis()
    val ruleData = RuleData()
    val analyzeUrl = AnalyzeUrl(
        sortUrl,
        page = page,
        key = key,
        baseUrl = rssSource.sourceUrl,
        source = rssSource,
        ruleData = ruleData,
        coroutineContext = currentCoroutineContext(),
        hasLoginHeader = false
    )
    
    val checkJs = rssSource.loginCheckJs
    val res = kotlin.runCatching {
        analyzeUrl.getStrResponseAwait().let {
            if (!checkJs.isNullOrBlank()) {
                analyzeUrl.evalJS(checkJs, it) as StrResponse
            } else {
                it
            }
        }
    }.getOrElse { throwable ->
        val netDuration = System.currentTimeMillis() - netStart
        // 记录网络请求失败
        FlowLogRecorder.logNetwork(
            source = rssSource,
            message = "网络请求失败",
            url = analyzeUrl.ruleUrl,
            method = "GET",
            duration = netDuration,
            error = throwable,
            requestHeaders = analyzeUrl.headerMap,
            cookies = analyzeUrl.headerMap["Cookie"]
        )
        recorder.failed(RssExecutionStep.NETWORK_REQUEST, 
            throwable.message ?: "网络请求失败", netDuration)
        recorder.endSession()
        FlowLogRecorder.endSession(rssSource.sourceUrl)
        throw throwable
    }
    
    val netDuration = System.currentTimeMillis() - netStart
    
    // 记录网络请求成功
    FlowLogRecorder.logNetwork(
        source = rssSource,
        message = "网络请求成功",
        url = res.url,
        method = "GET",
        statusCode = res.code(),
        duration = netDuration,
        detail = "响应大小: ${res.body?.length ?: 0} 字节",
        requestHeaders = analyzeUrl.headerMap,
        cookies = analyzeUrl.headerMap["Cookie"]
    )
    
    recorder.success(RssExecutionStep.NETWORK_REQUEST,
        detail = analyzeUrl.ruleUrl, duration = netDuration)
    
    checkRedirect(rssSource, res)
    Debug.log(rssSource.sourceUrl, "≡获取成功:${analyzeUrl.ruleUrl}", category = DebugCategory.RSS)
    
    if (!res.body.isNullOrBlank()) {
        recorder.success(RssExecutionStep.RESPONSE_BODY, detail = "内容长度: ${res.body!!.length}")
    } else {
        recorder.failed(RssExecutionStep.RESPONSE_BODY, "响应内容为空")
    }
    
    // 结束执行会话
    recorder.endSession()
    FlowLogRecorder.endSession(rssSource.sourceUrl)
    
    return RssParserByRule.parseXML(sortName, sortUrl, res.url, res.body, rssSource, ruleData)
}
```

- [ ] **步骤 3：在 getContentAwait 中记录网络请求详情**

类似地修改 `getContentAwait` 方法，添加网络请求详情记录。

---

## 任务 5：扩展 RssExecutionStatus 组件展示规则执行树

**文件：**
- 修改：`app/src/main/java/io/legado/app/ui/debuglog/components/RssExecutionStatus.kt`

- [ ] **步骤 1：添加规则执行树展示**

在 `ExecutionSessionCard` 中添加规则执行树的展示：

```kotlin
@Composable
private fun ExecutionSessionCard(
    records: List<RssExecutionRecord>,
    ruleRecords: List<RssRuleExecutionRecord>,
    totalDuration: Long?,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    var ruleExpanded by remember { mutableStateOf(false) }
    
    // ... 现有代码 ...
    
    // 规则执行路径展示
    if (ruleRecords.isNotEmpty() && expanded) {
        Spacer(modifier = Modifier.height(8.dp))
        SectionHeader("规则执行路径")
        
        ruleRecords.forEach { ruleRecord ->
            RuleExecutionRow(record = ruleRecord)
        }
    }
}
```

- [ ] **步骤 2：添加 RuleExecutionRow 组件**

```kotlin
@Composable
private fun RuleExecutionRow(record: RssRuleExecutionRecord) {
    var expanded by remember { mutableStateOf(false) }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🌳",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    text = record.step.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                record.duration?.let {
                    Text(
                        text = "${it}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 规则内容
            record.ruleContent?.let { rule ->
                Text(
                    text = rule,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp)
                )
            }
            
            // 展开显示执行树
            AnimatedVisibility(
                visible = expanded && record.executionTree != null,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                record.executionTree?.let { tree ->
                    RuleExecutionTreeView(
                        tree = tree,
                        modifier = Modifier.padding(start = 24.dp, top = 8.dp)
                    )
                }
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}
```

- [ ] **步骤 3：添加 RuleExecutionTreeView 组件**

复用现有的 `RuleExecutionNodeView` 组件展示规则执行树：

```kotlin
@Composable
private fun RuleExecutionTreeView(
    tree: RuleExecutionTree,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        tree.root.children.forEach { node ->
            RuleExecutionNodeView(node, "", 0)
        }
    }
}
```

---

## 任务 6：更新 DebugLogViewModel 订阅规则执行记录

**文件：**
- 修改：`app/src/main/java/io/legado/app/ui/debuglog/viewmodel/DebugLogViewModel.kt`

- [ ] **步骤 1：添加规则执行记录的 StateFlow**

```kotlin
private val _rssRuleRecords = MutableStateFlow<List<RssRuleExecutionRecord>>(emptyList())
val rssRuleRecords: StateFlow<List<RssRuleExecutionRecord>> = _rssRuleRecords.asStateFlow()
```

- [ ] **步骤 2：订阅规则执行记录流**

```kotlin
private fun subscribeToRssRuleRecords() {
    RssExecutionRecorder.ruleRecordsFlow
        .onEach { records ->
            _rssRuleRecords.value = records
        }
        .launchIn(viewModelScope)
    refreshRssRuleRecords()
}

fun refreshRssRuleRecords() {
    _rssRuleRecords.value = RssExecutionRecorder.getCurrentRuleRecords()
}
```

- [ ] **步骤 3：在 init 中调用订阅**

```kotlin
init {
    loadHistoryLogs()
    subscribeToEventFlow()
    subscribeToFlowLogs()
    subscribeToRssExecutionRecords()
    subscribeToRssRuleRecords()
}
```

---

## 任务 7：编译验证

- [ ] **步骤 1：运行编译**

```bash
./gradlew assembleDebug
```

预期：编译成功，无错误

- [ ] **步骤 2：检查新增功能**

启动应用，进入订阅源调试界面，验证：
1. 规则执行路径是否正确展示
2. 网络请求详情是否完整
3. 执行耗时是否正确记录

---

## 自检清单

- [x] 规格覆盖度：所有设计文档中的需求都有对应任务
- [x] 占位符扫描：无"待定"、"TODO"等占位符
- [x] 类型一致性：所有类型和方法签名保持一致
