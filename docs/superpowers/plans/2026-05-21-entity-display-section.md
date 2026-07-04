# FlowLogDetailDialog 实体显示 section 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。

**目标：** 在 FlowLogDetailDialog 中「数据流转」section 旁边新增「实体显示」section，展示当前流程日志关联的 Book 和 BookChapter 实体。

**架构：** FlowLogItem 新增 book/bookChapter 字段，FlowLogRecorder 日志方法扩展参数，规则引擎调用点传入实体，FlowLogDetailDialog 新增 section 展示。

**技术栈：** Kotlin data class、FlowLogRecorder singleton、Compose Material3

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `model/debug/FlowLogItem.kt` | 修改 | 新增 `book: Book?`、`bookChapter: BookChapter?` 字段 |
| `data/repository/debug/FlowLogRecorder.kt` | 修改 | 6 个日志方法签名扩展 |
| `model/webBook/BookInfo.kt` | 修改 | 1 处 logStageDataFlow + 17 处 logExtract 传入 book |
| `model/webBook/BookContent.kt` | 修改 | 1 处 logStageDataFlow + 2 处 logExtract + 2 处 logReplace 传入 book + bookChapter |
| `model/webBook/BookChapterList.kt` | 修改 | 2 处 logStageDataFlow + 3 处 logExtract 传入 book |
| `model/analyzeRule/AnalyzeRule.kt` | 修改 | 4 处 logRuleExecution + 2 处 logJsContext + 4 处 logReplace 传入 book + chapter |
| `ui/debuglog/components/FlowLogDetailDialog.kt` | 修改 | 新增「实体显示」section + Book/BookChapter 视图组件 |

---

### 任务 1：FlowLogItem 新增字段

**文件：** `app/src/main/java/io/legado/app/model/debug/FlowLogItem.kt`

- [ ] **步骤 1：添加 import**

```kotlin
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
```

- [ ] **步骤 2：在 dataFlow 字段之后添加**

```kotlin
/** 当前处理的书籍对象 */
val book: Book? = null,
/** 当前处理的章节对象 */
val bookChapter: BookChapter? = null
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/io/legado/app/model/debug/FlowLogItem.kt
git commit -m "feat: FlowLogItem 新增 book 和 bookChapter 字段"
```

---

### 任务 2：FlowLogRecorder 日志方法扩展参数

**文件：** `app/src/main/java/io/legado/app/data/repository/debug/FlowLogRecorder.kt`

- [ ] **步骤 1：添加 import**

```kotlin
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
```

- [ ] **步骤 2：修改 `log()` 方法签名（约第 552 行）**

在 `cookies` 参数之后添加：

```kotlin
/** 当前处理的书籍 */
book: Book? = null,
/** 当前处理的章节 */
bookChapter: BookChapter? = null
```

在创建 FlowLogItem 处（约第 586 行）添加这两个字段：

```kotlin
val item = FlowLogItem(
    // ... 现有参数 ...
    requestHeaders = requestHeaders,
    cookies = cookies,
    book = book,
    bookChapter = bookChapter
)
```

- [ ] **步骤 3：修改 `logExtract()` 方法签名（约第 307 行）**

添加参数并传递给 `log()`：

```kotlin
fun logExtract(
    source: BaseSource?,
    message: String,
    rule: String? = null,
    result: String? = null,
    originalValue: String? = null,
    duration: Long? = null,
    detail: String? = null,
    error: Throwable? = null,
    book: Book? = null,
    bookChapter: BookChapter? = null
) {
    val sourceUrl = source?.getKey()
    log(
        // ... 现有参数 ...
        error = error,
        book = book,
        bookChapter = bookChapter
    )
}
```

- [ ] **步骤 4：修改 `logReplace()` 方法签名（约第 345 行）**

同上模式，添加 `book: Book? = null`、`bookChapter: BookChapter? = null` 并传递给 `log()`。

- [ ] **步骤 5：修改 `logRuleExecution()` 方法签名（约第 195 行）**

添加 `book: Book? = null`、`bookChapter: BookChapter? = null`，在创建 FlowLogItem 时传入。

- [ ] **步骤 6：修改 `logJsExecution()` 方法签名（约第 239 行）**

添加 `book: Book? = null`、`bookChapter: BookChapter? = null`，在创建 FlowLogItem 时传入。

- [ ] **步骤 7：修改 `logJsContext()` 方法签名（约第 284 行）**

添加 `book: Book? = null`、`bookChapter: BookChapter? = null`，传递给 `logJsExecution()`。

- [ ] **步骤 8：修改 `logStageDataFlow()` 方法签名（约第 476 行）**

添加 `book: Book? = null`、`bookChapter: BookChapter? = null`，在创建 FlowLogItem 时传入。

- [ ] **步骤 9：Commit**

```bash
git add app/src/main/java/io/legado/app/data/repository/debug/FlowLogRecorder.kt
git commit -m "feat: FlowLogRecorder 日志方法扩展 book/bookChapter 参数"
```

---

### 任务 3：调用点传入实体 — BookInfo + BookContent + BookChapterList

- [ ] **步骤 1：修改 BookInfo.kt（约 17 处 logExtract + 1 处 logStageDataFlow）**

所有 `FlowLogRecorder.logExtract(...)` 调用添加 `book = book`：
```kotlin
FlowLogRecorder.logExtract(
    source = source,
    message = "...",
    // ... 现有参数 ...
    book = book  // 新增
)
```

