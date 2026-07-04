# 下划线距离设置功能设计

## 概述

在 `UnderlineWidthDialog` 界面添加一个 SeekBar，用于调节下划线与文字之间的距离。

## 需求背景

当前下划线距离在代码中硬编码为 `6.dpToPx()`，用户无法自定义调节。用户希望能够像调节下划线粗细一样，自由调节下划线与文字的距离。

## 设计方案

### 1. UI 变更

**UnderlineWidthDialog** 界面添加第二个 SeekBar：

```
┌─────────────────────────────────────┐
│ 下划线粗细设置                        │
├─────────────────────────────────────┤
│ [预览文字效果]  ← 带下划线预览         │
├─────────────────────────────────────┤
│ 粗细: 2.0                           │
│ 细 ─────────●────────── 粗          │
├─────────────────────────────────────┤
│ 距离: 6.0                           │  ← 新增
│ 近 ─────────●────────── 远          │  ← 新增
└─────────────────────────────────────┘
```

- **距离范围**: 0dp ~ 20dp（默认 6dp，与当前硬编码值一致）
- **步进**: 1dp 每档，共 21 档

### 2. 配置存储

**ReadBookConfig.Config** 新增字段：

```kotlin
var underlineOffset: Float = 6f, // 下划线距离(dp)
```

### 3. 渲染逻辑变更

需要修改以下文件，将硬编码的 `underlineOffset = 6.dpToPx()` 改为读取配置：

| 文件 | 当前实现 | 变更内容 |
|------|----------|----------|
| `TextLine.kt:286-287` | `distance = (lineSpacingExtra * 10 - 11).coerceIn(-1f, 10f)` | 改为读取 `ReadBookConfig.durConfig.underlineOffset` |
| `SolidUnderlineSpan.kt:14` | `underlineOffset = 6.dpToPx()` 硬编码 | 接收构造函数参数 |
| `DashUnderlineSpan.kt:15` | `underlineOffset = 6.dpToPx()` 硬编码 | 接收构造函数参数 |
| `WaveUnderlineSpan.kt:15` | `underlineOffset = 6.dpToPx()` 硬编码 | 接收构造函数参数 |
| `DoubleUnderlineSpan.kt:14` | `underlineOffset = 6.dpToPx()` 硬编码 | 接收构造函数参数 |
| `BgImageSpan.kt:17` | `underlineOffset = 6.dpToPx()` 硬编码 | 接收构造函数参数 |

### 4. 文件修改清单

#### 4.1 布局文件
- `dialog_underline_width.xml` — 添加距离 SeekBar UI

#### 4.2 Dialog 逻辑
- `UnderlineWidthDialog.kt` — 添加距离调节逻辑，预览视图同时反映粗细和距离变化

#### 4.3 配置存储
- `ReadBookConfig.kt` — 添加 `underlineOffset` 配置字段，添加 getter/setter

#### 4.4 渲染逻辑
- `TextLine.kt` — 使用配置的距离值绘制全局下划线
- `SolidUnderlineSpan.kt` — 接收并使用动态距离参数
- `DashUnderlineSpan.kt` — 接收并使用动态距离参数
- `WaveUnderlineSpan.kt` — 接收并使用动态距离参数
- `DoubleUnderlineSpan.kt` — 接收并使用动态距离参数
- `BgImageSpan.kt` — 接收并使用动态距离参数

#### 4.5 预览逻辑
- `HighlightRulePreview.kt` — 预览使用配置的距离

#### 4.6 字符串资源
- `values/strings.xml` — 添加英文字符串
- `values-zh/strings.xml` — 添加中文字符串

## 实现细节

### UnderlineWidthDialog.kt 变更

1. 添加距离 SeekBar 的初始化
2. 修改 `UnderlinePreviewView` 支持动态距离
3. 距离变化时更新预览并保存配置

### Span 类变更模式

所有 Span 类统一变更模式：

```kotlin
// 变更前
class SolidUnderlineSpan(
    private val textColor: Int,
    private val underlineColor: Int,
    private val underlineWidth: Float = 1f,
) : ReplacementSpan() {
    private val underlineOffset = 6.dpToPx()
    ...
}

// 变更后
class SolidUnderlineSpan(
    private val textColor: Int,
    private val underlineColor: Int,
    private val underlineWidth: Float = 1f,
    private val underlineOffset: Float = 6f,  // 新增参数，单位 dp
) : ReplacementSpan() {
    private val offsetPx = underlineOffset.dpToPx()
    ...
}
```

### TextLine.kt 变更

```kotlin
// 变更前
val distance = (ChapterProvider.lineSpacingExtra * 10 - 11).coerceIn(-1f, 10f)
val lineY = height + distance.dpToPx()

// 变更后
val underlineOffset = ReadBookConfig.durConfig.underlineOffset
val lineY = height + underlineOffset.dpToPx()
```

## 成功标准

1. 用户可以在下划线粗细设置界面调节下划线距离
2. 距离设置实时反映在预览中
3. 距离设置保存后，阅读界面的下划线使用新距离
4. 所有下划线样式（实线、虚线、波浪、点线、双线）都使用统一的距离设置
5. 配置持久化，重启应用后保持设置
