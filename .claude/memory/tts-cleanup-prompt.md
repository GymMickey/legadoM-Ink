---
name: tts-dead-code-cleanup-prompt
description: TTS 朗读死代码清理完整提示词（不影响功能的分批清理方案）
metadata:
  type: reference
---

## TTS 朗读模块死代码清理（安全分批版）

### 原则

- 以下所有代码均无活跃调用者，删除后不影响任何功能
- 有活跃调用者的函数（如 `removePageAloudSpan()`）保留签名、清空函数体，确保编译通过
- `help/TTS.kt`、`HttpTTS.kt` entity、`read_aloud` 字符串、`channelIdReadAloud` 常量 — 不碰

---

### 第一批：独立死函数（零调用者，最安全）

#### 1. TextChapter.kt — getNeedReadAloud()

文件：`app/src/main/java/io/legado/app/ui/book/read/page/entities/TextChapter.kt`

搜索并删除整个 `getNeedReadAloud` 函数（大约 215-240 行，从 `fun getNeedReadAloud(` 到其 `}` 结束）。该函数只有删除的 BaseReadAloudService 调用过，零残留调用者。

#### 2. ReadView.kt — getReadAloudPos() + aloudStartSelect()

文件：`app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt`

删除两个函数：
- `fun getReadAloudPos(): Pair<Int, TextLine>? { return curPage.getReadAloudPos() }`（约 744-746 行）
- `suspend fun aloudStartSelect() { ... }`（约 718-731 行，整段到其 `}` 结束）

#### 3. PageView.kt — getReadAloudPos()

文件：`app/src/main/java/io/legado/app/ui/book/read/page/PageView.kt`

删除函数（约 488-489 行）：
```kotlin
fun getReadAloudPos(): Pair<Int, TextLine>? {
    return binding.contentTextView.getReadAloudPos()
}
```

#### 4. ContentTextView.kt — getReadAloudPos()

文件：`app/src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt`

删除 `fun getReadAloudPos()` 函数（约 553 行起，到其 `}` 结束）。

#### 5. TextPage.kt — upPageAloudSpan()

文件：`app/src/main/java/io/legado/app/ui/book/read/page/entities/TextPage.kt`

删除整个 `upPageAloudSpan` 函数（约 214-236 行，从 `fun upPageAloudSpan` 到其 `}`）。零调用者。

#### 6. ReadMenu.kt — 两个死接口方法

文件：`app/src/main/java/io/legado/app/ui/book/read/ReadMenu.kt`

在 CallBack 接口中删除两行（约 744 和 746 行）：
```kotlin
fun showReadAloudDialog()
fun onClickReadAloud()
```

#### 7. ReadBookActivity.kt — 两个空壳实现

文件：`app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt`

删除两行（约 1416-1417 行）：
```kotlin
override fun showReadAloudDialog() { /* TTS removed */ }
override fun onClickReadAloud() { /* TTS removed */ }
```

#### 8. DefaultData.kt — httpTTS 死属性

文件：`app/src/main/java/io/legado/app/help/DefaultData.kt`

删除第 6 行的 import（如果仅服务于 httpTTS）：
```kotlin
import io.legado.app.data.entities.HttpTTS
```

删除 `httpTTS` 属性（约 45-53 行）：
```kotlin
val httpTTS: List<HttpTTS> by lazy {
    val json = String(appCtx.assets.open("defaultData${File.separator}httpTTS.json").readBytes())
    HttpTTS.fromJsonArray(json).getOrElse { emptyList() }
}
```

#### 9. LocalConfig.kt — needUpHttpTTS 死属性

文件：`app/src/main/java/io/legado/app/help/config/LocalConfig.kt`

删除属性（约 112-113 行）：
```kotlin
val needUpHttpTTS: Boolean
    get() = !isLastVersion(6, "httpTtsVersion")
```

---

### 第二批：朗读高亮死代码（涉及最小化分支清理）

#### 10. TextLine.kt — isReadAloud 属性 + 条件分支

文件：`app/src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt`

**10a.** 删除 `isReadAloud` 属性（约 69-78 行）：
```kotlin
var isReadAloud: Boolean = false
    set(value) {
        if (field != value) { invalidate() }
        if (value) { textPage.hasReadAloudSpan = true }
        field = value
    }
```

