# 下载管理界面增强设计

日期: 2026-05-21

## 需求

1. **Tab 分组显示** — 用 TabRow 切换：全部 / 下载中 / 已暂停 / 已完成 / 失败，每个 Tab 显示数量角标
2. **实时瞬时下载速度** — 通过轮询间隔的下载量差值计算 bytes/sec，UI 显示格式化速度（如 2.5 MB/s）
3. **显示下载来源** — 同时显示来源页面 URL 和文件直链 URL，灰色小字，长按可复制

## 修改范围

| 文件 | 改动 |
|------|------|
| `service/DownloadState.kt` | `DownloadTask` 新增 `speed`、`sourceUrl`、`downloadUrl` 字段；新增速度计算逻辑 |
| `service/DownloadService.kt` | `startDownload()` 接收 sourceUrl 参数；轮询时计算速度并更新 task |
| `model/Download.kt` | `start()` 方法新增 sourceUrl 参数 |
| `ui/download/DownloadManageScreen.kt` | TabRow 组件；速度显示；来源信息展示 |
| `ui/download/DownloadManageViewModel.kt` | Tab 过滤逻辑 |
| 调用 `Download.start()` 的地方 | 传入来源页面 URL |
