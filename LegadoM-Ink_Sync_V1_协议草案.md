# LegadoM-Ink Sync v1 协议草案

> 状态：评审草案，尚未冻结实现。本文只定义协议、JSON Schema 和固定样例，不包含同步业务代码、Room 实体或数据库迁移。

## 1. 目标与非目标

Sync v1 在现有 WebDAV 能力上增加轻量目录、不可变事件、确认回执和可重建快照。Android 保留 Legado 数据语义并产生最终状态；Kindle/KOReader 设备保留阅读器本地事实并提交事件。WebDAV 只负责文件传递，不承担事务或唯一数据备份职责。

本阶段不更改 Book 表、AppDatabase schema、`bookshelf.json`、旧备份 ZIP 格式、章节正文或 Kindle 阅读器数据格式；不实现同步数据库、导入器、导出器或同步 UI。

协议版本固定为 `protocol_version: "sync-v1"`，当前 JSON 结构版本为 `schema_version: "1.0.0"`。不兼容字段变化必须提升 `schema_version`；改变行为或语义须另行评审协议版本。

## 2. 兼容旧用户与隐私边界

- 现有 Book 表、AppDatabase schema、`bookshelf.json` 和原备份格式保持不变。Sync v1 数据只写入独立数据库 `legadoM_ink_sync.db`。
- 老用户升级后按需生成稳定 `work_id`，无需清空书架或重新导入。首次生成与后续重建须由 Android 的身份映射事务保证稳定。
- `WorkAlias` 在 Android 本地把一个或多个 `bookUrl` 映射到同一 `work_id`。换源只增加/更新别名，不新建作品身份。同步目录只发布 `book_url_sha256`，不发布原始 `bookUrl`。
- 若独立同步库丢失或用户恢复旧版备份，Android 只读既有 Book 的 `bookUrl`，计算可发布的别名摘要并与 catalog 匹配，以恢复 `WorkAlias/work_id`；未匹配项才按需分配新 `work_id`。不得要求改 Book 表、`bookshelf.json` 或旧备份。含凭据 URL 的摘要不发布，因此这类别名跨库丢失后的自动恢复规则仍待双方确认。
- 所有路径 ID（`work_id`、`device_id`、`event_id`、`snapshot_id`）使用小写 UUID；Schema 同时校验 UUID 格式和固定字符模式，因此不能含 `/`、`\\`、点段或路径穿越字符。
- 不导出正文、EPUB、TXT、图片缓存、`bookChapterCache.json` 或其他缓存。章节文件只包含目录元数据和可选的规范化文本指纹/字符数。
- 任何协议对象都不得含 WebDAV 密码、Authorization、Cookie、访问令牌、私钥或其他凭据。不得把它们放入 URL、日志、事件或样例。
- `book_url_sha256` 是原始 `bookUrl` UTF-8 字节的 SHA-256，仅用于别名匹配；它不是正文指纹，也不是密码存储形式。含凭据的 `bookUrl` 不得发布其哈希；该别名只保留本地，直至实现方定义安全的脱敏 canonical form。

## 3. 双端职责

### Android

- 管理稳定 `work_id`、本地 `bookUrl` 别名和作品元数据。
- 导出轻量书架目录及已加载/已知章节目录，不导出正文。
- 把 Kindle 的本地 TOC 位置转换成 Legado 当前 TOC 索引；判断章节匹配和指纹。
- 在事务中幂等导入阅读时间事件、进度事件并记录回执。
- 管理最终进度、绝对投影、递增的 `progress_revision` 和 ack。
- 发布 catalog、chapters、progress-state 和 Android 生成的快照。

### Kindle / KOReader

- 保留 KOReader 本地事实：`content_id`、TOC、XPointer、可见字符位置和阅读时间。
- 每个事实变化写成新的不可变事件；事件不修改、不复用、不依赖连续 sequence。
- 下载 Android 的目录、最终状态与 ack；以 ack 判断事件是否已处理，并按需重新上传同一个 `event_id`。
- 不重建 Android 备份 ZIP，不把自己的本地状态直接覆盖 Android 的进度状态。

## 4. WebDAV 路径、所有权与版本

根目录为 `legadom_ink_sync/v1/`。相对路径区分大小写。文件名中的 ID 为密码学安全随机源生成的规范小写 UUID v4，不包含斜杠；线上 UUID 字段统一满足 `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`。

