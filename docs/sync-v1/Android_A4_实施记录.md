# Android Sync-v1 A4 实施记录：书籍绑定请求与 ack

分支：`codex/sync-v1-android-a4-binding`
基线：`ae87041c7285d02448423633d9216e761a2eeb8c`

## 本阶段范围

A4 增加 Android 对 ready Kindle 设备 `binding_request` 事件的手动处理，并以不可变 ack 返回绑定结果。本阶段未修改协议、Schema、Kindle 插件、`Book`/`AppDatabase`、旧备份格式，也未实现进度、阅读时间或其他事件导入。

设置页新增“处理 Sync-v1 书籍绑定请求”入口和摘要，仅显示扫描设备数、绑定事件数、各终态数量、ack 发布/待发布数量及安全错误码；不显示 WebDAV URL、书籍 URL、事件正文或凭据。ViewModel 对 A1/A2/A3/A4 任务实施互斥，Repository 另有 single-flight 互斥锁。

## 事件和绑定规则

- 只扫描当前 WebDAV 服务与根目录下已验证为 `ready`、且协商模式为 `uuid_verified` 的设备事件目录。
- 事件解析前限制原始响应不超过 256 KiB；严格检查 JSON、重复/未知字段、UUID v4、文件路径与 payload 的设备及事件 ID 一致性，并直接对收到的原始字节计算 SHA-256。
- 非 `binding_request` 事件保持未处理，不写 rejected ack；无效绑定请求生成协议定义的 rejected ack。单个事件失败不会阻止后续事件。
- 绑定仅使用当前远端 A2 catalog 的原始标题和作者精确匹配：唯一候选为 `bound`，2–20 个候选为 `ambiguous`，无候选或超过上限为 `not_found`。`toc_revision=0` 不影响匹配。选择请求须引用同一服务/根目录下原有 ambiguous receipt，且设备、counter epoch、内容 ID 一致、sequence 与 event ID 为新值、所选作品属于原候选集合，否则返回 `rejected / invalid_event`。
- 不使用 URL、文件路径、凭据、摘要或模糊标题/作者匹配，也不创建书架中不存在的作品身份。`toc_candidates` 仅留在必要 receipt 字段中供诊断，不用于自动绑定。

## 独立数据库 v3 → v4

`legadoM_ink_sync.db` 使用 SQLiteOpenHelper 显式升级至 v4；未删除数据库、清表或启用 destructive fallback。升级新增：

- `sync_import_receipt`：事件摘要、必要的已校验事件字段、候选/最终结果、终态 ack 完整字节及其 SHA-256、确认时间和服务/根目录身份。
- `kindle_content_binding`：以 `device_id + counter_epoch + content_id` 为键保存最终 work_id，并引用独立 `work_identity`。
- `ack_publication_intent`：服务/根目录隔离的不可变路径、ack 原始字节、SHA-256、PUT 次数及发布状态。

新绑定、receipt 和 ack intent 在同一个 SQLite 事务内提交。receipt 不保存完整事件 JSON，也不保存 URL 或未知字段；ack intent 保留可逐字节恢复的原始 ack。v2 与 v3 升级测试确认既有 A1/A2/A3 注册、状态、WorkIdentity、目录发布状态及 pending intent 仍在。

## 幂等和 ack 发布

- `event_id` 是唯一去重键。同 ID 的已存 receipt 与当前设备及 payload 摘要一致时复用原终态 ack 的字节和 `acknowledged_at`；不同摘要只报告 `EVENT_ID_CONFLICT`，不修改原绑定、receipt 或 ack。
- ack 使用 `acks/<device_id>/<event_id>.json` 和 `GET → 404 → PUT → GET`。远端相同字节且 SHA-256 相同则幂等成功；不同内容报告 `ACK_CONFLICT` 且不覆盖。
- PUT 响应不明时先 GET；404 才能重试持久化的同一路径和同一原始字节，总 PUT 次数最多 2。已发布或待恢复 intent 在扫描新事件前优先处理；服务或根目录变化时不会跨作用域续传。
- 事件和 ack 均不删除。取消异常继续传播。

## 验证结果

2026-11-15 使用指定 F: SDK、Gradle 缓存和临时目录验证：

- 定向 JVM 测试：86/86 通过，其中 A4 `SyncV1BindingRepositoryTest` 13/13，A1/A2/A3 Sync-v1 回归 73/73。
- `SyncV1SQLiteStoreMigrationTest` 模拟器 instrumentation：2/2 通过（Android API 37），覆盖 v2→v4 与 v3→v4。
- `:app:assembleAppLegacyDebug` 与 `:app:assembleAppLegacyDebugAndroidTest` 构建成功。
- Debug APK：`app/build/outputs/apk/appLegacy/debug/LegadoM-Ink_兼容版_3.26-beta30_legacy.apk`，大小 53,524,004 字节，SHA-256 `395925DB5A683A0455201252392A234D5917B3098507EBE4FBE0B53AAA515265`。
- AndroidTest APK：`app/build/outputs/apk/androidTest/appLegacy/debug/app-appLegacy-debug-androidTest.apk`，大小 1,542,703 字节，SHA-256 `BC51D21BC6DDFAEDEA901EABCE0923B0731A28947988D731D95A554A82C0C853`。
- `git diff --check` 在实施记录更新后执行。

未访问真实 WebDAV，尚未进行 Kindle A4 真机联调。A4 仍待源码复审和真实 WebDAV 验收；本分支未提交、未推送。

## A4 分支封存前复验（2026-11-18）

- A4 定向 JVM 测试 `SyncV1BindingRepositoryTest`：13/13 通过。
- `SyncV1SQLiteStoreMigrationTest` 模拟器 instrumentation：2/2 通过（Medium_Phone，Android API 37），覆盖 v2→v4 与 v3→v4。
- `:app:assembleAppLegacyDebug` 与 `:app:assembleAppLegacyDebugAndroidTest` 均成功。
- 最新 Debug APK：`app/build/outputs/apk/appLegacy/debug/LegadoM-Ink_兼容版_3.26-beta30_legacy.apk`，53,523,992 字节，SHA-256 `C2EC973C4EFAC35E2D5B78A7E234E5FDD0F6A0F5F230D109FA235BC6767CB6F0`。
- 最新 AndroidTest APK：`app/build/outputs/apk/androidTest/appLegacy/debug/app-appLegacy-debug-androidTest.apk`，1,542,703 字节，SHA-256 `BC51D21BC6DDFAEDEA901EABCE0923B0731A28947988D731D95A554A82C0C853`。
- 本次 Android 工作区没有 K3/K3-B 真机或真实 WebDAV 联调证据，因此不记录 K3/K3-B 成功结论或事件数量；本轮只确认上述本地自动化验证。

复验后 `git diff --check` 通过。以上记录不代表真实 WebDAV/Kindle 联调完成。
