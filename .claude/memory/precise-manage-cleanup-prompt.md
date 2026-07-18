---
name: precise-manage-cleanup-prompt
description: 删除精准管理模块（PreciseManageFragment + 6个子模块UI）的完整代码窗口提示词
metadata:
  type: reference
---

## 删除精准管理模块（安全分批版）

### 原则

- 只删除 UI 层（Activity/Screen/ViewModel），**保留**数据层（Entity/DAO/Database migration）
- 被其他功能共享的组件（FilePickerDialog、HandleFileActivity、SourceRecycleBinHelp、UrlRecordInterceptor 等）**不碰**
- 不做任何重构、不新增逻辑
- 每批改完 → 编译 → 通过 → 继续下一批

### 保留清单（说明为什么保留）

| 文件/组件 | 原因 |
|-----------|------|
| `data/entities/UrlRecord.kt` + `data/dao/UrlRecordDao.kt` | 数据库兼容，Room schema 不变 |
| `help/http/UrlRecordInterceptor.kt` + `HttpHelper.kt` | HTTP 层 URL 记录，独立于 UI |
| `data/entities/SourceRecycleBin.kt` + `data/dao/SourceRecycleBinDao.kt` | 数据库兼容 |
| `help/source/SourceRecycleBinHelp.kt` | 被 ReplaceRuleViewModel、DictRuleViewModel 等多个模块调用 |
| `ui/file/FilePickerDialog.kt` + `FilePickerViewModel.kt` | 被 40+ 文件使用（书源导入、字体选择、备份恢复等） |
| `ui/file/HandleFileActivity.kt` + `HandleFileContract.kt` + `HandleFileViewModel.kt` | 文件关联/导入核心功能 |
| `ui/file/utils/FilePickerIcon.java` | FilePicker 依赖 |
| `AppDatabase.kt` + `DatabaseMigrations.kt` | 数据库 schema，不碰 |

---

### 第一批：删除导航枢纽 + 清理入口

#### 1. 删除 PreciseManageFragment.kt

**删除整个文件：**
```
app/src/main/java/io/legado/app/ui/config/PreciseManageFragment.kt
```

#### 2. 删除布局文件

**删除整个文件：**
```
app/src/main/res/xml/pref_precise_manage.xml
```

#### 3. ConfigActivity.kt — 删除 PreciseManageFragment 分支

文件：`app/src/main/java/io/legado/app/ui/config/ConfigActivity.kt`

**3a.** 删除 import（约第 3 行）：
```kotlin
import io.legado.app.ui.config.PreciseManageFragment
```
（如果 import 行不存在，跳过）

**3b.** 删除 when 分支（约第 20 行）：
删掉这一行：
```kotlin
ConfigTag.PRECISE_MANAGE -> replaceFragment<PreciseManageFragment>(configTag)
```

#### 4. ConfigTag.kt — 删除 PRECISE_MANAGE 常量

文件：`app/src/main/java/io/legado/app/ui/config/ConfigTag.kt`

删除第 5 行：
```kotlin
const val PRECISE_MANAGE = "preciseManage"
```

#### 5. pref_main.xml — 删除"精准管理"入口

文件：`app/src/main/res/xml/pref_main.xml`

删除第 108-114 行（preciseManage 的 Preference 块）：
```xml
        <io.legado.app.lib.prefs.Preference
            android:icon="@drawable/ic_storage_black_24dp"
            android:key="preciseManage"
            android:summary="@string/precise_manage_summary"
            android:title="@string/precise_manage"
            app:allowDividerBelow="false"
            app:iconSpaceReserved="false" />
```

#### 6. MyFragment.kt — 清理精准管理入口 + 死代码

文件：`app/src/main/java/io/legado/app/ui/main/my/MyFragment.kt`

**6a.** 删除 `preciseManage` 点击处理（约 167-169 行）：
```kotlin
                "preciseManage" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.PRECISE_MANAGE)
                }
```

**6b.** 删除 4 个死代码 handler（这些 preference key 在 pref_main.xml 中不存在，永远不会触发）：

删除（约 165 行）：
```kotlin
                "urlRecord" -> startActivity<UrlRecordActivity>()
```

删除（约 183 行）：
```kotlin
                "fileManage" -> startActivity<FileManageActivity>()
```

删除（约 185 行）：
```kotlin
                "storageManage" -> startActivity<StorageManageActivity>()
```

删除（约 186 行）：
```kotlin
                "downloadManage" -> startActivity<DownloadManageActivity>()
```