```text
legadom_ink_sync/v1/
  devices/<device_id>.json
  android/device-state/<device_id>/<state_revision>-<snapshot_id>.json
  android/catalog/<catalog_revision>-<snapshot_id>.json
  android/chapters/<work_id>/<toc_revision>-<snapshot_id>.json
  android/progress/<work_id>/<progress_revision>-<snapshot_id>.json
  events/<device_id>/<event_id>.json
  acks/<device_id>/<event_id>.json
  snapshots/<device_id>/<snapshot_id>.json
```

| 路径 | 写入方 | 读取方 | 覆盖规则 | 版本/并发规则 |
|---|---|---|---|---|
| `devices/<device_id>.json` | Kindle | Android、对应 Kindle | 不可覆盖 | 注册对象包含支持的创建模式与定位能力。上传并回读后 Kindle 本地保持 `uploaded`，等待 Android 状态；注册流程见 4.2。 |
| `android/device-state/<device_id>/<state_revision>-<snapshot_id>.json` | Android | Android、对应 Kindle | 不可覆盖 | 第一份 device-state 固定按 `uuid_verified` 发布，用它告知协商结果；Kindle 验证 `ready` 后，后续 device-state 与其他对象才按已选模式创建。每次决策先持久化 revision、snapshot、路径、原始字节及摘要。 |
| `android/catalog/<catalog_revision>-<snapshot_id>.json` | Android | Android、Kindle | 不可覆盖 | catalog 是带 revision 的不可变投影。路径 revision 与文件 `catalog_revision`、`snapshot_id` 必须相同。 |
| `android/chapters/<work_id>/<toc_revision>-<snapshot_id>.json` | Android | Android、Kindle | 不可覆盖 | 每作品 TOC 是不可变投影；路径中的 work ID、TOC revision 和 snapshot ID 必须与文件内容一致。 |
| `android/progress/<work_id>/<progress_revision>-<snapshot_id>.json` | Android | Android、Kindle | 不可覆盖 | progress revision 只在 Android 成功应用新事件后递增；路径字段必须与绝对状态内容一致。 |
| `events/<device_id>/<event_id>.json` | 事件来源设备 | Android、来源设备 | 不可覆盖 | 创建方式由已选 `create_mode` 决定。`create_mode` 不写入事件 payload。 |
| `acks/<device_id>/<event_id>.json` | Android | Android、对应来源设备 | 不可覆盖 | Android 事务提交后才创建。ack 的创建方式由已选模式决定；`create_mode` 不写入 ack。 |
| `snapshots/<device_id>/<snapshot_id>.json` | Android（快照生产者） | Android；Kindle 可选读取 | 不可覆盖 | 使用已选 `create_mode`；快照记录生成时已包含的最高来源 sequence，但不能用缺号阻塞事件导入。Kindle v1 不要求读取 snapshot，snapshot 不能作为增量事件导入。 |

所有投影和状态文件均为不可变对象；禁止 `latest.json` 等可覆盖指针。Android 发布前持久化 revision、snapshot ID、目标路径、完整原始字节与 SHA-256；任何重试必须复用全部这些值。选择最高有效 revision，revision 缺号允许。若同一 revision 出现多个 snapshot ID 或不同原始字节/摘要，必须报告 `revision_conflict`，不得任意择一，也不得静默回退到旧状态。候选路径的 revision、snapshot ID 和作品/设备 ID 必须与对象内容完全一致。v1 不删除远端事件；ack 不是阅读数据的唯一备份。

### 4.1 不可变创建模式

`create_mode` 有且只有两种：

- `conditional_create`：强条件创建。服务端必须执行 `If-None-Match: *`；创建只在目标不存在时成功。严格不可覆盖要求使用此模式。若条件能力未经验证、被忽略或响应语义不符合约定，禁止上传真实事件。
- `uuid_verified`：UUID v4 + 上传前检查 + 上传后回读的兼容模式。适用于受信任、单账号、非恶意多写者的个人 WebDAV；不保证抵抗恶意写入者或极端并发竞争。所有 UUID（包括 device/event/snapshot ID）必须由密码学安全随机源生成。严格不可覆盖要求不能依赖此模式。

`conditional_create` 使用服务端强制的 `If-None-Match: *`。2xx 表示新对象创建；`412` 必须 GET 并比较目标的完整原始字节及 SHA-256，相同才视为幂等成功，不同则冲突；`409` 绝不能视为成功。超时/结果不明时先 GET 并按相同的字节+摘要规则处理；只有确认 404 后才可对同一 intent 重试。Android 只有在服务端条件创建能力得到验证后才能选择该模式。

