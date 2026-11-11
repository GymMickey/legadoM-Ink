# Sync-v1 Android A2 实施记录

日期：2026-11-11

分支：`codex/sync-v1-android-a2-catalog`

基线：`dceef640ed81f620560c3db826e7222d0164bd49`

## 范围

A2 为 Android 侧稳定作品身份、独立身份映射和手动发布只读 catalog 不可变快照。本阶段没有实现章节目录、进度、Kindle 事件扫描/导入、ack、阅读时间或自动同步。

## 实现

- `legadoM_ink_sync.db` 从 A1 版本 1 通过显式、仅新增表的升级迁移至版本 2；保留 A1 注册记录、device-state、发布 intent 和同步状态，不删除数据库、不清表、不使用破坏性 fallback。
- 新增 Android catalog 发布者 UUID、`WorkIdentity`、`WorkAlias`、作品字段摘要/`updated_at`、catalog 发布状态及不可变发布 intent。`updated_at` 表示书目字段变更时间，由同步库独立维护。
- 完整本地 `bookUrl` 映射到稳定的小写 UUID v4 `work_id`；只有调用点明确提供旧、新 URL 的换源操作才关联别名。不同 URL 不按标题或作者合并。可安全发布的 URL 摘要按原始 URL UTF-8 字节计算；含凭据或无法安全判断的 URL 不输出摘要。
- catalog 从当前书架读取轻量书目字段，作品按 `work_id` 排序，拒绝超过 2500 项或原始 UTF-8 JSON 超过 1 MiB 的内容；A2 未有章节和进度数据时使用独立库中的初始 revision 0。正文和章节缓存不进入 catalog。
- catalog JSON 仅序列化一次。发布前在独立库事务中保存 revision、snapshot ID、不可变路径、完整字节、SHA-256、PUT 次数和非敏感 WebDAV 服务/根标识。使用 A1 的 `uuid_verified` GET → 404 → PUT → GET 回读流程；未知结果先 GET，最多两次 PUT，始终复用相同 intent 字节，不覆盖远端快照。
- 设置页增加手动“发布 Sync-v1 书架目录”入口，只显示作品数、revision、发布结果和安全错误码，不显示 URL、凭据或完整 catalog。
- 签名边界固定：`appLegacyDebug` / `io.legado.app.debug` 不指定自定义签名，使用 Android 默认 `C:\Users\L\.android\debug.keystore`，证书 SHA-256 为 `f5544859c5144b7e3b10c9e47837bedcd73ed4f806b32279cf96a12cb0501176`。Release 保持 `resolvedReleaseStoreFile` / `signingConfigs.myConfig`，正式密钥路径为 `F:\Leagdo-mickey\LegadoM-Ink-release-beta32\legado.jks`，证书 SHA-256 为 `8aff094f3196b08db32c8dbcfd2307e4a4bc5e1af574adac8cc47b0fca20f078`。密码字段未读取或更改。

## 验证

- A2 定向 JVM 测试：`SyncV1WorkIdentityTest` 与 `SyncV1CatalogRepositoryTest` 共 **17/17 通过**。覆盖稳定身份、显式换源、保守去重、别名恢复冲突、不可变发布/幂等、语义 revision、PUT 未知结果恢复与两次上限、revision 冲突、2500 项和 1 MiB 限制、无 ready 设备等。
- 首次 `:app:assembleAppLegacyDebug` 使用隔离的 `GRADLE_USER_HOME`，未加载外部 Gradle 签名配置，因此在签名阶段失败；不是源码编译错误。之后使用既有外部 Gradle 配置并修正其过期 keystore 路径，构建曾成功。发现 Debug 误用 Release 签名后，删除 `debugConfig` 并让 AGP 使用默认 Debug 签名；本次唯一一次 `:app:assembleAppLegacyDebug --no-daemon` **成功**。构建过程未读取或输出密码，未复制 keystore，Release 签名逻辑保持原样。
- 最终 Debug APK：`app/build/outputs/apk/appLegacy/debug/LegadoM-Ink_兼容版_3.26-beta33_legacy.apk`；包名 `io.legado.app.debug`；APK SHA-256 `9F79C3568906E7FD118DB4E826C57766BBDEE07A2F78EC38F21D618683F8BD1D`；证书 SHA-256 `f5544859c5144b7e3b10c9e47837bedcd73ed4f806b32279cf96a12cb0501176`。先前误用 Release 证书生成的 Debug APK 已被本产物取代，不应继续使用。
- 证书和包名校验通过后执行 `adb install -r`，但设备 `461QYFDP22BRE` 当时已不在 adb 列表中，安装未完成；未卸载应用、未清除数据，未对手机做其他更改。
- 最终签名配置修正后未重跑 A2 JVM 测试；采用此前已通过的 17/17 结果。

