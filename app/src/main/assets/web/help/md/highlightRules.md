# 高亮规则说明

本项目包含两种高亮规则系统：**替换规则 (ReplaceRule)** 和 **样式高亮规则 (HighlightRule)**。两者功能定位不同，机制各异。

---

## 一、替换规则 (ReplaceRule)

### 1.1 功能定位

替换规则用于**在加载书籍内容时进行文本替换/净化**，是一种内容预处理机制。它会在书籍内容加载到阅读界面之前，对原始文本进行修改。

典型应用场景：
- 去除广告文字
- 修正错别字
- 替换特定格式文本
- 净化不良内容

### 1.2 数据结构

定义位置：[ReplaceRule.kt](app/src/main/java/io/legado/app/data/entities/ReplaceRule.kt)

```kotlin
@Entity(tableName = "replace_rules")
data class ReplaceRule(
    var id: Long,                    // 主键
    var name: String,                // 规则名称
    var group: String?,              // 分组
    var pattern: String,             // 匹配模式（要替换的内容）
    var replacement: String,         // 替换为的内容
    var scope: String?,              // 作用范围（书籍名或源名）
    var scopeTitle: Boolean,         // 是否作用于标题
    var scopeContent: Boolean,       // 是否作用于正文（默认 true）
    var excludeScope: String?,       // 排除范围
    var isEnabled: Boolean,          // 是否启用
    var isRegex: Boolean,            // 是否为正则表达式（默认 true）
    var timeoutMillisecond: Long,    // 超时时间（默认 3000ms）
    var order: Int                   // 排序
)
```

### 1.3 语法说明

#### 匹配模式 (pattern)

- **正则模式** (`isRegex = true`)：使用 Kotlin 正则表达式语法
  - 示例：`广告.*?结束` 匹配 "广告" 到 "结束" 之间的内容
  - 示例：`\\[.*?\\]` 匹配方括号内的内容

- **普通文本模式** (`isRegex = false`)：直接匹配文本
  - 示例：`错别字` 精确匹配该文本

#### 替换内容 (replacement)

- 可以是任意文本
- 正则模式下支持捕获组引用：`$1`、`$2` 等
- 留空表示删除匹配内容

#### 作用范围 (scope)

- 格式：`书籍名,书源名` 或单独指定
- 为空表示全局生效
- `excludeScope` 用于排除特定书籍/源

### 1.4 执行机制

执行位置：[ContentProcessor.kt](app/src/main/java/io/legado/app/help/book/ContentProcessor.kt)

执行流程：

```
书籍内容加载
    ↓
去除重复标题
    ↓
重新分段（可选）
    ↓
简繁转换（可选）
    ↓
【替换规则执行】← 按顺序遍历所有启用的规则
    ↓
返回处理后的内容
```

关键代码逻辑：

```kotlin
getContentReplaceRules().forEach { item ->
    val tmp = if (item.isRegex) {
        mContent.replace(item.regex, item.replacement, timeout)
    } else {
        mContent.replace(item.pattern, item.replacement)
    }
    if (mContent != tmp) {
        effectiveReplaceRules.add(item)  // 记录生效的规则
        mContent = tmp
    }
}
```

### 1.5 使用 usehtml 实现富文本高亮

替换规则不仅能替换纯文本，还能通过 `<usehtml>` 标签实现**富文本高亮**，这是普通替换规则的高级用法。

#### 1.5.1 机制说明

`<usehtml>` 是一种特殊的标记，内容会被解析为 HTML 并渲染：

1. **识别阶段**：`ContentProcessor` 在替换规则执行前，先提取 `<usehtml>...</usehtml>` 内容
2. **占位替换**：用占位符临时替换，避免被其他替换规则修改
3. **排版阶段**：`TextChapterLayout` 检测到 `<usehtml>` 后，调用 `setTypeHtml()` 方法
4. **HTML 解析**：使用 `HtmlCompat.parseAsHtml()` 解析 HTML 标签
5. **样式应用**：应用 Span 样式后渲染到页面

