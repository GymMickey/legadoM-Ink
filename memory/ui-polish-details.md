---
name: ui-polish-details
description: UI 打磨阶段的关键文件路径和技术细节，便于下次审查
metadata:
  type: reference
---

# UI 打磨关键文件索引

## 首页仪表盘

| 组件 | 路径 | 说明 |
|------|------|------|
| Compose UI | `ui/main/homepage/HomepageScreen.kt` (~547 行) | 4 卡片 Compose 布局 |
| 封面组件 | `ui/main/homepage/modules/HomepageBookCover.kt` | GlideImage 封面 + 文字叠层 |
| ViewModel | `ui/main/homepage/HomepageViewModel.kt` | combine 多 Flow 构建 State |
| Contract | `ui/main/homepage/HomepageContract.kt` | HomepageDashboardState 数据类 |
| Fragment | `ui/main/homepage/HomepageFragment.kt` | Compose 桥接 |

## 封面系统

| 组件 | 路径 | 说明 |
|------|------|------|
| 参考实现 | `ui/widget/image/CoverImageView.kt` | 标准封面 View，shouldDrawName 逻辑正确 |
| 封面工具 | `model/BookCover.kt` | drawBookName / drawBookAuthor 设置 |
| Glide 加载器 | `help/glide/OkHttpModelLoader.kt` | sourceOriginOption 处理封面解密 |

## 阅读菜单

| 组件 | 路径 | 说明 |
|------|------|------|
| 菜单布局 | `res/layout/view_read_menu.xml` | 3 按钮 + spacer(1-2-2-1) |
| 菜单逻辑 | `ui/book/read/ReadMenu.kt` | TTS 引用已清理 |

## 备份系统

| 组件 | 路径 | 说明 |
|------|------|------|
| 备份选择器 | `help/storage/BackupSelectorConfig.kt` | 待清理 videoConfig/httpTTS |
| 备份执行 | `help/storage/Backup.kt` | 待删 videoConfig.xml |
| 恢复执行 | `help/storage/Restore.kt` | 待删空 readBackupPrefs 块 |
| 备份信息 | `help/storage/BackupInfoHelper.kt` | 待删 videoConfig 条目 |
| API 控制器 | `api/controller/BackupController.kt` | 待删 videoConfig ConfigItemDef |

## 主题/颜色

| 组件 | 路径 | 说明 |
|------|------|------|
| 通用颜色 | `ui/theme/CommonPageColors.kt` | pageCardContainerColor / pageCardElevatedContainerColor |
| Compose 主题 | `ui/theme/LegadoTheme.kt` | primary = Color(accentColor) |
| E-Ink 判断 | `help/config/AppConfig.kt:54` | isEInkMode = themeMode == "3" |

## 版本/命名

| 组件 | 路径 | 说明 |
|------|------|------|
| Gradle 构建 | `app/build.gradle` | name, version, outputFileName, manifestPlaceholders |
| Debug 应用名 | `res/values/strings.xml:6` | `app_name_debug` = "阅读D" → 改 "阅读" |
| 正式应用名 | `res/values-zh/strings.xml:3` | `app_name` = "阅读" |

**Why:** 下次继续 UI 打磨或审查时不用重新探索文件位置。
**How to apply:** 直接参照路径打开文件。