Kindle 注册对象通过上传/回读后，Android 根据 `supported_create_modes` 和已验证的 WebDAV 服务能力选择最终模式，并在第一份不可变 device-state 中返回。为消除协商循环，第一份 device-state 无条件使用 `uuid_verified` 状态机发布，不依赖它所携带的协商结果；Kindle 按 `uuid_verified` 回读并验证这份状态、确认 `status: ready` 后，后续 device-state、事件、ack 与投影才使用其中选定的 `create_mode`。最终 `create_mode` 不进入每个 event 或 ack。注册 bootstrap 阶段不允许上传真实事件，且不得在能力未经验证时选择 `conditional_create`。

`uuid_verified` 的单对象状态机冻结如下：

1. 先 GET 目标路径。`200` 时比较完整原始字节和对这些字节计算的 SHA-256：二者都相同则幂等成功；任一不同则冲突并停止。`404` 才允许 PUT。
2. PUT 必须使用已持久化的相同路径、对象 ID 和原始字节。PUT 返回 2xx 后必须 GET 回读，并逐字节及 SHA-256 双重校验；回读不一致即冲突并停止。
3. PUT 超时或结果不明时先 GET：完全相同则成功；不同则冲突；`404` 才允许重试一次相同原始字节。最多 2 次 PUT（首次加最多一次重试）；达到上限后停止并保留 intent。
4. 任何未知结果都不得重新序列化 payload 或生成新 `event_id` 掩盖结果。相同字节意味着摘要也须相同；摘要相同仍需逐字节相同，不能只凭哈希判定对象相同。

事件 intent 在首次网络请求前必须持久化 `create_mode`、目标路径、`event_id`、`payload_sha256`、完整原始字节、PUT 尝试次数，以及不含凭据的 WebDAV 服务标识和远端根目录标识。非事件对象的 intent 使用相应对象 ID（如 snapshot ID）代替 event ID。重试只能读取并复用 intent；禁止从当前设置重建 URL 或 payload。

### 4.2 设备注册

1. Android 初始化时创建 `devices/`、`android/device-state/`、`android/catalog/`、`android/chapters/`、`android/progress/`、`events/`、`acks/` 和 `snapshots/` 等所需目录；验证注册后再创建该设备的 `events/<device_id>/` 与 `acks/<device_id>/`。目录创建由 Android 负责，Kindle 不发送 `MKCOL`。
2. Kindle 生成密码学安全随机小写 UUID v4 `device_id` 和 `counter_epoch`，注册对象声明 `supported_create_modes`（`uuid_verified`、`conditional_create`）及 `locator_capability`（`chapter_only` 或 `character_exact`）。注册对象只编码一次并原子持久化，所有 PUT/GET 校验复用同一原始字节。读取已存在注册时，只有原始字节和摘要一致才可恢复为 `registration_recovered`；不同则为 `device_id_conflict`。
3. Kindle 本地持久状态有且只有 `unregistered`、`uploaded`、`ready`、`rejected`：尚无注册 intent/身份为 `unregistered`；注册对象成功上传且回读一致后为 `uploaded`；验证 Android 远端状态后转为 `ready` 或 `rejected`。`waiting_android` 只是 UI 根据“本地为 uploaded 且尚无有效远端状态”派生的显示值，不得持久化、写入远端或作为协议 status。
4. Android 远端 device-state 的 `status` 有且只有 `ready`、`rejected`。Android 验证注册 Schema、64 KiB 原始字节上限、设备 ID、摘要与模式交集，创建设备子目录，再使用 `uuid_verified` 发布第一份不可变状态 `android/device-state/<device_id>/<state_revision>-<snapshot_id>.json`。状态至少包含 `device_id`、`state_revision`、`snapshot_id`、`status`、`registration_result`、`create_mode`、`locator_capability`、`registration_payload_sha256` 和 `generated_at`；`registration_result` 只允许 `registration_verified`、`registration_recovered`、`device_id_conflict`、`registration_parent_missing`、`unsupported_mode`、`invalid_registration`。
5. 设备注册文件名不是严格小写 UUID v4 时，Android 只报告本地 `invalid_device_id`，不得据不可信 payload 创建远端状态。文件名合法但 payload 的 `device_id` 不匹配时，以文件名 ID 发布 `rejected/device_id_conflict`，不从 payload 推测路径。文件名合法但 JSON、Schema、字段或 `locator_capability` 无效时，发布 `rejected/invalid_registration`，并将 `create_mode`、`locator_capability` 均置为 `null`；`registration_payload_sha256` 始终是收到的原始字节摘要。缺少 `uuid_verified` 支持时发布 `rejected/unsupported_mode`。不得修复、补默认值、覆盖或删除 Kindle 注册文件。
6. `ready` 状态必须选择注册对象声明支持且服务端能力已确认的 `create_mode`，并给出定位能力；冲突、不支持模式或注册父目录缺失时发布 `rejected`。设备状态路径与文件字段必须一致。Kindle 只验证最高有效 `state_revision` 后进入 `ready`；revision 缺号允许。同 revision 的多个 snapshot ID 或不同原始字节/摘要为 `revision_conflict`，必须停止并等待处理，不能退回旧 revision。
7. Kindle 只有按 `uuid_verified` 成功回读第一份 device-state，且状态 `device_id` 与本机一致、注册摘要一致、`status: ready`、`create_mode` 是本机支持模式、`locator_capability` 合法时，才可把本地状态转为 `ready` 并上传事件。远端 `rejected` 使本地转为 `rejected`；无有效远端状态时本地保持 `uploaded`。不得把 `waiting_android`、`registration_verified` 等值混入任何本地或远端顶层 status。

