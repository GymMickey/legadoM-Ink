# 阅读正文通过 URL 导入字体——技术交接文档

> 整理日期：2026-08-17
> 当前状态：URL 字体导入、离线保存、自动启用、独立列表和删除功能已实现；少数特殊字体会引发阅读页反复重新分页的问题仍未彻底解决。

## 1. 功能范围

本次功能只扩展现有的阅读字体选择能力：用户在阅读样式的“选择字体”窗口中输入 `http://` 或 `https://` 字体直链，App 下载并校验字体，永久保存到 App 自己的字体目录，然后写入现有的 `ReadBookConfig.textFont` 并刷新当前阅读排版。

支持格式：

- TrueType Font（`.ttf`）
- OpenType Font（`.otf`）

明确不包含：

- App 全局 UI 字体替换
- 字体商城、搜索、历史记录或自动更新
- `.ttc`、`.woff`、`.woff2`、压缩包和在线 CSS 字体
- 正文与标题分别选择字体
- 数据库变更

## 2. 原有字体链路确认

现有调用关系如下：

1. `ReadStyleDialog` 打开 `FontSelectDialog`。
2. `FontSelectDialog` 通过回调把选中的字体路径交给 `ReadStyleDialog.selectFont(path)`。
3. `ReadStyleDialog` 将路径保存到 `ReadBookConfig.textFont`。
4. `ReadStyleDialog` 发布 `EventBus.UP_CONFIG`，参数为 `2、5`。
5. 阅读页收到事件后更新样式并重新加载当前章节。
6. `ChapterProvider.upStyle()` 通过 `ReadBookConfig.textFont` 创建 `Typeface`，并用于章节标题、正文以及阅读页中原本就跟随该 Typeface 的提示文字。

本次没有改变 `ReadBookConfig.textFont` 的数据含义。URL 本身不会写入配置，配置中保存的始终是最终本地文件路径。

## 3. 完整数据流

```text
字体选择窗口
  → 点击“从 URL 导入”
  → 去除首尾空格并检查 http/https
  → 显示可取消的等待窗口，禁止重复提交
  → 在后台线程下载到临时文件
  → 检查 HTTP、重定向、大小和读写异常
  → Typeface.createFromFile() 实际解析临时文件
  → 生成安全且不覆盖旧文件的最终文件名
  → 移动/复制到 App 字体目录
  → 记录本地字体文件路径
  → ReadBookConfig.textFont = 最终本地路径
  → ReadBookConfig.save()
  → 发布原有 UP_CONFIG(2, 5) 事件
  → 当前章节重新排版，字体立即生效
```

完成下载后，阅读过程只读取本地文件。断网、原 URL 失效或服务器关闭都不会影响已经保存的字体。

## 4. 本地保存和字体列表

### 4.1 保存目录

主目录由以下代码确定：

```kotlin
File(context.externalFiles, "font")
```

其中 `context.externalFiles` 等于 `getExternalFilesDir(null)`，无法取得外部 App 目录时回退到 `filesDir`。典型逻辑位置为：

```text
Android/data/<应用包名>/files/font
```

该目录不是系统缓存目录，App 重启和设备断网后文件仍存在；卸载 App 或清除 App 数据时仍会按 Android 的正常规则删除。

临时文件也创建在此字体目录中，名称形如 `.font-import-*.tmp`。校验通过后优先在同一目录内重命名，重命名失败时再复制到最终文件；无论成功、失败、超限、取消还是解析失败，临时文件都会清理。

### 4.2 独立字体索引

新增 `ImportedFontStore`，使用 `PreferKey.importedFontFiles` 在 SharedPreferences 中记录 App 管理的字体绝对路径。

打开字体窗口时，不再要求用户必须先选择一个外部字体文件夹，而是优先合并以下来源：

- 已登记的 App 字体路径
- 当前主字体目录 `externalFiles/font`
- 内部目录 `filesDir/font`
- `getExternalFilesDirs(null)` 返回的各 App 外部目录下的 `font`
- 当前正在使用且仍然存在的本地字体文件
- 用户主动选择的外部字体文件夹

路径会规范化并去重，最终按字体名称排序。原来的外部文件夹选择和 `content://` 字体仍然保留。

## 5. 下载与校验策略

网络部分复用项目现有的 `okHttpClient`，通过独立 Builder 设置本功能的限制：

| 项目 | 当前实现 |
| --- | --- |
| URL 协议 | 仅 `http`、`https` |
| 连接超时 | 15 秒 |
| 读取超时 | 60 秒 |
| 整体调用超时 | 5 分钟 |
| 自动重定向 | 关闭，由本功能手动处理 |
| 最大重定向次数 | 5 次 |
| 最大字体大小 | 100 MB |

大小限制执行两次：

