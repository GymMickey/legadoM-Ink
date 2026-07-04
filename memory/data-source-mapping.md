---
name: data-source-mapping
description: Mapping of dashboard cards to their data sources and known issues
metadata:
  type: reference
---

# 仪表盘数据源映射

## 4 个卡片 + 数据源

| 卡片 | 数据源 | 注意事项 |
|------|--------|---------|
| 最近阅读卡片 | `BookDao.flowAll()` + `Book` entity 字段 | `ReadRecord` 不含 `coverUrl`/`bookUrl`/`totalChapterNum`，需 Book 表 join |
| 统计双卡 | 本数: `ReadRecordDao.count` / 时长: `ReadRecordDao.getTotalReadTime()` | ⚠️ 当前实现用 `shelfBooks.size` 而非 ReadRecord.count |
| 最近书籍横滑 | `BookDao.flowAll()` 按 `durChapterTime` 排序 | 同需要 Book 表获取封面 |
| WebDAV 备份卡 | `AppWebDav.isOk` + `Backup.backupLocked()` | 直接复用已有逻辑 |

## 关键 Entity 字段

- `ReadRecord`: deviceId, bookName, bookAuthor, readTime, lastRead, durChapterTitle, durChapterIndex
- `Book`: name, author, bookUrl, coverUrl, customCoverUrl, durChapterTime, durChapterIndex, durChapterTitle, totalChapterNum, origin, getDisplayCover()

## ReadRecordDao 可用查询

- `getTotalReadTime(): Flow<Long?>` (行 61)
- `getAllReadRecordsSortedByLastRead(): Flow<List<ReadRecord>>` (行 70)
- `count: Int` (行 76, 非 Flow)

**Why:** Phase 2 数据源分析的结论，避免重复调研。
**How to apply:** 后续涉及数据源修改时参考此表。