### 4.3 预发布开发版注册迁移

Kindle 开发版曾创建旧格式注册文件。协议仅允许预发布开发版执行一次性迁移，而且必须同时满足：旧注册缺少本版协商字段；对旧 `device_id` 的 `events/<old_device_id>/` 与 `acks/<old_device_id>/` 均完成明确的远端列表检查，两个目录都必须返回确定的 `404` 或成功返回空目录；本地 outbox/intent 中不存在任何未完成、结果不明或待确认对象。只有全部条件成立，才可生成新的密码学安全随机 UUID v4 `device_id` 与 `counter_epoch`，创建新注册路径并重新协商。网络错误、认证失败、超时、PROPFIND/列表不支持、响应无法解析或任何“结果不明”都必须禁止迁移，不能按空目录处理。旧注册文件始终保留，不覆盖、不删除。此规则不适用于正式用户；只要旧 events/acks 任一非空或本地存在未完成 intent，就不得自动换身份或 epoch。

### 4.4 首次书籍绑定

- Kindle 首次遇到未关联的书籍时，向 `events/<device_id>/<event_id>.json` 写入 `binding_request` 事件；该请求不需要 `work_id`。`content_id` 是 Kindle 本地内容定位提示，不是远端作品主身份。
- 请求只保留可选的 `toc_candidates`，最多 20 项，标题最多 256 个字符；不再定义重复的 `title_samples`。不得上传章节正文、正文片段或无界完整 TOC。事件文件仍受事件字节上限约束。
- `binding-request.schema.json` 用 `oneOf` 区分两种请求：初始请求严禁出现 `selected_work_id` 与 `selection_from_event_id`；收到 `ambiguous` 后的选择请求必须同时携带这两个字段。选择请求必须使用新的 `event_id` 和新的已持久化 `sequence`，`selection_from_event_id` 指向原 ambiguous 事件。Android 必须从原 `SyncImportReceipt`/终态 ack 恢复候选集合，并确认 `selected_work_id` 确实属于该集合；同时必须确认新请求的 `device_id`、`counter_epoch`、`content_id` 与 `selection_from_event_id` 指向的原 `binding_request` 完全一致。候选归属或任一跨事件字段不一致时，必须返回 `rejected` 且 `reason_code: "invalid_event"`，不得按用户输入直接绑定。
- Android 只能自行分配 `work_id`，或从 Android catalog 唯一恢复已有 `work_id`。结果通过 ack 返回：`bound` 携带最终 `work_id`；`ambiguous` 携带有限候选；`not_found` 要求下载 catalog 或人工绑定；非法、超限或非法选择请求返回 `rejected`。
- Kindle 可在本地保存 `content_id → work_id`，但 `content_id` 不是远端主身份。不确定时不得静默合并。含凭据的 `bookUrl` 不得上传原文或可离线猜测的简单摘要。同步库丢失后优先由 Android catalog 恢复；仍不唯一时要求用户确认，不修改旧 Book 表或旧备份。

## 5. 事件幂等、事务与恢复