#### 1.5.2 支持的 HTML 标签

| 标签 | 说明 | 示例 |
|-----|------|------|
| `<b>` / `<strong>` | 粗体 | `<b>重点内容</b>` |
| `<i>` / `<em>` | 斜体 | `<i>斜体文字</i>` |
| `<u>` | 下划线 | `<u>下划线文字</u>` |
| `<s>` / `<del>` | 删除线 | `<s>删除内容</s>` |
| `<big>` | 大号字体 | `<big>大字</big>` |
| `<small>` | 小号字体 | `<small>小字</small>` |
| `<sub>` | 下标 | `H<sub>2</sub>O` |
| `<sup>` | 上标 | `x<sup>2</sup>` |
| `<font color="">` | 指定颜色 | `<font color="#FF0000">红色</font>` |
| `<a href="">` | 超链接 | `<a href="url">链接</a>` |
| `<img src="">` | 图片 | `<img src="图片地址">` |
| `<p>` | 段落 | `<p>段落内容</p>` |
| `<div>` | 块级容器 | `<div>内容</div>` |
| `<br>` | 换行 | `第一行<br>第二行` |
| `<hr>` | 分隔线 | `<hr>` |
| `<button>` | 按钮 | `<button>按钮名@onclick:js代码</button>` |

#### 1.5.3 使用示例

**示例 1：文字颜色高亮**

将对话内容替换为带颜色的 HTML：

```
规则名称：对话颜色高亮
匹配模式：(")([^"]+)(")
替换为：<usehtml><font color="#FF8C00">$1$2$3</font></usehtml>
```

**示例 2：重点内容加粗+颜色**

```
规则名称：重点强调
匹配模式：【([^】]+)】
替换为：<usehtml><b><font color="#DC143C">【$1】</font></b></usehtml>
```

**示例 3：书名号绿色+斜体**

```
规则名称：书名号样式
匹配模式：《([^》]+)》
替换为：<usehtml><i><font color="#228B22">《$1》</font></i></usehtml>
```

**示例 4：章节标题居中+加粗**

```
规则名称：章节标题样式
匹配模式：^第([0-9]+)章\s+(.+)$
替换为：<usehtml><p style="text-align:center"><b>第$1章 $2</b></p></usehtml>
```

**示例 5：分隔线替代**

```
规则名称：分隔符替换
匹配模式：^---+$
替换为：<usehtml><hr></usehtml>
```

**示例 6：可点击按钮**

```
规则名称：跳转按钮
匹配模式：\[跳转\]
替换为：<usehtml><button>点击跳转@onclick:java.toast("按钮被点击")</button></usehtml>
```

#### 1.5.4 注意事项

1. **必须启用特殊样式**：需要在设置中开启 `adaptSpecialStyle`（适配特殊样式）
2. **嵌套限制**：`<usehtml>` 标签不能嵌套
3. **性能影响**：HTML 解析比纯文本慢，不宜大量使用
4. **样式优先级**：HTML 样式会与高亮规则叠加，可能产生冲突
5. **按钮交互**：`<button>` 标签需要配合 `@onclick:` 指定点击执行的 JS 代码

### 1.6 存储方式

- 存储在 Room 数据库 `replace_rules` 表中
- 通过 `ReplaceRuleDao` 进行 CRUD 操作
- 支持导入/导出 JSON 格式

---

## 二、样式高亮规则 (HighlightRule)

### 2.1 功能定位

样式高亮规则用于**在阅读页面渲染时对文本进行样式高亮**，是一种视觉增强机制。它不修改原始内容，仅改变显示样式。

典型应用场景：
- 对话内容高亮（如引号内的文字）
- 书名号下划线（《书名》）
- 括号注释灰色显示
- 章节标题强调

### 2.2 数据结构

定义位置：[HighlightRule.kt](app/src/main/java/io/legado/app/ui/book/read/config/HighlightRule.kt)

