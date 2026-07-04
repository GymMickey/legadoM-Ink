# 备份图片泄漏 Bug 分析报告

## 问题描述

用户反馈：在主题设置中删除的历史背景图片以及启动界面使用过的历史图片，在执行备份操作时仍然会被打包到压缩包中，导致备份文件体积持续增大。

## 代码分析

### 1. 备份流程全貌

备份入口：`Backup.backup()` (Backup.kt:432)

```
Backup.backup()
├─ FileUtils.delete(backupPath)          ← 清理临时目录
├─ 导出数据库数据 → JSON 文件 (bookshelf.json 等)
├─ 导出配置 → JSON/XML 文件 (readConfig.json, themeConfig.json, config.xml 等)
├─ stageBackgroundImageFiles(backupPath) ← 复制图片到暂存目录
├─ stageHighlightRuleBackgroundFiles()
├─ stageCoverGallery()
├─ stageBookCache()
├─ getBackupPaths()                      ← 收集需要打包的文件路径列表
├─ ZipUtils.zipFiles(paths, zipFilePath) ← ZIP 打包
├─ 复制到目标目录 / 上传 WebDav
└─ FileUtils.delete(backupPath)          ← 清理临时目录
```

### 2. `getBackupPaths()` — 存在严重的代码缺陷

**文件**: `app/src/main/java/io/legado/app/help/storage/Backup.kt:325-341`

```kotlin
private fun getBackupPaths(): ArrayList<String> {
    return File(backupPath)                           // ← 326: EARLY RETURN! 
        .listFiles()
        ?.mapTo(arrayListOf()) { it.absolutePath }
        ?: arrayListOf()
    val paths = arrayListOf(*backupFileNames)         // ← 330-340: DEAD CODE
    for (i in 0 until paths.size) {                   //   永远不会执行
        paths[i] = backupPath + File.separator + paths[i]
    }
    val bgFiles = getBackgroundImageFiles()
    LogUtils.d(TAG, "背景图片文件数量: ${bgFiles.size}")
    bgFiles.forEach {
        LogUtils.d(TAG, "添加背景图片: ${it.absolutePath}")
        paths.add(it.absolutePath)
    }
    return paths
}
```

**结论：第 326 行的 `return` 使函数提前退出，第 330-340 行为死代码。**

该函数的实际行为：
- 仅返回 `backupPath/` 目录下的**直接子项**（包括文件和子目录）
- `getBackgroundImageFiles()` 永远不会被调用
- 所有 JSON 数据文件、bgImage/ 子目录、bgImageNight/ 子目录等均作为路径条目返回

### 3. `ZipUtils.zipFile()` 会递归处理目录

**文件**: `app/src/main/java/io/legado/app/utils/compress/ZipUtils.kt:171-203`

```kotlin
private fun zipFile(srcFile: File, rootPath: String, zos: ZipOutputStream, comment: String?): Boolean {
    if (!srcFile.exists()) return true
    rootPath1 = rootPath1 + (if (isSpace(rootPath1)) "" else File.separator) + srcFile.name
    if (srcFile.isDirectory) {
        val fileList = srcFile.listFiles()
        if (fileList == null || fileList.isEmpty()) {
            val entry = ZipEntry("$rootPath1/")        // 空目录
            zos.putNextEntry(entry)
            zos.closeEntry()
        } else {
            for (file in fileList) {
                if (!zipFile(file, rootPath1, zos, comment)) return false  // 递归!
            }
        }
    } else {
        // 写入文件内容
    }
    return true
}
```

**关键发现**：`zipFile()` 对传入的每个路径条目，如果是目录则**递归遍历其所有内容**并打包。这意味着 `getBackupPaths()` 返回的 `bgImage/`、`bgImageNight/` 目录路径会导致其下的**所有文件**被无条件打包。

### 4. 图片暂存逻辑 — 过滤正确但依赖目录清理

**文件**: `Backup.kt:262-278`

```kotlin
fun stageBackgroundImageFiles(rootPath: String) {
    // 1. 阅读界面背景 ← 仅从 ReadBookConfig.configList 中引用
    getReadBackgroundImageFiles().forEach { bgFile ->
        bgFile.copyTo(File(rootPath, bgFile.name), overwrite = true)
    }
    // 2. 当前主题背景 ← 仅从 SharedPreferences 中读取
    listOf(PreferKey.bgImage, PreferKey.bgImageN).forEach { prefKey ->
        appCtx.getPrefString(prefKey)?.let { path ->
            resolveThemeBackgroundFile(path, prefKey)
        }?.let { bgFile ->
            val targetDir = File(rootPath, prefKey).createFolderIfNotExist()
            bgFile.copyTo(File(targetDir, bgFile.name), overwrite = true)
        }
    }
    // 3. 所有主题配置的背景 ← 从 ThemeConfig.configList 遍历
    getThemeConfigBackgroundFiles().forEach { (prefKey, bgFile) ->
        val targetDir = File(rootPath, prefKey).createFolderIfNotExist()
        bgFile.copyTo(File(targetDir, bgFile.name), overwrite = true)
    }
}
```

`stageBackgroundImageFiles()` 的过滤逻辑是正确的：只复制被当前配置引用的图片文件。使用 `createFolderIfNotExist()` 创建子目录，但**不清理子目录中已有的旧文件**。

### 5. 图片删除操作 — 不删除磁盘文件