- `event_id` UUID 是唯一幂等键。`device_id`、`counter_epoch`、`session_id`、`sequence` 只描述来源、排序和诊断，不得用它们替代去重键。事件通过 `event_type` 区分 `binding_request`、`progress` 和 `reading_time`。
- 每个 ack 的 `payload_sha256` 表示对应事件文件原始字节的 SHA-256，不是 ack 自身摘要；在解析事件 JSON 前计算并由 `SyncImportReceipt` 保存。对象相同必须同时满足原始字节逐字节相同和 SHA-256 相同。禁止解析后重新序列化再计算。相同 `event_id` 携带不同 `payload_sha256` 是 ID 冲突，必须拒绝且不应用状态。
- 同一 `event_id` 与相同摘要再次到达时，Android 必须逐字段重放 `SyncImportReceipt` 保存的原终态 ack，不创建 `duplicate` 或任何新状态；`status`、`acknowledged_at`、恢复动作及所有可选字段都复用原值。事件不会重复增加阅读时间、应用进度或重新绑定。
- 若同一 `event_id` 再次到达但 `payload_sha256` 不同，必须返回 `event_id_conflict`，其中 `payload_sha256` 是本次事件摘要、`receipt_payload_sha256` 是 receipt 原摘要，两者必须不同，恢复动作是 `stop_sync`。比较 ack 是否相同，必须计算 ack 完整原始字节自身的 SHA-256 并逐字节比较；不能用 ack 的 `payload_sha256` 代替。Kindle 若发现已下载 ack 的 `payload_sha256` 与本地 outbox 事件摘要不同，必须立即停止同步。
- sequence 缺号不阻塞任何其他事件。实现可按接收顺序处理，或用时间/sequence 辅助诊断；不能等待缺失事件才能推进水位。
- 阅读时间的 `duration_delta_ms` 是事件载荷中的正整数毫秒增量；零增量不生成事件。创建事件前必须原子递增并持久化 `sequence`。sequence 可以缺号，不能等待补号；设备重启不更换 `counter_epoch`，只有插件持久状态丢失或用户明确重置时才更换。`session_id` 只用于诊断，不是账本正确性的前提。应用必须在同一数据库事务中先插入唯一账本记录并仅在首次成功插入时更新聚合值。禁止没有幂等保护地直接 `+= delta_ms`。
- Android 需在一个数据库事务中完成事件验证、`SyncImportReceipt(event_id, payload_sha256, terminal_ack)` 写入、`ReadingEventLedger` 唯一插入及进度/时长聚合更新；ledger 与聚合值必须原子提交。事务成功提交后才能上传 ack。网络失败不回滚本地事务。
- ack 上传失败时，只能从 `SyncImportReceipt` 保存的完整终态 ack 逐字段重建相同对象，并以同一 ack 路径重试；`acknowledged_at` 也必须保持首次终态值。已存在 ack 文件时禁止覆盖，Android与Kindle都必须 GET 并核对摘要/内容。`applied` 仅表示原事务首次成功应用，receipt 命中不会产生新的状态。
- v1 不删除事件文件。即使已存在 ack，事件仍须保留为恢复和审计来源；快照只是可重建的绝对投影，不代替事件账本。

## 6. 进度冲突与绝对状态

每个 progress event 至少包含：`based_on_toc_revision`、`based_on_progress_revision`、`chapter_title`、`local_toc_index`、`visible_char_offset`、`visible_char_count`、`chapter_fraction`、`chapter_text_fingerprint`、`reported_locator_confidence` 和 `normalization_version`；`previous_chapter_title`、`next_chapter_title` 为可选上下文，分别表示扁平 TOC 中向前/向后遇到的第一个“XPointer 有效、与当前 XPointer 不同且顺序有效”的章节标题。第一章或最后一章相应字段可以省略。事件的 `sequence` 表示设备事件顺序；不再定义 `observed_sequence`。