`logStageDataFlow` 调用（约第 460 行）同样添加 `book = book`。

- [ ] **步骤 2：修改 BookContent.kt（约 2 处 logExtract + 2 处 logReplace + 1 处 logStageDataFlow）**

所有调用添加 `book = book, bookChapter = bookChapter`。

- [ ] **步骤 3：修改 BookChapterList.kt（约 3 处 logExtract + 2 处 logStageDataFlow）**

所有调用添加 `book = book`（BookChapterList 没有 bookChapter 参数）。

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/io/legado/app/model/webBook/BookInfo.kt \
        app/src/main/java/io/legado/app/model/webBook/BookContent.kt \
        app/src/main/java/io/legado/app/model/webBook/BookChapterList.kt
git commit -m "feat: webBook 调用点传入 book/bookChapter 实体"
```

---

### 任务 4：调用点传入实体 — AnalyzeRule

**文件：** `app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt`

AnalyzeRule 中有：
- `private val book get() = ruleData as? BaseBook` — 返回 BaseBook?
- `private var chapter: BookChapter? = null`

需要将 `book` 转为 `Book?`（通过 `as? Book`）传入日志方法。

- [ ] **步骤 1：修改 4 处 logRuleExecution 调用（约第 396, 409, 484, 547 行）**

添加 `book = book as? Book, bookChapter = chapter`。

- [ ] **步骤 2：修改 2 处 logJsContext 调用（约第 1071, 1083 行）**

添加 `book = book as? Book, bookChapter = chapter`。

- [ ] **步骤 3：修改 4 处 logReplace 调用（约第 604, 630, 1018, 1092 行）**

添加 `book = book as? Book, bookChapter = chapter`。

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt
git commit -m "feat: AnalyzeRule 调用点传入 book/chapter 实体"
```

---

### 任务 5：FlowLogDetailDialog 新增实体显示 section

**文件：** `app/src/main/java/io/legado/app/ui/debuglog/components/FlowLogDetailDialog.kt`

- [ ] **步骤 1：添加 import**

```kotlin
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
```

- [ ] **步骤 2：在「数据流转」section 之后（约第 280 行后）添加**

```kotlin
if (log.book != null || log.bookChapter != null) {
    Spacer(Modifier.height(12.dp))
    DetailSection(title = "实体显示", searchQuery = searchQuery) {
        log.book?.let { book ->
            Text(
                text = "Book（书籍）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            BookEntityView(book, searchQuery)
            if (log.bookChapter != null) {
                Spacer(Modifier.height(8.dp))
            }
        }
        log.bookChapter?.let { chapter ->
            Text(
                text = "BookChapter（章节）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            BookChapterEntityView(chapter, searchQuery)
        }
    }
}
```

- [ ] **步骤 3：在文件末尾添加 BookEntityView 和 BookChapterEntityView composable**

```kotlin
@Composable
private fun BookEntityView(book: Book, searchQuery: String) {
    DetailRow("书名", book.name, searchQuery)
    DetailRow("作者", book.author, searchQuery)
    DetailRow("bookUrl", book.bookUrl, searchQuery)
    DetailRow("origin", book.origin, searchQuery)
    book.originName.takeIf { it.isNotBlank() }?.let {
        DetailRow("originName", it, searchQuery)
    }
    book.coverUrl.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("coverUrl", it, searchQuery)
    }
    book.kind.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("kind", it, searchQuery)
    }
    book.intro.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("intro", it, searchQuery)
    }
    DetailRow("type", book.type.toString(), searchQuery)
    DetailRow("group", book.group.toString(), searchQuery)
    book.durChapterTitle.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("durChapterTitle", it, searchQuery)
    }
    DetailRow("durChapterIndex", book.durChapterIndex.toString(), searchQuery)
    DetailRow("totalChapterNum", book.totalChapterNum.toString(), searchQuery)
    book.latestChapterTitle.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("latestChapterTitle", it, searchQuery)
    }
    book.wordCount.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("wordCount", it, searchQuery)
    }
}

@Composable
private fun BookChapterEntityView(chapter: BookChapter, searchQuery: String) {
    DetailRow("title", chapter.title, searchQuery)
    DetailRow("url", chapter.url, searchQuery)
    DetailRow("index", chapter.index.toString(), searchQuery)
    DetailRow("bookUrl", chapter.bookUrl, searchQuery)
    DetailRow("isVolume", chapter.isVolume.toString(), searchQuery)
    DetailRow("isVip", chapter.isVip.toString(), searchQuery)
    DetailRow("isPay", chapter.isPay.toString(), searchQuery)
    chapter.tag.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("tag", it, searchQuery)
    }
    chapter.wordCount.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("wordCount", it, searchQuery)
    }
    chapter.resourceUrl.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("resourceUrl", it, searchQuery)
    }
    chapter.imgUrl.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("imgUrl", it, searchQuery)
    }
}
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/debuglog/components/FlowLogDetailDialog.kt
git commit -m "feat: FlowLogDetailDialog 新增实体显示 section"
```

---

### 任务 6：编译验证

- [ ] **步骤 1：编译**

```bash
./gradlew assembleAppMaxDebug
```

预期：BUILD SUCCESSFUL

- [ ] **步骤 2：Commit（如有修复）**

```bash
git add -A
git commit -m "fix: 实体显示编译修复"
```
