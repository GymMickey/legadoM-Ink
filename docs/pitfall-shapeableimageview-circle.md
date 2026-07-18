# 坑：ShapeableImageView 不适合圆形样式选择器场景

## 现象

W1 将 `item_read_style.xml` 中的 `CircleImageView` 替换为 Material `ShapeableImageView`（`shapeAppearanceOverlay="@style/ShapeAppearance.Circle"`）后，阅读样式选择器中**整排圆圈均显示不完整**——不是 RecyclerView 滚动裁切问题，而是每个圆的描边/填充渲染异常。

## 根因

`CircleImageView` 是自定义 View，用 `canvas.clipPath` 绘制圆形 + 内部描边（border 完全在圆内）。  
`ShapeableImageView` 的渲染路径不同：
1. 描边居中于边缘（0.5dp 外侧被父容器 `clipChildren` 裁切）
2. `centerCrop` 矩阵变换在 shape mask 应用之前，与 Material shape 系统存在交互差异
3. Material3 的 `ShapeableImageView` 并非为这种紧凑 48dp 圆形选择器场景设计

## 最终方案

**回退 `item_read_style.xml` 为原始 `CircleImageView`，恢复 `CircleImageView.kt`。**

这是整个 W1 替换中**唯一回退的 1 处**。其他 4 处 ShapeableImageView 替换（非圆形、无描边、无紧凑 hit-test 需求）保持不变：

| 文件 | 组件 | 状态 |
|------|------|------|
| `item_book_source.xml` | ShapeableImageView | ✅ 保留 |
| `item_explore_show_grid.xml` | ShapeableImageView | ✅ 保留 |
| `item_explore_show_waterfall.xml` | ShapeableImageView | ✅ 保留 |
| `item_search.xml` | ShapeableImageView | ✅ 保留 |
| **`item_read_style.xml`** | **CircleImageView** | **🔄 回退** |

## 避坑指南

以后遇到类似场景，**不要用 ShapeableImageView 替代自定义圆形 View**——尤其在以下情况：
- 需要精确的圆形 clip + 内部描边
- 紧凑尺寸（≤48dp）
- 有圆形 hit-test 需求（`isInView` 在 ReadStyleDialog 中虽已移除，但其他场景可能用到）

`ShapeableImageView` 适合：圆角图片、简单圆形头像（无描边）、大尺寸场景。不适合精细圆形选择器。