- Android 每次成功应用一个新进度事件后递增该作品 `progress_revision`。重复事件不会递增。
- 事件的 `based_on_progress_revision` 不是当前 revision 时，返回 `stale_progress` ack；不得根据设备时间或“最后写入者”静默覆盖。
- `stale_progress` ack 必须携带服务端当前 `current_progress_revision`，并设置 `recovery_action: "download_progress_state"`；来源端下载该作品最新 progress-state 后再决定是否生成新事件，不能重用旧基线直接重试。
- ack 的 `status` 为 `applied`、`stale_progress`、`stale_toc`、`event_id_conflict`、`bound`、`ambiguous`、`not_found` 或 `rejected`，不定义 `duplicate`。每个状态严格禁止无关字段：只有 `event_id_conflict` 可带 `receipt_payload_sha256`；只有 `stale_toc` 可带 `current_toc_revision`；只有 `stale_progress` 可带 `current_progress_revision`；只有 `ambiguous` 可带 `candidates`。`event_id_conflict` 的两个摘要必须不同。Schema 用 `oneOf` 约束各状态必需字段、恢复动作和禁止字段，Schema 无法比较两个摘要时由语义校验补足。
- `based_on_toc_revision` 过期时，Android 必须返回 `stale_toc`，携带当前 `current_toc_revision` 并要求重新下载章节目录（`recovery_action: "download_chapters"`）；不得把过期索引当作当前目录索引继续应用。
- Android 必须根据 TOC revision、`chapter_title` 与前后章节标题上下文、正文指纹重新判断 Kindle 报告的位置，不能直接信任 `reported_locator_confidence`。无法安全映射时返回 `stale_toc` 或 `rejected`，并说明原因。
- 章节规范化指纹一致时，允许采用 `visible_char_offset`；指纹不一致但章节目录匹配时，只能降级到章节级位置，字符偏移不可信。
- 事件字段 `reported_locator_confidence` 固定为 `high`、`estimated` 或 `chapter`，只表示 Kindle 的报告值。`high` 只有在 Android 根据当前目录、标题上下文和章节指纹独立确认后才可能用于字符定位；`estimated` 只供诊断/估值，绝不能当作精确字符位置。指纹不一致但章节匹配时，Android 必须降级到章节级，不得继续使用 `visible_char_offset`。`chapter` 表示只有章节级位置，其字符偏移、字符数和指纹必须为 `null`。Android 最终投影中的 `locator_confidence` 是 Android 判定结果，不是对事件报告值的原样复制。Schema 使用 `oneOf` 拒绝不合法组合。
- 对非空字符位置必须满足 `visible_char_offset <= visible_char_count`；Draft 2020-12 标准关键字不能引用同一对象的另一个数值作为动态上限，因此越界要在事件语义校验阶段拒绝，不能静默截断。
- `chapter_fraction` 永远是估算值，Schema 要求 `chapter_fraction_is_estimated: true`。它不能伪装为精确字符位置。
- `visible_char_offset` 和 `visible_char_count` 是规范化文本中的 Unicode code point 数，0-based。不能使用 Kotlin UTF-16 `String.length` 或 UTF-8 字节数。
- Kindle 应先下载 Android 最新 progress-state，再以该状态的两个 revision 为基线创建新事件；不能因设备时钟较新而绕过基线检查。

## 7.1 KOReader CREngine 文本边界

EPUB/KOReader 候选位置按 CREngine 的 XPointer 区间计算：

- 起点是当前匹配 TOC 条目的有效 XPointer，包含起点。寻找下一章边界时必须在扁平 TOC 中向后跳过无效 XPointer、与 `chapter_start` 相同的 XPointer，以及用 `compareXPointers()` 判断为倒序的 XPointer；第一个严格位于起点之后的有效 XPointer 才是 `chapter_end`，且不包含终点。
- 必须先校验 `chapter_start <= current_xpointer < chapter_end`。任一比较失败或当前位置不在该半开区间内，都不能生成字符级位置。
- 全章 `full` 来自 `document:getTextFromXPointers(chapter_start, chapter_end)`；前缀 `prefix` 来自 `document:getTextFromXPointers(chapter_start, current_xpointer)`；后缀 `suffix` 来自 `document:getTextFromXPointers(current_xpointer, chapter_end)`。
- 三段文本分别执行相同的 normalization-v1。只有 `normalize(prefix) + normalize(suffix) == normalize(full)` 时才允许生成字符偏移、字符数和指纹；否则必须降级为 `chapter`。字符偏移为 `normalize(prefix)` 的 code point 数，字符总数为 `normalize(full)` 的 code point 数。
- 协议定义的“可见正文”就是 CREngine 从这些 XPointer 区间返回的文本，不推断屏幕实际绘制像素或隐藏排版内容。
- 没有可靠的下一章边界、XPointer 无效或文本提取失败时必须降级为 `chapter`，所有字符定位字段置 `null`。
- Android 与 CREngine 提取文本不一致时，以章节指纹不一致触发章节级降级；不能继续使用 Kindle 字符偏移。

## 8. normalization-v1

所有章节指纹以及可见字符偏移使用同一个冻结的 `normalization-v1`：