**6c.** 删除对应的死 import（约 22, 28-30 行）：
```kotlin
import io.legado.app.ui.book.storage.StorageManageActivity
```
```kotlin
import io.legado.app.ui.urlRecord.UrlRecordActivity
```
```kotlin
import io.legado.app.ui.file.FileManageActivity
```
```kotlin
import io.legado.app.ui.download.DownloadManageActivity
```

#### 7. DebugLogScreen.kt — 删除"精准管理"菜单项

文件：`app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt`

删除约 252-264 行的 DropdownMenuItem 块（从 `DropdownMenuItem(` 开始到 `),` 结束）：
```kotlin
                                DropdownMenuItem(
                                    text = { Text("精准管理") },
                                    onClick = {
                                        showOverflowMenu = false
                                        val intent = Intent(context, ConfigActivity::class.java)
                                        intent.putExtra("configTag", ConfigTag.PRECISE_MANAGE)
                                        context.startActivity(intent)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Settings, contentDescription = null)
                                    },
                                    colors = menuItemColors
                                )
```

注意：删除该项后，如果文件顶部有 `import io.legado.app.ui.config.ConfigTag` 且仅用于此处，一并删除该 import。

---

### 第二批：删除 6 个子模块 UI 文件

#### 8. 删除 urlRecord UI 模块

直接删除以下 4 个文件：
```
app/src/main/java/io/legado/app/ui/urlRecord/UrlRecordActivity.kt
app/src/main/java/io/legado/app/ui/urlRecord/UrlRecordScreen.kt
app/src/main/java/io/legado/app/ui/urlRecord/UrlRecordUIState.kt
app/src/main/java/io/legado/app/ui/urlRecord/UrlRecordViewModel.kt
```

#### 9. 删除 download UI 模块

直接删除以下 3 个文件：
```
app/src/main/java/io/legado/app/ui/download/DownloadManageActivity.kt
app/src/main/java/io/legado/app/ui/download/DownloadManageScreen.kt
app/src/main/java/io/legado/app/ui/download/DownloadManageViewModel.kt
```

#### 10. 删除 module 模块（全部）

直接删除以下 4 个文件：
```
app/src/main/java/io/legado/app/ui/module/ModuleStatus.kt
app/src/main/java/io/legado/app/ui/module/ModuleStatusActivity.kt
app/src/main/java/io/legado/app/ui/module/ModuleStatusScreen.kt
app/src/main/java/io/legado/app/ui/module/ModuleStatusViewModel.kt
```

#### 11. 删除 source/recycle UI 模块

直接删除以下 3 个文件：
```
app/src/main/java/io/legado/app/ui/source/recycle/SourceRecycleBinActivity.kt
app/src/main/java/io/legado/app/ui/source/recycle/SourceRecycleBinScreen.kt
app/src/main/java/io/legado/app/ui/source/recycle/SourceRecycleBinViewModel.kt
```

⚠️ **保留** `help/source/SourceRecycleBinHelp.kt` — 被多个业务模块调用（ReplaceRuleViewModel、DictRuleViewModel、TxtTocRuleViewModel 等）

#### 12. 删除 book/storage 模块（全部）

直接删除以下 6 个文件：
```
app/src/main/java/io/legado/app/ui/book/storage/StorageManageActivity.kt
app/src/main/java/io/legado/app/ui/book/storage/StorageManageScreen.kt
app/src/main/java/io/legado/app/ui/book/storage/StorageManageViewModel.kt
app/src/main/java/io/legado/app/ui/book/storage/components/CacheItemCard.kt
app/src/main/java/io/legado/app/ui/book/storage/components/CacheSummaryCard.kt
app/src/main/java/io/legado/app/ui/book/storage/components/ClearConfirmDialog.kt
```

如果 `components/` 目录变为空目录，一并删除。

#### 13. 删除 file UI 模块（仅 UI，保留 FilePicker/HandleFile）

直接删除以下 3 个文件：
```
app/src/main/java/io/legado/app/ui/file/FileManageActivity.kt
app/src/main/java/io/legado/app/ui/file/FileManageScreen.kt
app/src/main/java/io/legado/app/ui/file/FileManageViewModel.kt
```

⚠️ **保留** `ui/file/` 目录下其他文件：`FilePickerDialog.kt`、`FilePickerViewModel.kt`、`HandleFileActivity.kt`、`HandleFileContract.kt`、`HandleFileViewModel.kt`、`utils/FilePickerIcon.java` — 这些被 40+ 个文件引用，是核心共享组件。