```kotlin
data class HighlightRule(
    var id: String,                  // 唯一标识
    var name: String,                // 规则名称
    var pattern: String,             // 正则表达式模式
    var sampleText: String,          // 示例文本（用于预览）
    var group: String,               // 分组
    var targetScope: Int,            // 作用范围：0=全部, 1=标题, 2=正文
    var enabled: Boolean,            // 是否启用
    var textColor: Int?,             // 文字颜色（ARGB）
    var underlineMode: Int,          // 下划线模式
    var underlineColor: Int?,        // 下划线颜色
    var underlineWidth: Float,       // 下划线宽度
    var underlineOffset: Float,      // 下划线偏移
    var underlineSvgPath: String?,   // 自定义 SVG 路径
    var bgImage: String?,            // 背景图片路径
    var bgImageFit: Int,             // 背景图适配方式
    var bgImageScale: Float          // 背景图缩放
)
```

### 2.3 语法说明

#### 匹配模式 (pattern)

使用 Kotlin 正则表达式语法，常用模式示例：

| 规则名称 | 正则模式 | 匹配内容 |
|---------|---------|---------|
| 对话高亮 | `"[^"\n]{1,120}"|「[^」\n]{1,120}」` | 中英文引号内的对话 |
| 书名号 | `《[^》\n]{1,80}》` | 书名号内的内容 |
| 括号注释 | `（[^）\n]{1,80}）|\\([^()\\n]{1,80}\\)` | 中英文括号内的注释 |
| 章节标题 | `(?m)^\\s{0,2}第[0-9零一二三四五六七八九十]{1,12}[章节卷].*$` | 章节标题行 |
| 英文单词 | `\\b[A-Za-z]{2,}[A-Za-z0-9'-]*\\b` | 英文单词 |

#### 下划线模式 (underlineMode)

| 值 | 模式 | 说明 |
|---|------|------|
| 0 | 无下划线 | 默认值 |
| 1 | 实线下划线 | 普通实线 |
| 2 | 虚线下划线 | 虚线样式 |
| 3 | 波浪下划线 | 波浪线样式 |
| 4 | 双下划线 | 双实线 |
| 5 | 自定义 SVG | 使用 `underlineSvgPath` 绘制 |

#### 作用范围 (targetScope)

| 值 | 范围 | 说明 |
|---|------|------|
| 0 | TARGET_ALL | 作用于标题和正文 |
| 1 | TARGET_TITLE | 仅作用于标题 |
| 2 | TARGET_BODY | 仅作用于正文 |

#### 背景图适配 (bgImageFit)

| 值 | 模式 | 说明 |
|---|------|------|
| 0 | 平铺 | 默认平铺 |
| 1 | 拉伸 | 拉伸填充 |
| 2 | 裁剪 | 居中裁剪 |

### 2.4 执行机制

执行位置：[TextChapterLayout.kt](app/src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt)

执行流程：

```
章节内容排版
    ↓
编译正则表达式（启动时预编译）
    ↓
【高亮规则匹配】← 遍历所有启用的规则
    ↓
应用 Span 样式（ForegroundColorSpan、HighlightStyleSpan）
    ↓
渲染到页面
```

关键代码逻辑：

```kotlin
private fun applyHighlightRules(
    spannable: SpannableStringBuilder,
    isTitle: Boolean = false
): SpannableStringBuilder {
    compiledHighlightRules.forEach { compiled ->
        if (!compiled.rule.appliesTo(isTitle)) return@forEach
        applyRuleSpans(spannable, compiled.rule, compiled.regex)
    }
    return spannable
}

private fun applyRuleSpans(
    spannable: SpannableStringBuilder,
    rule: HighlightRule,
    regex: Regex
) {
    regex.findAll(spannable).forEach { match ->
        // 应用文字颜色
        rule.textColor?.let { color ->
            spannable.setSpan(ForegroundColorSpan(color), ...)
        }
        // 应用下划线/背景图样式
        if (rule.underlineMode != 0 || !rule.bgImage.isNullOrBlank()) {
            spannable.setSpan(HighlightStyleSpan(...), ...)
        }
    }
}
```

