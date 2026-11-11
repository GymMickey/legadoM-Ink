# Sync-v1 Android A1 实施记录

日期：2026-11-11
分支：`codex/sync-v1-android-a1`
协议基线：`626acbdf186d837ccc12b855bf15920a4f0fbafb`

## 范围

本阶段仅处理 Kindle 设备注册并发布首份 Android `device-state`。未实现 catalog、chapters、进度、阅读时间、事件/ack 导入或 exporter/importer。

## 实现

- 设置页增加“处理 Sync-v1 设备注册”手动入口。界面显示设备 ID、运行/处理结果、create mode、revision 和安全错误码，不显示账号、密码、Authorization 或完整 WebDAV URL。
- 使用经过 WebDAV 验证的配置快照建立 A1 客户端；配置身份变化时停止请求。A1 客户端禁用重定向和 OkHttp 隐式连接重试。
- 初始化协议目录。缺目录时发送 MKCOL；收到 405/409 只有在后续 PROPFIND 确认目标确实为集合后才视作成功。
- 注册列表限制为直接子项。文件名必须是小写 RFC 4122 UUID v4；读取注册对象最多保留 64 KiB，严格 UTF-8/JSON 解析并执行当前 `device-registration.schema.json` 的字段、类型、版本、枚举和格式约束。超限对象不解析，但以流式方式计算完整原始字节 SHA-256，并发布 `invalid_registration`。
- 注册 payload 的 SHA-256 始终按 WebDAV 返回的原始字节计算。首次见到的注册摘要写入独立记录；同一 `device_id` 后续字节摘要改变时按 `device_id_conflict` 拒绝，不接受覆盖后的注册内容。
- 状态文件按 `android/device-state/<device_id>/<revision>-<snapshot_id>.json` 发布。revision 取最高有效 revision 加一，缺号允许；同 revision 出现多个 snapshot 路径或不同有效内容时报 `revision_conflict`。
- device-state 只编码一次。写前在独立数据库事务中保存 revision、snapshot ID、路径、完整原始 JSON、摘要、PUT 次数和非敏感配置标识；PUT 只复用已保存字节。流程为 GET → 404 → PUT → GET 回读，逐字节和 SHA-256 均一致才确认。结果不明时先 GET；只有 404 才复用相同 intent 重试，最多两次 PUT。
- 独立 SQLite 文件为 `legadoM_ink_sync.db`，包含注册记录、device-state 发布 intent 和同步状态；持久本地状态由枚举限制为 `unregistered`、`uploaded`、`ready`、`rejected`。运行中的 `registering` 不落盘，`waiting_android` 不作为持久状态。

## 验证

- JVM 注册仓库测试：21 项通过，0 失败、0 错误、0 跳过。
- `:app:assembleAppLegacyDebug`：成功。
- `:app:assembleAppLegacyDebugAndroidTest`：成功。
- 魅族 21 真机 A1 MockWebServer 仪器测试：3/3 通过。
- 原 WebDAV 真机回归测试：21/21 通过。
- 真实 WebDAV 联调：Android 发布首份 device-state，`create_mode=uuid_verified`；Kindle 从 `uploaded/waiting_android` 刷新为 `ready`，revision 为 1。
- 幂等复测：Android 再次处理同一注册后，Kindle 仍为 `ready`、revision 仍为 1；未生成新 revision 或重复状态。
- Kindle 显示“事件上传尚未启用”符合 A1 范围，事件上传未实现。
- 本轮实际执行的 JVM 报告：`app/build/test-results/testAppLegacyDebugUnitTest/TEST-io.legado.app.help.syncv1.SyncV1RegistrationRepositoryTest.xml`。
- 主 Debug APK：`app/build/outputs/apk/appLegacy/debug/LegadoM-Ink_兼容版_3.26-beta33_legacy.apk`，50,551,820 bytes，SHA-256 `D9523A52A4FD459BA472F7E89389FA9050812A5EDF52C7B418EAAB757591B795`。
- 测试 APK：`app/build/outputs/apk/androidTest/appLegacy/debug/app-appLegacy-debug-androidTest.apk`，1,538,315 bytes，SHA-256 `FE323012A60535FD001233810149AEA608DB9DD373A9DE84C0D90C23E6676CAA`。

## 待联调与风险

- `uuid_verified` 按协议提供个人可信 WebDAV 的兼容性校验，不具备 `If-None-Match: *` 的强原子创建保证；极端并发写入仍可能产生竞争，最终由回读和 revision 冲突检测阻止静默接受。
- 本次真机与真实 WebDAV 联调通过；其他 WebDAV 服务端的 PROPFIND/MKCOL 实现差异仍需按目标服务验证。
- JSON Schema 约束目前由严格解析器按冻结的注册和 device-state Schema 实现；若协议 Schema 后续改变，Android 校验器需同步更新。
- 旧 Book/ReadRecord 数据库、原备份文件及其格式未修改。
