# 已完成下载操作菜单设计

日期: 2026-05-21

## 需求

点击已完成的下载任务卡片，弹出 PopupMenu 提供操作选项。

## 菜单选项

1. **打开文件** — 通过 `DownloadManager.getUriForDownloadedFile()` 获取 URI，用系统 Intent 打开
2. **打开文件所在文件夹** — 跳转到 Downloads 目录
3. **复制路径** — 复制文件完整路径到剪贴板
4. **删除** — 保留原有删除功能

## 修改范围

| 文件 | 改动 |
|------|------|
| `DownloadManageScreen.kt` | SUCCESSFUL 卡片加点击事件 + PopupMenu |
| `DownloadManageViewModel.kt` | 添加 openFile / openFolder / copyPath 方法 |
