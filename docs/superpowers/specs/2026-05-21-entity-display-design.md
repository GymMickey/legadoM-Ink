# 实体显示功能设计规格

## 概述

在调试日志界面的「书源」分类下新增「实体」子分类 tab，用户选择一个书源后，查看该书源关联的所有规则实体（SearchRule、ExploreRule、BookInfoRule、TocRule、ContentRule）以及 BookSource 自身的基础字段。

## 目标

- 让开发者在调试过程中快速查看书源的规则配置，无需跳转到书源编辑页面
- 以结构化的 key-value 形式展示每个实体的所有非空字段
- 复用现有 UI 组件风格（DetailRow、DetailSection）

## 不在范围内

- Book 和 BookChapter 实体（它们是用户数据，非书源组成部分）
- 实体的编辑功能（仅查看）
- 实体的 JSON 导出

## 用户流程

```
调试日志面板
  → 选择「书源」分类 tab
    → 选择「实体」子分类
      → 顶部显示书源下拉选择器
        → 选择一个书源
          → 下方显示可折叠的实体卡片列表
```

## UI 设计

### 书源选择器

使用 Compose `ExposedDropdownMenuBox`，显示书源名称和分组。选中后加载完整的 BookSource 对象。

### 实体卡片列表

使用 `LazyColumn`，每个实体类型一张可折叠卡片。默认全部展开。

卡片结构：
- 标题栏：实体类型名称 + 字段数量 badge
- 内容区：所有非 null 字段的 key-value 行

实体类型及对应字段：

| 实体 | 字段数 | 字段来源 |
|------|--------|---------|
| BookSource | ~20 | 基础字段（不含嵌套 rule） |
| SearchRule | ~11 | checkKeyWord + BookListRule 接口字段 |
| ExploreRule | ~10 | BookListRule 接口字段 |
| BookInfoRule | ~12 | 书籍信息规则字段 |
| TocRule | ~10 | 目录规则字段 |
| ContentRule | ~11 | 正文规则字段 |

### 字段展示

复用 `DetailRow(label, value)` 模式：
- label 宽度固定 100dp
- value 超过 80 字符可展开/收起
- 长值支持水平滚动

## 数据流

```
Room DB (book_sources)
  → DebugLogViewModel.loadBookSources()
    → availableBookSources: StateFlow<List<BookSource>>
      → 用户选择书源
        → DebugLogViewModel.selectBookSource(url)
          → 从 availableBookSources 中取出完整 BookSource 对象
            → selectedBookSource: StateFlow<BookSource?>
              → EntityDisplay 读取并展示各嵌套实体
```

## 文件改动清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `model/debug/SourceSubCategory.kt` | 修改 | 新增 `ENTITY("实体")` |
| `ui/debuglog/viewmodel/DebugLogViewModel.kt` | 修改 | 新增书源列表状态、选中状态、加载逻辑 |
| `ui/debuglog/DebugLogScreen.kt` | 修改 | ENTITY 子分类分支 + 导入新组件 |
| `ui/debuglog/components/EntityDisplay.kt` | 新建 | 实体展示主组件（书源选择器 + 实体卡片列表） |

## 组件结构

```
EntityDisplay (新文件)
├── BookSourceSelector (ExposedDropdownMenuBox)
└── LazyColumn
    ├── EntityCard("BookSource", bookSource 基础字段)
    ├── EntityCard("SearchRule", bookSource.ruleSearch?.toFieldList())
    ├── EntityCard("ExploreRule", bookSource.ruleExplore?.toFieldList())
    ├── EntityCard("BookInfoRule", bookSource.ruleBookInfo?.toFieldList())
    ├── EntityCard("TocRule", bookSource.ruleToc?.toFieldList())
    └── EntityCard("ContentRule", bookSource.ruleContent?.toFieldList())
```

## 技术约束

- 不新增 ViewModel，逻辑放在现有 DebugLogViewModel
- 不新增 DAO，使用现有的书源查询方法
- 实体字段提取使用 Kotlin 反射或手动映射（优先手动映射，避免反射开销）
- UI 风格与 FlowLogDetailDialog 保持一致