| 操作 | 位置 | 行为 |
|------|------|------|
| 删除主题背景 | `ThemeConfigFragment.selectBgAction()` | 仅调用 `removePref(bgKey)`，**不删除磁盘文件** |
| 删除主题配置 | `ThemeConfig.delConfig()` | 从 `configList` 移除，**不删除关联的图片文件** |
| 删除启动界面图片 | `WelcomeConfigFragment` | 仅调用 `removePref(key)`，**不删除磁盘文件** |
| 删除阅读配置 | `ReadBookConfig.deleteDur()` | 从 `configList` 移除，**不删除关联的 bg 文件** |

### 6. 图片清理机制 — 覆盖面不足

| 清理函数 | 触发时机 | 覆盖目录 | 缺陷 |
|----------|----------|----------|------|
| `ThemeConfig.clearBg()` | 每个会话**首次**调用 `applyConfig()` 时 | `bgImage/`, `bgImageN/` | 仅运行一次，删除主题配置文件后残留图片无法清理 |
| `ReadBookConfig.clearBgAndCache()` | 应用启动时 (`App.kt:114`) | `bg/` | 仅启动时运行，会话期间删除的配置残留图片无法清理 |
| 启动界面图片清理 | **无** | `covers/` | 完全不存在清理机制 |

## 根因分析

### Bug 确认：存在

综合代码分析，该 Bug **确实存在**，根因为 `getBackupPaths()` 的早期 return 缺陷与 `ZipUtils.zipFile()` 的目录递归打包行为的组合效应。

### 触发条件

以下时序可导致已删除图片被打包：

1. **备份临时目录未完全清理**：虽然 `Backup.backup()` 首行调用 `FileUtils.delete(backupPath)`，但如果因文件占用等原因删除不完整，`backupPath` 残留的子目录（来自上次备份的 `bgImage/`、`bgImageN/`）中的旧文件会被 `getBackupPaths()` 收集
2. **`createFolderIfNotExist()` 不会清空已有目录**：`stageBackgroundImageFiles()` 仅追加新文件，不删除目的目录中的已有文件
3. **`ZipUtils.zipFile()` 无条件递归打包目录**：只要目录路径出现在打包列表中，其下所有文件都会被包含

### 死代码问题的严重性

`getBackupPaths()` 中的死代码意味着以下原本设计用于过滤的逻辑**完全不生效**：

- `getBackgroundImageFiles()` 的引用过滤（检查 `exists() && isFile`、检查 `PreferKey.bgImage` 是否为空）
- 路径去重 (`distinctBy`)
- 仅从 `backupFileNames` 列表中收集已知文件路径

实际的打包行为完全依赖 `File(backupPath).listFiles()` 的原始结果 + `ZipUtils.zipFile()` 的递归目录处理。

## 修复建议

### 方案一：修复 `getBackupPaths()` 的死代码（推荐）

将第 326 行的 `return` 移到正确位置：

```kotlin
private fun getBackupPaths(): ArrayList<String> {
    val paths = File(backupPath)
        .listFiles()
        ?.mapTo(arrayListOf()) { it.absolutePath }
        ?: arrayListOf()
    // 添加 backupFileNames 中的文件
    val filePathPrefix = backupPath + File.separator
    backupFileNames.forEach { fileName ->
        val fullPath = filePathPrefix + fileName
        if (!paths.contains(fullPath)) {
            paths.add(fullPath)
        }
    }
    // 添加背景图片文件（带引用过滤）
    val bgFiles = getBackgroundImageFiles()
    bgFiles.forEach {
        paths.add(it.absolutePath)
    }
    return paths
}
```

**同时需要**：`getBackupPaths()` 中应排除已由 `stageBackgroundImageFiles()` 创建的目录条目，改为显式收集子目录中的文件，以避免目录递归打包带来的不可控行为。

### 方案二：修改 `ZipUtils.zipFile()` 不对目录递归

将 `getBackupPaths()` 返回的目录路径展开为具体文件路径后再传给 `zipFiles()`。但此方案治标不治本。

### 方案三：增强图片删除时的磁盘清理

| 函数 | 修改内容 |
|------|----------|
| `ThemeConfigFragment.selectBgAction()` 删除操作 | 增加 `File(bgFilePath).delete()` |
| `ThemeConfig.delConfig()` | 调用清理检查删除关联的背景图片文件 |
| `WelcomeConfigFragment` 删除操作 | 增加 `File(imagePath).delete()` |
| `ThemeConfig.clearBg()` | 移除 `needClearImg` 一次性限制，改为每次应用主题时都执行清理 |

### 方案四：在 `stageBackgroundImageFiles()` 中清空子目录

在创建目标子目录前先执行 `FileUtils.delete(targetDir)` 确保无残留文件。

## 涉及的关键文件

| 文件 | 角色 |
|------|------|
| `app/.../help/storage/Backup.kt:325-341` | `getBackupPaths()` — 死代码缺陷 |
| `app/.../help/storage/Backup.kt:262-278` | `stageBackgroundImageFiles()` — 图片暂存 |
| `app/.../help/storage/Backup.kt:166-219` | `getBackgroundImageFiles()` — 图片引用收集 |
| `app/.../utils/compress/ZipUtils.kt:171-203` | `zipFile()` — 目录递归打包 |
| `app/.../help/config/ThemeConfig.kt:535-566` | `clearBg()` — 一次性清理限制 |
| `app/.../help/config/ReadBookConfig.kt:178-195` | `clearBgAndCache()` — 启动时清理 |
| `app/.../ui/config/ThemeConfigFragment.kt:276-279` | 主题背景删除 — 不删文件 |
| `app/.../ui/config/WelcomeConfigFragment.kt:130-131,163-164` | 启动图片删除 — 不删文件 |