1. 输入必须是有效 Unicode 标量序列；孤立 surrogate 拒绝处理。
2. 将 CRLF 和单独 CR 统一为 LF (`U+000A`)。
3. 应用 Unicode NFC；禁止 NFKC。规范化数据版本约定为 Unicode 15.1。
4. 删除 `U+200B` ZERO WIDTH SPACE、`U+FEFF` BOM/零宽不换行空格、`U+2060` WORD JOINER。保留 `U+200C` ZWNJ、`U+200D` ZWJ、variation selectors 及其他未明确列入删除集合的码点，以免改变 emoji/文字组合。
5. 以下水平空白映射为 ASCII 空格：TAB `U+0009`、VT `U+000B`、FF `U+000C`、SPACE `U+0020`、NBSP `U+00A0`、OGHAM SPACE MARK `U+1680`、`U+2000`–`U+200A`、NARROW NBSP `U+202F`、MEDIUM MATHEMATICAL SPACE `U+205F`、IDEOGRAPHIC SPACE `U+3000`。其他码点不因平台的通用 whitespace API 而改变。
6. 连续 ASCII 空格折叠为一个；每行首尾的 ASCII 空格删除。连续 LF 折叠为最多两个 LF（保留一个空行）；整个文本开头和结尾的 LF 删除。
7. 标点保持原码点，不删除、不替换、不转换全角/半角、不重排。除 NFC 外不做兼容折叠。
8. 指纹为最终规范化 UTF-8 字节的 SHA-256，小写 64 位十六进制。偏移按最终规范化字符串的 Unicode code point、0-based 计数。

实现不得用 JVM/平台默认的字符分类或 locale 相关规则代替上面显式集合。`normalization-v1-vectors.valid.json` 是协议样例，双方实现须逐项得到相同规范化文本、code point 数和 SHA-256。

Android 与 Kindle 实现启动时必须读取规范化运行库明确报告的 Unicode 数据版本并运行全部固定向量。只有运行库明确报告 `15.1.0` 且全部向量通过，才可报告 `high`；KOReader/utf8proc 可调用 `utf8proc_unicode_version()` 获取该版本。无法读取版本、版本不是 `15.1.0` 或任一向量失败时，仍可进行章节级同步，但 `visible_char_offset`、`visible_char_count`、`chapter_text_fingerprint` 必须全部为 `null`，`reported_locator_confidence` 必须为 `chapter`。U+1CC00 向量只验证非 BMP 字符的 UTF-8 保留与 code point 计数，不能用作 Unicode 版本检测器。

## 9. 独立数据库概念设计（不创建实体/迁移）

事实来源为独立 `legadoM_ink_sync.db`：

| 表 | 职责 |
|---|---|
| `WorkIdentity` | 稳定 `work_id`、创建时间和必要的同步身份元数据；不替换原 Book。 |
| `WorkAlias` | 将本地 `bookUrl`/换源地址关联到 `work_id`；原始 URL 只留在 Android 本地，冲突时显式处理。 |
| `SyncImportReceipt` | 以 `event_id` 唯一记录导入状态、终态 ack 内容和恢复所需信息。 |
| `SyncState` | 保存设备 ID、counter epoch、schema/protocol 状态、ETag、同步游标及重试状态。 |
| `ReadingEventLedger` | 以 `event_id` 唯一记录已应用的阅读时长增量，保证事务幂等。 |
| `ProgressState` | 保存每个 `work_id` 的 Android 最终绝对进度、目录基线和递增 revision。 |

这些表共同构成同步身份、事件账本、回执与同步状态的事实来源。原生 `readRecord` 等既有表最多是以后可重新生成的派生投影，不能代替事件账本或回执。数据库的列、索引、Room 实体和迁移都不属于本协议文档阶段。

## 10. JSON 文件与 Schema

Schema 使用 JSON Schema Draft 2020-12。核心对象和嵌套对象默认 `additionalProperties: false`，以防旧客户端忽略未知语义。时间是 UTC RFC 3339 且以 `Z` 结束；时长为整数毫秒；revision/sequence 为非负整数。

所有线上 JSON 整数（revision、sequence、索引、偏移、计数及毫秒时长）必须在 `0..9007199254740991`，即 IEEE-754 `Number.MAX_SAFE_INTEGER` 范围内；超限必须拒绝，避免 Lua/JSON 数字舍入。Kotlin 可用 `Long` 存储，但线上仍遵守此上限。所有 SHA-256 使用小写 64 位十六进制。`payload_sha256` 摘要对象是 WebDAV 实际收到的完整事件文件原始字节，必须在 JSON 解析前计算；禁止解析后重新序列化再计算。所有实现必须在解析前检查原始文件字节数，超限拒绝，不能截断后继续处理。

