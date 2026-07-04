# FlowLogDetailDialog 实体显示 section 设计规格

## 概述

在 `FlowLogDetailDialog` 的「数据流转」section 旁边新增「实体显示」section，展示当前流程日志关联的 Book 和 BookChapter 实体对象。

## 目标

- 让开发者在查看流程日志详情时，直接看到当时处理的 Book 和 BookChapter 实体
- 无需跳转到其他页面即可查看实体的完整字段值

## 数据流

```
规则执行引擎（BookInfo/BookContent/BookChapterList/AnalyzeRule）
  → FlowLogRecorder.logStageDataFlow/logRuleExecution/logJsContext/log()
    → FlowLogItem 新增 book: Book?、bookChapter: BookChapter?
      → FlowLogDetailDialog 检测非空字段
        → 实体显示 section 展示 Book/BookChapter 字段
```

## 改动清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `model/debug/FlowLogItem.kt` | 修改 | 新增 `book: Book?`、`bookChapter: BookChapter?` 字段 |
| `data/repository/debug/FlowLogRecorder.kt` | 修改 | 日志方法签名扩展，传入 book/bookChapter |
| `model/webBook/BookInfo.kt` | 修改 | 调用 logStageDataFlow/logExtract 时传入 book |
| `model/webBook/BookContent.kt` | 修改 | 调用 logStageDataFlow/logExtract/logReplace 时传入 book + bookChapter |
| `model/webBook/BookChapterList.kt` | 修改 | 调用 logStageDataFlow/logExtract 时传入 book |
| `model/analyzeRule/AnalyzeRule.kt` | 修改 | 调用 logRuleExecution/logJsContext/logReplace 时传入 book + chapter |
| `ui/debuglog/components/FlowLogDetailDialog.kt` | 修改 | 新增「实体显示」section + Book/BookChapter 视图组件 |

## UI 设计

在 FlowLogDetailDialog 中，「数据流转」section 之后新增：

```kotlin
if (log.book != null || log.bookChapter != null) {
    DetailSection(title = "实体显示", searchQuery = searchQuery) {
        log.book?.let { BookEntityView(it, searchQuery) }
        log.bookChapter?.let { BookChapterEntityView(it, searchQuery) }
    }
}
```

BookEntityView 展示关键字段：name, author, bookUrl, origin, originName, coverUrl, intro, type, group, durChapterTitle, durChapterIndex, totalChapterNum 等。

BookChapterEntityView 展示关键字段：title, url, index, bookUrl, isVolume, isVip, isPay, tag, wordCount 等。