**10b.** 约 225 行，将：
```kotlin
if (AppConfig.isEInkMode && (isReadAloud || searchResultColumnCount > 0)) {
```
改为：
```kotlin
if (AppConfig.isEInkMode && searchResultColumnCount > 0) {
```

**10c.** 约 253 行左右的 `textColor` getter，将：
```kotlin
val textColor = if (isReadAloud) {
    textPage.textColor.toArgb()
} else {
    // ...正常逻辑...
}
```
改为直接使用 else 分支：
```kotlin
val textColor = /* 正常逻辑 */ (即删除 if (isReadAloud) 分支，保留 else 分支内容，不加 if)
```

#### 11. TextColumn.kt — 去掉 isReadAloud 引用

文件：`app/src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt`

约 68 行，将：
```kotlin
val drawColor = if (textLine.isReadAloud || isSearchResult) {
```
改为：
```kotlin
val drawColor = if (isSearchResult) {
```

#### 12. TextHtmlColumn.kt — 去掉 isReadAloud 引用

文件：`app/src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt`

约 86 行，将：
```kotlin
color = if (textLine.isReadAloud || isSearchResult) {
```
改为：
```kotlin
color = if (isSearchResult) {
```

#### 13. TextPage.kt — hasReadAloudSpan + removePageAloudSpan 瘦身

文件：`app/src/main/java/io/legado/app/ui/book/read/page/entities/TextPage.kt`

**13a.** 删除 `hasReadAloudSpan` 字段（约 60 行）：
```kotlin
var hasReadAloudSpan = false
```

**13b.** 将 `removePageAloudSpan()` 函数体简化（该函数被 TextPageFactory 和 ReadBook 多次调用，保留签名确保编译通过）：

改前（约 199-208 行）：
```kotlin
fun removePageAloudSpan(): TextPage {
    if (!hasReadAloudSpan) {
        return this
    }
    hasReadAloudSpan = false
    for (i in textLines.indices) {
        textLines[i].isReadAloud = false
    }
    return this
}
```

改后：
```kotlin
fun removePageAloudSpan(): TextPage {
    return this
}
```

---

### 第三批：孤儿资源文件

#### 14. 删除 layout 文件

直接删除文件：
```
app/src/main/res/layout/dialog_http_tts_edit.xml
```

#### 15. 删除孤儿字符串（23 个键，8 个语言文件）

涉及文件列表（每个都要搜）：
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/src/main/res/values-zh-rHK/strings.xml`
- `app/src/main/res/values-zh-rTW/strings.xml`
- `app/src/main/res/values-es-rES/strings.xml`
- `app/src/main/res/values-pt-rBR/strings.xml`
- `app/src/main/res/values-vi/strings.xml`
- `app/src/main/res/values-ja-rJP/strings.xml`

要删除的键（每个文件搜索，删除对应的行）：
```
read_aloud_t
read_aloud_s
read_aloud_pause
read_aloud_timer
read_aloud_timer_chapter
read_aloud_speed
read_aloud_by_page
read_aloud_by_page_summary
read_aloud_wake_lock
read_aloud_wake_lock_summary
stream_read_aloud_audio
stream_read_aloud_audio_summary
read_aloud_floating_ui
read_aloud_floating_ui_summary
pause_read_aloud_while_phone_calls_title
pause_read_aloud_while_phone_calls_summary
read_aloud_read_phone_state_permission_rationale
read_aloud_by_media_button_title
read_aloud_by_media_button_summary
aloud_config
alouding_disable
aloud_can_not_auto_page
volume_key_page_on_play
```

#### 16. service/README.md — 删两行

文件：`app/src/main/java/io/legado/app/service/README.md`

删除约第 6-7 行：
```
* HttpReadAloudService 在线朗读服务
* TTSReadAloudService tts朗读服务
```

---

### 约束

- 不碰 `help/TTS.kt`、`HttpTTS.kt` entity、`channelIdReadAloud` 常量、`read_aloud` 字符串键
- 不碰 `content_select_action.xml` 和 `rss_read.xml` 中的 `menu_aloud`
- 每改完一批 → 编译 → 编译通过 → 继续下一批
- 第一批 → 第二批 → 第三批，按顺序来
- 不 commit，不 push