所有文件上限都按解析前收到的 UTF-8 原始字节计算，必须先检查大小再解析；超限直接拒绝，禁止截断。设备注册和设备状态的硬上限均为 64 KiB。事件与 ack 的硬上限均为 256 KiB，正常事件建议不超过 16 KiB，正常 ack 建议不超过 64 KiB。catalog 的硬上限正式冻结为 1 MiB，单作品 chapters 的硬上限正式冻结为 2 MiB；目标 Kindle 已分别完成 1.00 MiB/2371 项和 2.00 MiB/6188 项真机解析。峰值内存尚未量化仅作为实现警告，不再阻塞 exporter/importer；Kindle 必须在后台解析，异步任务向 UI/调用方只返回摘要、状态或错误，不得返回完整解析树。Schema 同时限制 catalog 最多 2500 个作品、chapters 最多 10000 项、绑定 TOC 候选最多 20 项、ack 候选最多 20 项，核心标题/作者/content_id 均有 `maxLength`。Draft 2020-12 Schema 无法检查原始字节数、catalog 的 `work_id` 唯一性、chapters 的 `chapter_key`/`local_toc_index` 唯一性、绑定候选索引唯一性、`visible_char_offset <= visible_char_count` 或两个冲突摘要必须不同，这些都属于实现必须执行的语义校验。

- `schemas/catalog.schema.json` — Android 轻量作品目录。
- `schemas/chapters.schema.json` — 每个作品的章节目录。
- `schemas/progress-state.schema.json` — Android 最终绝对进度。
- `schemas/progress-event.schema.json` — 不可变进度事件。
- `schemas/reading-time-event.schema.json` — 不可变阅读时间事件。
- `schemas/acknowledgement.schema.json` — 事件终态确认。
- `schemas/snapshot.schema.json` — 可重建的绝对投影快照。
- `schemas/normalization-vectors.schema.json` — normalization-v1 固定向量。
- `schemas/device-registration.schema.json` — Kindle 不可变设备注册请求。
- `schemas/device-state.schema.json` — Android 注册验证状态。
- `schemas/binding-request.schema.json` — 不带 `work_id` 的首次绑定事件。
- `schemas/create-mode-vectors.schema.json` — create mode 网络状态机测试向量。
- `schemas/snapshot-selection-vectors.schema.json` — 不可变 revision/snapshot 选择测试向量。

固定样例位于 `fixtures/`，文件名以 `.valid.json` 结尾。它们是规范样例，不含真实用户/服务器数据；ack 样例中的摘要必须与各自对应事件 fixture 的 Git blob 原始字节一致。

进度事件与两种绑定请求 fixture 是无 BOM 的紧凑 JSON；摘要以仓库中实际原始字节为准，不假设解析后的字段顺序或空白。每个 ack fixture 的 `payload_sha256` 与其对应事件 fixture 原始字节完全一致；设备状态 fixture 的注册摘要与注册 fixture 原始字节一致；snapshot-selection 向量中的每个 payload 摘要与 `payload_utf8` 精确字节一致。真实客户端只能对最终原子保存并实际上传的同一字节缓冲区计算摘要，重试不得重新编码对象。`create_mode` 只存在于协商状态和本地 intent，不得出现在 event 或 ack payload。

## 11. 尚待 Android / Kindle 双方确认

1. `work_id` 的首次分配与已有书籍去重规则，以及跨设备如何识别两个不同 `bookUrl` 是同一作品。
2. `bookUrl` canonicalization/脱敏后 alias hash 方案；当前明确不发布含凭据 URL 的任何派生值。
3. `toc_revision` 的内容定义、章节 key 生成方式、目录替换/增删时的 revision 规则。
4. stale_progress 后的用户重新基线 UI；自动合并绝不使用设备时间。
5. 其他 WebDAV 服务的 `conditional_create` 能力仍需按同一向量逐服务验证；当前目标服务已确认只能使用 `uuid_verified`。
6. 快照生成频率和保留策略；Kindle v1 不要求读取 snapshot，snapshot 不能作为增量事件导入，且 v1 禁止删除原始事件。
7. Android 是否能使用明确报告 Unicode `15.1.0` 的 NFC 实现；目标 Kindle 已确认 Unicode `17.0.0`、10/10 向量通过并按协议固定为 `chapter_only`。

在双方确认这些语义并评审 Schema 前，不应把草案解释为已上线协议或实现承诺。