---

### 第三批：清理 AndroidManifest + 资源文件

#### 14. AndroidManifest.xml — 删除 6 个 Activity 声明

文件：`app/src/main/AndroidManifest.xml`

删除以下 6 个 activity 块（含注释）：

```xml
        <!-- 存储管理 -->
        <activity
            android:name=".ui.book.storage.StorageManageActivity"
            android:configChanges="orientation|screenSize"
            android:hardwareAccelerated="true" />
```

```xml
        <!-- 下载管理 -->
        <activity
            android:name=".ui.download.DownloadManageActivity"
            android:configChanges="orientation|screenSize"
            android:hardwareAccelerated="true" />
```

```xml
        <activity
            android:name=".ui.module.ModuleStatusActivity"
            android:configChanges="orientation|screenSize"
            android:hardwareAccelerated="true" />
```

```xml
        <!-- URL访问记录 -->
        <activity
            android:name=".ui.urlRecord.UrlRecordActivity"
            android:configChanges="orientation|screenSize"
            android:hardwareAccelerated="true" />
```

```xml
        <activity
            android:name=".ui.source.recycle.SourceRecycleBinActivity"
            android:configChanges="orientation|screenSize"
            android:hardwareAccelerated="true" />
```

```xml
        <!-- 文件管理 -->
        <activity
            android:name=".ui.file.FileManageActivity"
            android:configChanges="orientation|screenSize"
            android:hardwareAccelerated="true" />
```

⚠️ **保留** `HandleFileActivity` 声明（文件关联功能仍在使用）

#### 15. 删除 menu/url_record.xml

直接删除文件：
```
app/src/main/res/menu/url_record.xml
```

#### 16. 删除孤儿字符串（8 个语言文件）

涉及文件列表（每个都要搜）：
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/src/main/res/values-zh-rHK/strings.xml`
- `app/src/main/res/values-zh-rTW/strings.xml`
- `app/src/main/res/values-es-rES/strings.xml`
- `app/src/main/res/values-pt-rBR/strings.xml`
- `app/src/main/res/values-vi/strings.xml`
- `app/src/main/res/values-ja-rJP/strings.xml`

**A. 所有 8 个文件都要删除的键（file_manage + precise_manage）：**
```
precise_manage
precise_manage_summary
file_manage
file_manage_summary
```

**B. 仅在 values / values-zh / values-zh-rTW / values-zh-rHK 中删除的键（这些子模块只翻译了 4 种语言）：**

url_record 相关：
```
url_record
record_url_switch
search_url_record_hint
filter_by_domain
clear_records
clear_old_7_days
clear_old_30_days
clear_all_records
```

storage_manage 相关：
```
storage_manage
storage_manage_summary
```

download_manage 相关：
```
download_manage
download_manage_summary
```

module_status 相关：
```
module_status
module_status_summary
```

source_recycle_bin 相关（24 个键）：
```
source_recycle_bin
source_recycle_bin_summary
show_source_recycle_bin
hide_source_recycle_bin
enable_source_recycle_bin
disable_source_recycle_bin
source_recycle_bin_count
source_recycle_bin_empty
source_recycle_bin_enabled
source_recycle_bin_disabled
source_recycle_bin_restore_msg
source_recycle_bin_conflict_title
source_recycle_bin_conflict_msg
source_recycle_bin_delete_msg
source_recycle_bin_batch_restore_msg
source_recycle_bin_batch_conflict_msg
source_recycle_bin_batch_delete_msg
source_recycle_bin_clear_title
source_recycle_bin_clear_msg
source_recycle_bin_type_group
source_recycle_bin_time_left
source_recycle_bin_restored
source_recycle_bin_deleted
source_recycle_bin_cleared
```

### 约束

- 不碰数据层：Entity/DAO/DatabaseMigration/AppDatabase
- 不碰共享组件：FilePicker、HandleFile、SourceRecycleBinHelp、UrlRecordInterceptor
- 第一批 → 第二批 → 第三批，按顺序来
- 每批改完必须编译通过再继续下一批
- 不 commit，不 push

### 第二批编译验证

第二批删除文件最多（23 个文件），建议按子模块逐个删除并编译：
- urlRecord（4 文件）→ 编译
- download（3 文件）→ 编译
- module（4 文件）→ 编译
- source/recycle（3 文件）→ 编译
- book/storage（6 文件）→ 编译
- file（3 文件）→ 编译
