# Android Sync-v1 A5 实施记录

## 基线与范围

- 工作分支：`codex/sync-v1-android-a5-progress`
- A4 基线：`3e14b883e4bbed72c5628fd3b584c6df2ea7b891`
- 冻结进度协议合并提交：`e9fffd2e1ebc197e88431efd1dc5220cfbf0dbc5`
- 协议来源：`8d76d786cbd5e51dcb889e8ad7d54890416dfbb6`
- 本记录对应的实现尚未提交或推送。

A5 实现 EPUB 进度 baseline、初始 progress-state、按当前 service/root 恢复远端状态、TOC 安全重投影、progress event 幂等导入，以及 `progress-state → catalog → ack` 可恢复发布。没有实现阅读时间、TXT、自动同步、Kindle 插件改动或 Android 主进度投影；没有修改 `Book`、`ReadRecord`、`AppDatabase`、`bookshelf.json` 或旧备份格式。

## A5-A：baseline 与初始状态

- 独立同步数据库继续使用 `legadoM_ink_sync.db`，版本由 4 升至 5；显式非破坏升级新增 progress baseline、state intent、event ledger、progress receipt 和 catalog dirty 状态。v2、v3、v4 升级均保留既有 A1–A4 数据和 pending intents。
- 默认位置字段不是“从未阅读”的证据。只有权威未读证据才能得到 `zero_confirmed`；无法确认时为 `unavailable`。现有 Book 字段只用作当前位置候选，不写回主数据库。
- 初始状态和重投影只依赖当前 `serviceIdentity + rootIdentity + work_id` 已确认的 A3 chapters 原始快照。只在索引和原始章节标题精确匹配时重投影；否则保留旧状态并派生 `progress_toc_stale`。
- catalog 为每个当前作品发布 baseline 和 progress revision。旧 catalog 缺少 A5 字段时按 `legacy_unknown` 处理，不能授权 progress 上传；升级通过新的不可变 catalog revision 表达新字段，不修改旧对象。
- 每本作品独立发布和回读 progress-state，整批最多发布一个 catalog；状态、catalog 和恢复意图均按 service/root 隔离，允许部分成功。

## A5-B：事件与恢复链

- 仅把声明为 `progress` 的事件交给 A5；A4 `binding_request` receipt/ack 不被 A5 修改，`reading_time` 和未知类型保持未处理。
- 事件按原始 UTF-8 字节校验、计算摘要和幂等；校验设备注册 epoch、能力、绑定、baseline、当前确认 TOC、章节上下文及两个独立 revision。sequence 缺号不阻塞其他事件。
- `applied` 的 state intent、ledger、receipt 与 catalog dirty 在独立数据库事务中原子保存；同一 work 有 `projection_pending` 时不会并发应用后续事件。发布依次执行 progress-state、catalog、ack；重启续用已持久化原始字节，不重复增加 revision 或重编码。
- state/catalog 未确认时不发布 ack。ACK、state 的传输结果不明时先 GET；只对原 intent 有限重试。主 `Book` 进度和 `ReadRecord` 保持不变。
- 设置页提供手动初始化/处理入口和精简状态摘要，不显示凭据、完整 WebDAV URL、bookUrl 或事件正文。

## 验证结果

### 定向 JVM 测试

最终执行的 A1–A5 定向测试共 **122/122 通过**，失败 0、错误 0、跳过 0：

| 测试组 | 数量 |
| --- | ---: |
| A5 progress protocol | 9 |
| A5 progress repository | 27 |
| A1 registration | 21 |
| A2 catalog | 13 |
| A2 work identity | 8 |
| A3 chapters | 31 |
| A4 binding | 13 |

覆盖 baseline 保守判定、确认 TOC 映射、revision 0、stale 和冲突、sequence 缺号、幂等重放、设备能力、事件上下文、事务前状态检查、单 work pending、A4 receipt 隔离、重启恢复及 state/catalog/ack 顺序等。

### Android 仪器测试

API 17 `Medium_Phone(AVD)`：

- `SyncV1SQLiteStoreMigrationTest`：**4/4 通过**，包含 v2/v3/v4 到 v5 的非破坏升级，以及在 progress receipt 插入失败时验证 state intent、ledger、receipt 一起回滚且既有 A4 receipt/ack 不变。
- `WebDavSyncV1Test`：**21/21 通过**。
- `SyncV1WebDavMockServerTest`：**4/4 通过**。

仪器测试只使用本地模拟器和 MockWebServer，没有访问真实 WebDAV。

### 构建产物