### 2.5 内置默认规则

系统预设了以下高亮规则：

| ID | 名称 | 默认启用 | 样式 |
|----|------|---------|------|
| dialog_default | 对话高亮 | ✓ | 橙色文字 |
| book_title_default | 书名号高亮 | ✓ | 绿色波浪下划线 |
| bracket_note_default | 括号标注高亮 | ✓ | 灰色文字 + 蓝色虚线下划线 |
| title_emphasis_default | 标题强调 | ✓ | 深灰文字 + 棕色双下划线 |
| thought_default | 心理活动 | ✗ | 紫色文字 + 紫色实线下划线 |
| narrator_default | 旁白说明 | ✗ | 灰色文字 |
| emphasis_default | 重点强调 | ✗ | 红色文字 + 红色实线下划线 |
| poetry_default | 诗词引用 | ✗ | 深青文字 + 深青波浪下划线 |
| ellipsis_default | 省略停顿 | ✗ | 灰色文字 |
| number_default | 数字金额 | ✗ | 蓝色文字 |
| english_default | 英文单词 | ✗ | 蓝色文字 |
| date_time_default | 时间日期 | ✗ | 青色文字 |

### 2.6 存储方式

- 存储在 SharedPreferences 中
- Key: `highlightRuleItems`
- 值为 JSON 数组格式
- 通过 `HighlightRuleStore` 进行读写操作

---

## 三、两种规则对比

| 特性 | 替换规则 (ReplaceRule) | 高亮规则 (HighlightRule) |
|------|----------------------|------------------------|
| **目的** | 修改文本内容 | 改变显示样式 |
| **执行时机** | 内容加载时 | 页面渲染时 |
| **是否修改原文** | 是 | 否 |
| **存储位置** | Room 数据库 | SharedPreferences |
| **正则支持** | 可选（可关闭） | 必须 |
| **作用范围** | 按书籍/源过滤 | 按标题/正文过滤 |
| **样式能力** | 无 | 颜色、下划线、背景图 |
| **可逆性** | 不可逆（已修改内容） | 可逆（仅样式） |
| **配置入口** | 替换净化设置 | 阅读设置 → 高亮规则 |

---

## 四、扩展开发指南

### 4.1 添加新的替换规则

1. 在 `ReplaceRuleActivity` 中添加规则
2. 规则会自动保存到数据库
3. 下次加载书籍时自动生效

### 4.2 添加新的高亮规则

1. 在 `HighlightRuleStore.createDefaultRules()` 中添加预设规则
2. 或在阅读界面通过高亮规则配置对话框添加
3. 规则会立即应用到当前阅读页面

### 4.3 自定义下划线样式

通过 `underlineSvgPath` 可以定义自定义 SVG 路径：

```kotlin
HighlightRule(
    name = "自定义下划线",
    pattern = "重点",
    underlineMode = 5,  // 自定义 SVG 模式
    underlineSvgPath = "M0,0 L10,0 L5,5 Z"  // 三角形路径
)
```

---

## 五、注意事项

1. **替换规则性能**：正则表达式可能超时，默认 3 秒超时限制
2. **高亮规则性能**：启动时预编译正则，避免运行时编译开销
3. **规则优先级**：替换规则按 `order` 排序执行；高亮规则按加载顺序应用
4. **作用范围冲突**：替换规则的 `scope` 和高亮规则的 `targetScope` 是独立的过滤条件
5. **时间顺序**：高亮规则是实时应用，是最晚的。比如用替换规则把小明替换成了小张，高亮规则只能找到小张，因为高亮规则是最后应用的，所以只能匹配替换后的文本。
6. **样式冲突**：如果高亮规则和替换规则同时匹配到同一文本，高亮规则的样式会覆盖替换规则的样式。
7. **推荐使用高亮规则**：高亮规则在阅读时更方便，因为它们是实时应用的，并且可以预览，而替换规则需要先加载完所有内容后再应用。替换规则就用替换规则来写，高亮规不在此处，在阅读界面的设置里。