1. 响应有 `Content-Length` 时，在读取正文前检查。
2. 流式下载时持续累计实际字节数，即使服务器没有提供长度或长度不可信，也不能超过 100 MB。

文件有效性不依赖 MIME 类型。下载完成后必须满足：

- 文件非空；
- `Typeface.createFromFile(tempFile)` 能成功解析。

因此，伪装成 `.ttf` 的 HTML 错误页会在 Typeface 校验阶段失败。URL 或响应文件名没有扩展名时，最终文件名默认补 `.ttf`，但是否接受仍以 Typeface 的实际解析结果为准。

### 5.1 文件名处理

最终文件名来源优先级：

1. `Content-Disposition` 的 `filename*`；
2. `Content-Disposition` 的 `filename`；
3. 最终 URL 路径的最后一段；
4. 自动生成 `font_<时间戳>.ttf`。

处理内容包括：

- 支持 UTF-8 编码的中文响应文件名；
- 清理路径分隔符、控制字符和 Windows/Android 常见非法字符；
- 文件名为空时使用安全备用名；
- 只保留 `.ttf` 或 `.otf` 后缀，其他或无后缀时补 `.ttf`；
- 同名文件使用 `名称 (1).ttf`、`名称 (2).ttf` 递增，不覆盖旧文件。

## 6. 生命周期、取消和用户反馈

下载任务绑定到 `FontSelectDialog` 的 `viewLifecycleOwner.lifecycleScope`：

- 网络和文件操作在 IO/可中断后台线程执行；
- 下载期间禁用 URL 导入菜单，避免重复提交；
- 显示项目现有风格的 `WaitDialog`；
- 用户取消等待窗口时取消任务；
- Dialog View 销毁时再次取消任务并关闭等待窗口；
- 失败时不修改当前字体；
- 成功后才写入 `ReadBookConfig.textFont`。

已区分的用户提示包括：URL 无效、网络失败、下载超时、HTTP 状态失败、重定向异常、超过 100 MB、保存失败、字体解析失败、用户取消和导入成功。文案位于默认英文与简体中文字符串资源中。

## 7. 删除功能

字体列表中的 App 管理字体显示删除按钮。删除流程：

1. 弹出确认对话框；
2. 后台删除本地文件；
3. 从 `ImportedFontStore` 索引中移除路径；
4. 刷新字体列表；
5. 如果删除的是当前字体，调用原有选择回调切换到系统默认字体，并立即刷新阅读排版。

删除按钮不会出现在任意外部文件夹或 `content://` 字体上，避免越权删除用户在其他目录管理的文件。App 字体目录内的文件，包括 URL 导入或阅读配置导入后保存到该目录的字体，视为 App 可管理字体。

## 8. 阅读配置导入/导出兼容性

现有配置导出逻辑会通过 `FileDoc.fromFile(ReadBookConfig.textFont)` 打开字体输入流，把字体文件放进阅读配置 ZIP，并在 JSON 中只保存字体文件名。

现有配置导入逻辑会从 ZIP 取出字体，复制到 `externalFiles/font`，然后把 `textFont` 恢复为该本地文件路径。

URL 导入后的 `textFont` 与原有本地字体完全相同，因此直接复用了上述流程，没有修改配置格式。原有 `content://` 字体读取和导出路径也未移除。

## 9. 主要新增或修改文件

| 文件 | 作用 |
| --- | --- |
| `ui/font/FontUrlImporter.kt` | URL 下载、超时、手动重定向、大小限制、Typeface 校验、落盘和错误分类 |
| `ui/font/FontImportFileUtils.kt` | URL 解析、响应文件名解析、安全文件名、同名递增、受限流式复制 |
| `ui/font/ImportedFontStore.kt` | App 字体目录、字体路径索引、目录扫描和安全删除 |
| `ui/font/FontSelectDialog.kt` | URL 输入入口、任务生命周期、加载提示、错误反馈、本地字体合并、删除确认 |
| `ui/font/FontAdapter.kt` | 字体预览、当前选中状态和 App 管理字体的删除按钮 |
| `ui/book/read/config/ReadStyleDialog.kt` | 选择成功后保存 `ReadBookConfig` 并复用原事件刷新阅读页 |
| `constant/PreferKey.kt` | 新增 `importedFontFiles` 偏好键 |
| `res/menu/font_select.xml` | 增加“从 URL 导入”菜单项，保留原文件夹入口 |
| `res/layout/item_font.xml` | 增加字体删除按钮 |
| `res/values/strings.xml` | 默认英文文案 |
| `res/values-zh/strings.xml` | 简体中文文案 |
| `test/.../FontImportFileUtilsTest.kt` | 文件名、URL、大小限制和失败清理单元测试 |
| `ui/book/read/page/ReadView.kt` | 保留了一项针对重复分页的尺寸来源调整，详见第 11 节；不是 URL 下载必需逻辑 |