## 未实现与待验证

- 未实现 chapters 导出、progress-state、binding request、Kindle 事件/ack、阅读时间及原生 ReadRecord 投影、自动同步和远端对象清理。
- 未运行 AndroidTest/真机测试，也未连接或写入真实 WebDAV。独立数据库 A1→A2 升级采用非破坏性新增表迁移，仍需后续真机升级验证；最终 Debug APK 等待真机重新连接后安装。
- 未修改 Book 表、AppDatabase schema、`bookshelf.json`、原备份格式、协议 Schema 或 Kindle 代码。

## 本地书籍根目录迁移身份修复（2026-11-12）

- 根因：Book.getLocalUri() 在旧本地 URI 失效后，会从当前书籍根目录按文件名重新定位并更新 Book.bookUrl；该批量路径修正只保存了 Book，没有把明确的旧 URI → 新 URI 迁移告知 Sync-v1，因此 catalog 后续为新 URI 分配了新的 work_id。
- 修复：两处本地文件重定位入口都先保存旧/新 URL；只有 Book.save() 成功后才在同步库事务中关联别名。旧别名保留，切回旧根目录继续使用原 work_id。同 URL 不触发保存或映射；未按书名/作者合并。
- 本地 content:// 路径只存放在独立同步库别名映射中；不生成或发布 catalog URL 摘要。路径变化不改变公开书目字段，因此 updated_at 和 catalog revision 保持不变，重复发布返回 UNCHANGED。
- 定向 JVM 测试：SyncV1WorkIdentityTest 8/8、SyncV1CatalogRepositoryTest 13/13，通过；覆盖迁移与切回身份稳定、Book 保存失败不提前写映射、同路径无操作、公开 catalog 未变时复用 revision/snapshot。
- :app:assembleAppLegacyDebug --no-daemon：成功。Debug APK 包名 io.legado.app.debug，APK SHA-256 6D7BCB3CECCEF3702C5FBFA62B8876CC72E29F87EA533FD01A562D0C31F8DC9B；证书 SHA-256 f5544859c5144b7e3b10c9e47837bedcd73ed4f806b32279cf96a12cb0501176。本轮未安装 APK、未连接真机或访问真实 WebDAV。
- 本修复未修改 Book/AppDatabase schema、旧备份格式、协议 Schema 或 Kindle 代码；未删除远端历史快照。
## A2 最终验收记录（2026-11-12）

- 定向 JVM 测试：SyncV1WorkIdentityTest 与 SyncV1CatalogRepositoryTest 共 **21/21 通过**。
- :app:assembleAppLegacyDebug 成功。APK 包名为 io.legado.app.debug；APK SHA-256：6D7BCB3CECCEF3702C5FBFA62B8876CC72E29F87EA533FD01A562D0C31F8DC9B；Debug 证书 SHA-256：f5544859c5144b7e3b10c9e47837bedcd73ed4f806b32279cf96a12cb0501176。
- A1 有效 Kindle 设备保持 ready / uuid_verified / revision 1。首次 catalog 发布为 revision 1；修改一本书的书名后发布 revision 2。
- revision 1 与 2 均包含 185 个作品，没有新增或删除作品，也没有重复 work_id。改名作品的 work_id 不变，仅其 title 与 updated_at 改变；其他 184 本作品未变化。两份 catalog 的 catalog_revision、snapshot_id 与不可变文件名一致。
- 调整本地书籍根目录时曾暴露路径迁移未关联 WorkAlias 的问题，并留下远端 revision 3 作为测试历史。现已修复 Book.getLocalUri() 的两个本地文件重定位入口：Book 更新成功后才关联旧、新路径，保留旧路径别名，不按书名或作者猜测合并；content:// 地址不发布摘要。修复后的路径迁移与重复发布不增加 catalog revision，也不改变公开字段 updated_at。
- 远端 revision 1、2、3 按不可变协议保留，没有删除或覆盖。历史 invalid_registration 设备记录不会阻塞当前 ready 设备，并按 v1 规则保留。
- 本记录基于已提供的真机和真实 WebDAV 验收结果；本次收尾未重新构建、运行测试或访问真实 WebDAV。