以下命令成功：

```text
:app:assembleAppLegacyDebug :app:assembleAppLegacyDebugAndroidTest --no-daemon
```

- Debug APK：`app/build/outputs/apk/appLegacy/debug/LegadoM-Ink_兼容版_3.26-beta30_legacy.apk`
  - applicationId：`io.legado.app.debug`
  - 大小：53,729,929 bytes
  - SHA-256：`DA11D7AF9E380830D84F0AC7BCC328374020DEDD2988F60B6B416C7C1ABC5CF7`
  - Debug 证书 SHA-256：`f5544859c5144b7e3b10c9e47837bedcd73ed4f806b32279cf96a12cb0501176`
- AndroidTest APK：`app/build/outputs/apk/androidTest/appLegacy/debug/app-appLegacy-debug-androidTest.apk`
  - 大小：1,538,633 bytes
  - SHA-256：`4EC4611968AE969CA417365E79F8D572C5F87CC8A4F48541E5EDB327EB7B3277`

## 尚未完成

- 未实现 K4-B、阅读时间、自动同步、TXT、Android 主进度投影及 Kindle 代码。

## 最终验收：A5、WebDAV 兼容与 K4-A

### WebDAV 请求与连接策略

- 请求风暴根因是旧的 `scanRemoteStates()` 对每部作品反复确保目录并执行 PROPFIND。现在远端发现只读；revision 0 且没有本地状态或待恢复意图的作品不扫描 progress 目录。只有准备发布 progress-state 时才按顺序确保公共父目录和 work 目录，批次内共享父目录只初始化一次；目录 404 视为不存在，不转成全局服务器故障。
- 移除了“允许不安全 WebDAV”开关。HTTP 可直接连接；HTTPS 由 Android 系统信任库验证，不默认跳过证书或主机名校验。旧偏好值不再读取，也不参与新配置身份。
- 普通备份和恢复真机验证通过。断网、429、5xx 等临时故障保留账号、密码和完整配置快照；401/403 显示认证失败；对象级 404/409 不污染全局连接状态，后续成功操作可恢复 AVAILABLE。

### 旧 configIdentity 恢复兼容

- 新 intent 使用不含旧开关的新 `configIdentity`。恢复时，Session 仅从当前 URL、账号和密码计算当前身份，以及旧算法中 `allowInsecure=false/true` 两种候选；不读取 SharedPreferences 中的旧开关值。
- 兼容候选只允许校验已持久化 intent。`serviceIdentity`、`rootIdentity` 和 `session.isCurrent()` 仍严格匹配。URL、根目录、账号或密码改变后，旧身份不再被接受。
- 恢复沿用 intent 原始字节、摘要、路径、revision、snapshot 和 PUT 次数。远端相同对象幂等确认；对象不同仍冲突；新建 intent 继续写入新身份。

### 最终 Android 与 Kindle 真机验收

Android A5 最终结果：

- phase：`unchanged`
- baseline：`available 3 / unavailable 182`
- `initialStatesPublished=0`、`stateIntentsPending=0`、`catalogIntentsPending=0`
- `failed=0`、`scannedEvents=0`、`acknowledgementsPending=0`，无错误码

Kindle K4-A 在线刷新：

- catalog revision：`7`
- `available=3`、`current=3`、`unavailable=182`
- `zero_confirmed=0`、`legacy_unknown=0`、`progress_toc_stale=0`
- `missing=0`、`pending=0`、`conflict=0`、失败 `0`

revision 7 是此前已持久化 catalog intent 的恢复结果，包含第三个 available baseline；不是重复发布产生的不必要 revision。此前 K4-A catalog revision 6 的状态保持不变。

### 最终定向验证与 APK

- Sync-v1 progress、catalog、chapters、registration 定向 JVM 测试：`99/99` 通过；其后 progress repository 测试再次运行：`33/33` 通过。覆盖 A5 的 revision 0 请求量、旧 true/false 身份 intent 恢复、原对象幂等确认及配置身份隔离。
- Debug Kotlin 编译和 `:app:assembleAppLegacyDebug` 成功；最终 diff 检查通过。该次收口不重复构建或访问真实 WebDAV。
- Debug APK：`app/build/outputs/apk/appLegacy/debug/LegadoM-Ink_兼容版_3.26-beta30_legacy.apk`
  - applicationId：`io.legado.app.debug`
  - 大小：`53,758,897` bytes
  - SHA-256：`23B9533E196C97804B68086066B8AB03AE687E89D65C55E12FD8BC635770BBB1`