未新增大型依赖，未修改数据库、包名、应用名称、版本策略和发布流程。

## 10. 自动化验证

已执行相关单元测试和 AppMax Debug 构建：

```text
:app:testAppMaxDebugUnitTest --tests io.legado.app.ui.font.FontImportFileUtilsTest
:app:assembleAppMaxDebug
```

结果：构建成功，`FontImportFileUtilsTest` 共 6 项，0 失败、0 错误。

覆盖的自动测试：

- `http/https`、首尾空格、中文 URL 和非法协议；
- `Content-Disposition` 编码文件名与 URL 文件名回退；
- 非法文件名字符清理和缺失后缀补全；
- 同名字体自动增加序号且不覆盖旧文件；
- 流式下载超过限制后删除半成品；
- 下载流中途异常后删除半成品。

网络服务器行为、真实大字体的 Typeface 解析、断网重启、配置 ZIP 往返和不同 Android 设备上的 UI 表现仍以设备人工测试为主。

## 11. 开发过程中发现的问题

### 11.1 URL 字体必须先选择文件夹才显示——已处理

现象：字体虽然已经下载并生效，但再次打开字体窗口时列表为空；选择一次外部文件夹后才出现。

处理：

- 为 App 管理字体增加独立路径索引；
- 打开窗口时无条件优先扫描 App 字体目录；
- 外部文件夹只作为附加来源，不再是显示 URL 字体的前置条件；
- 当前正在使用的本地字体会被补充进列表并登记。

### 11.2 缺少删除入口——已处理

为 App 管理字体增加删除按钮、确认提示、失败反馈和“删除当前字体后回退系统默认字体”的逻辑。

### 11.3 少数特殊字体导致页码变化和页面抖动——仍未彻底解决

已通过录屏确认：在用户没有正常翻页时，当前页码可能在 `8/17、9/17、10/17、12/17` 等数值之间自行变化，正文随重新分页而跳动。

当前已知可稳定触发问题的字体：**汇文明朝体**。

后续人工对比发现：

- 问题主要由极少数特定字体触发；
- 换用其他字体后通常不出现；
- 因出现概率较低，当前决定不继续做字体专用适配。

相关代码线索：

- `PageView.upTipStyle()` 会把 `ChapterProvider.typeface` 用于阅读页的页眉、页脚提示文字；特殊字体的 ascent、descent、字高或字重行为可能改变这些区域的测量结果。
- 原实现中 `ReadView` 外层和主 `ContentTextView` 都会调用 `ChapterProvider.upViewSize()`，两者代表的高度并不完全相同。

当前工作区保留的尝试性调整：

- `ReadView.onSizeChanged()` 不再提交外层高度；
- 仍由主 `ContentTextView.onSizeChanged()` 提交真正正文区域的尺寸；
- 横竖屏或分辨率变化时，`ContentTextView` 仍会随布局变化重新提交尺寸，因此理论上仍保留自动适配能力。

但是，该调整在问题字体上仍未完全消除抖动，不能将其标记为已修复。建议主要维护者在合并前重点回归：

- 不同分辨率和屏幕密度；
- 横竖屏切换；
- 全屏/非全屏、状态栏与导航栏显示切换；
- 刘海屏、挖孔屏和系统字体缩放；
- 页眉/页脚显示与隐藏；
- 滚动、覆盖、仿真等不同翻页模式。

如果将来继续调查，建议先取得能稳定复现的字体文件，并记录以下数据，避免继续凭现象猜测：

- `ReadView` 与主 `ContentTextView` 每次 `onSizeChanged` 的宽高；
- 问题字体和正常字体的 `FontMetrics`；
- `ChapterProvider.upViewSize()`、`upStyle()` 和 `ReadBook.loadContent(forceReload = true)` 的触发次数与调用顺序；
- 页眉、页脚高度是否在两组数值之间往返。

## 12. 建议维护者验收顺序

1. 覆盖安装后，不选择任何外部文件夹，直接打开字体列表，确认 App 字体可见。
2. 导入有效 `.ttf`、`.otf` 和无后缀但内容有效的字体。
3. 导入成功后断网并重启 App，确认仍从本地读取。
4. 验证非法 URL、404、超时、超过 100 MB、HTML 伪装字体和损坏字体均不改变当前字体。
5. 重复导入同名字体，确认旧文件未被覆盖。
6. 删除非当前字体和当前字体，确认列表、文件、配置及阅读刷新行为正确。
7. 验证系统默认/衬线/等宽字体、外部文件夹和 `content://` 字体没有回归。
8. 导出并重新导入包含 URL 字体的阅读配置。
9. 使用普通字体回归不同分辨率、旋转、系统栏和翻页模式。
10. 对已知问题字体只记录结果；除非决定继续维护兼容性，否则不阻塞 URL 导入功能本身。
