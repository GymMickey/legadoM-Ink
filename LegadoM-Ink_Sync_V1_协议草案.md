# LegadoM-Ink Sync v1 协议草案

> 状态：整体仍为评审草案；A3 章节目录规则与 chapters Schema 已冻结。本文只定义协议、JSON Schema 和固定样例，不包含同步业务代码、Room 实体或数据库迁移。

## 1. 目标与非目标

Sync v1 在现有 WebDAV 能力上增加轻量目录、不可变事件、确认回执和可重建快照。Android 保留 Legado 数据语义并产生最终状态；Kindle/KOReader 设备保留阅读器本地事实并提交事件。WebDAV 只负责文件传递，不承担事务或唯一数据备份职责。

本阶段不更改 Book 表、AppDatabase schema、`bookshelf.json`、旧备份 ZIP 格式、章节正文或 Kindle 阅读器数据格式；不实现同步数据库、导入器、导出器或同步 UI。

协议版本固定为 `protocol_version: "sync-v1"`，当前 JSON 结构版本为 `schema_version: "1.0.0"`。不兼容字段变化必须提升 `schema_version`；改变行为或语义须另行评审协议版本。

本次进度对象字段冻结发生在 Android A5 与 Kindle K4-B 首次发布 progress event/state 之前；此前只有草案样例，没有线上旧格式进度对象。因此 `1.0.0` 是首个发布进度对象的结构，不构成已发布对象的 schema 兼容升级。catalog 新增字段保持可选以兼容已发布 A3 catalog；所有首发 progress 对象均使用本次冻结结构。后续一旦某类对象已发布，不兼容字段变化仍必须提升 `schema_version`。

## 2. 兼容旧用户与隐私边界

- 现有 Book 表、AppDatabase schema、`bookshelf.json` 和原备份格式保持不变。Sync v1 数据只写入独立数据库 `legadom_ink_sync.db`。
- 老用户升级后按需生成稳定 `work_id`，无需清空书架或重新导入。首次生成与后续重建须由 Android 的身份映射事务保证稳定。
- `WorkAlias` 在 Android 本地把一个或多个 `bookUrl` 映射到同一 `work_id`。换源只增加/更新别名，不新建作品身份。同步目录只发布 `book_url_sha256`，不发布原始 `bookUrl`。
- 若独立同步库丢失或用户恢复旧版备份，Android 只读既有 Book 的 `bookUrl`，计算可发布的别名摘要并与 catalog 匹配，以恢复 `WorkAlias/work_id`；未匹配项才按需分配新 `work_id`。不得要求改 Book 表、`bookshelf.json` 或旧备份。含凭据 URL 的摘要不发布，因此这类别名跨库丢失后的自动恢复规则仍待双方确认。
- 所有路径 ID（`work_id`、`device_id`、`event_id`、`snapshot_id`）使用小写 UUID；Schema 同时校验 UUID 格式和固定字符模式，因此不能含 `/`、`\\`、点段或路径穿越字符。
- 不导出正文、EPUB、TXT、图片缓存、bookChapterCache.json 或其他缓存。A3 chapters projection 只包含章节目录结构字段 chapter_key、title、local_toc_index；正文加载状态、字符数及正文指纹留给未来独立 projection/revision，本轮不定义其路径或 Schema。
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
| `android/chapters/<work_id>/<toc_revision>-<snapshot_id>.json` | Android | Android、Kindle | 不可覆盖 | 每作品 TOC 是不可变投影；revision 和语义内容规则见 4.5。路径中的 work ID、revision 和 snapshot ID 必须与文件内容一致。 |
| `android/progress/<work_id>/<progress_revision>-<snapshot_id>.json` | Android | Android、Kindle | 不可覆盖 | progress revision 可因初始导出、应用事件或安全 TOC 重投影递增；revision 与 snapshot 不可变，路径字段必须与绝对状态一致。 |
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

### 4.5 章节目录与 toc_revision（A3 冻结）

- catalog 中作品的 toc_revision 为 0 表示尚无任何有效章节快照。首次成功读取权威目录并发布时使用 revision 1；明确读取成功的空目录也是有效快照，发布空 chapters 并使用 revision 1。
- A3 chapters projection 仅描述目录结构。每章对象只包含 chapter_key、title 和 local_toc_index。toc_revision 只由按序排列的这些字段及数组排列顺序决定。新增/删除章节、顺序或索引变化、原始 title 变化都会改变目录语义。正文是否加载、字符数和正文指纹不属于本 projection，不得改变 toc_revision；这些元数据以后使用独立 projection/revision，本轮不设计其路径或 Schema。
- 目录来源状态必须区分 AVAILABLE、KNOWN_EMPTY、UNAVAILABLE、FAILED。AVAILABLE 表示已取得完整、权威且当前可用的扁平章节目录；KNOWN_EMPTY 表示成功确认权威目录为空；UNAVAILABLE 表示当前无法取得目录；FAILED 表示目录读取、解析或校验过程失败。只有 AVAILABLE 和 KNOWN_EMPTY 可以发布。导出不得主动联网刷新书源、加载正文或触发目录抓取；UNAVAILABLE/FAILED 不得按空目录处理，也不得覆盖快照或增加 revision。
- 目录结构完全相同时必须复用原 revision、snapshot_id 和原始字节；generated_at、重复检查、App 重启、换源但完整目录结构相同及 WebDAV 重试都不增加 revision。只有目录结构语义变化才递增一次。revision 缺号允许；选择最高有效章节 revision。同一 revision 出现多个 snapshot_id 或候选对象原始字节/摘要冲突，必须报告 revision_conflict，不得任选或回退。catalog 中该作品 toc_revision 必须等于最高有效章节快照 revision；尚无快照时为 0。
- local_toc_index 是 Android/Legado 当前扁平章节目录中的真实索引。chapters 数组必须按 local_toc_index 严格递增，同一快照内唯一；不要求连续，禁止为填补缺号重新编号。Kindle 只能在对应 toc_revision 内解释索引，不得跨 revision 直接复用。
- chapter_key 是当前 toc_revision 内确定且不含隐私的章节键。输入字符串严格为：chapter-key-v1 + U+001F + work_id + U+001F + local_toc_index 的无前导零十进制文本 + U+001F + 序列化前 Kotlin 原始 title 字符串。title 使用 JSON 实际发布的同一 Unicode 标量序列，按 UTF-8 编码；不包含 JSON 引号或转义字符，不做 trim、替换、规范化、大小写/空白转换或占位。title 必须非空、没有孤立 surrogate，且最多 512 个 Unicode code point；任一标题不符合要求时拒绝整本目录。对完整输入字符串的 UTF-8 字节计算 SHA-256，输出 ck1- 加 64 位小写十六进制摘要。不得使用 chapterUrl、正文 URL、文件/缓存路径、凭据或其摘要。相同 work_id、index 和原始 title 必须生成相同 key；重复标题由不同 index 区分。key 只在当前 toc_revision 内使用，不保证跨 revision 稳定。Android producer 按算法生成并校验；Kindle consumer 只检查格式和同一快照内唯一性，将 key 作为透明标识保存，不重算算法。
- 单作品 chapters 文件 toc_revision 必须大于 0，路径固定为 android/chapters/<work_id>/<toc_revision>-<snapshot_id>.json；路径中的 work_id、revision、snapshot_id 必须与内容一致。完整 JSON 序列化后，以最终 UTF-8 原始字节数检查 2 MiB 硬上限；不得使用 Kotlin String.length，不得截断，超限拒绝整个对象。chapters Schema 的 maxItems 固定为 6000；超过上限拒绝整本对象并返回 chapters_limit_exceeded。10000 章延后为 v1.1 可选扩展，不属于 v1；本轮不等待 10000 章 K0 测试。
- 批量发布按作品独立执行：先创建/复用该作品的不可变 chapters intent，按协商模式上传并回读逐字节及摘要验证；只有验证成功后才持久化该 work 的已确认 revision、snapshot_id、原始字节摘要，并标记 catalog dirty。失败作品保留其旧 toc_revision。全部作品处理完后，最多发布一个汇总 catalog。catalog 只能引用当前同一 serviceIdentity + rootIdentity 下已远端确认存在的 chapters；失败作品只有在旧快照也已在当前根目录确认存在时才能保留在该 catalog 中，否则从该次 catalog 中省略。可返回 partial_success；失败不得伪报全量成功。
- A3 持久状态按 serviceIdentity + rootIdentity + work_id 隔离，保存已确认的 toc_revision、snapshot_id、原始字节摘要、catalog dirty，以及未完成的 chapters/catalog intent；catalog intent 必须保存原始字节。进程重启后优先续传原 intent，路径、revision、snapshot_id 和字节完全复用，不重新序列化、不重复增加 revision。切换 WebDAV 服务或根目录后，不得直接复用旧根目录发布状态；必须先在新根目录发布或验证 chapters，再允许 catalog 引用。任何时刻最多一个 A3 发布任务；重复启动必须加入/返回现有任务或被合并，不能并发分配 revision 或 snapshot。

## 5. 事件幂等、事务与恢复

- `event_id` UUID 是唯一幂等键。`device_id`、`counter_epoch`、`session_id`、`sequence` 只描述来源、排序和诊断，不得用它们替代去重键。事件通过 `event_type` 区分 `binding_request`、`progress` 和 `reading_time`。
- 每个 ack 的 `payload_sha256` 表示对应事件文件原始字节的 SHA-256，不是 ack 自身摘要；在解析事件 JSON 前计算并由 `SyncImportReceipt` 保存。对象相同必须同时满足原始字节逐字节相同和 SHA-256 相同。禁止解析后重新序列化再计算。相同 `event_id` 携带不同 `payload_sha256` 是 ID 冲突，必须拒绝且不应用状态。
- 同一 `event_id` 与相同摘要再次到达时，Android 必须逐字段重放 `SyncImportReceipt` 保存的原终态 ack，不创建 `duplicate` 或任何新状态；`status`、`acknowledged_at`、恢复动作及所有可选字段都复用原值。事件不会重复增加阅读时间、应用进度或重新绑定。
- 若同一 `event_id` 再次到达但 `payload_sha256` 不同，必须返回 `event_id_conflict`，其中 `payload_sha256` 是本次事件摘要、`receipt_payload_sha256` 是 receipt 原摘要，两者必须不同，恢复动作是 `stop_sync`。比较 ack 是否相同，必须计算 ack 完整原始字节自身的 SHA-256 并逐字节比较；不能用 ack 的 `payload_sha256` 代替。Kindle 若发现已下载 ack 的 `payload_sha256` 与本地 outbox 事件摘要不同，必须立即停止同步。
- sequence 缺号不阻塞任何其他事件。实现可按接收顺序处理，或用时间/sequence 辅助诊断；不能等待缺失事件才能推进水位。
- 阅读时间的 `duration_delta_ms` 是事件载荷中的正整数毫秒增量；零增量不生成事件。创建事件前必须原子递增并持久化 `sequence`。sequence 可以缺号，不能等待补号；设备重启不更换 `counter_epoch`，只有插件持久状态丢失或用户明确重置时才更换。`session_id` 是一次 KOReader 文档打开/阅读会话的小写 UUID v4；同一打开会话复用，关闭文档后或 KOReader 完整重启后允许更换。它仅用于诊断，不是幂等键或账本唯一键；丢失 session_id 不得更换 counter_epoch。应用必须在同一数据库事务中先插入唯一账本记录并仅在首次成功插入时更新聚合值。禁止没有幂等保护地直接 `+= delta_ms`。
- Android 需在一个数据库事务中完成事件验证、`SyncImportReceipt(event_id, payload_sha256, terminal_ack)` 写入、`ReadingEventLedger` 唯一插入及进度/时长聚合更新；ledger 与聚合值必须原子提交。事务成功提交后才能上传 ack。网络失败不回滚本地事务。
- ack 上传失败时，只能从 `SyncImportReceipt` 保存的完整终态 ack 原始字节重试；`acknowledged_at` 保持首次终态值。已存在 ack 文件时禁止覆盖，Android与Kindle都必须 GET 并核对摘要/内容。对 progress 的 `applied` ack，必须等其 progress-state 与引用 revision 的 catalog 均上传并回读确认后才可发布；receipt 命中不会产生新状态或重复投影。
- v1 不删除事件文件。即使已存在 ack，事件仍须保留为恢复和审计来源；快照只是可重建的绝对投影，不代替事件账本。

## 5.1 progress 导入与投影的原子恢复

本节补充第 5 节的一般幂等规则。事件解析、Schema/语义校验、候选绝对位置与候选状态计算必须在无数据库写、无 WebDAV、无文件系统访问的事务外完成；这些仅是候选。进入事务后必须重读并复核 event_id、payload_sha256、device_id、counter_epoch、work_id、两项 based_on revision、当前 TOC/progress revision、baseline 和 SyncImportReceipt。任一基线变化时丢弃事务外候选及由其生成的字节，基于新状态重新开始处理或留下待处理状态。每个 work 同一时刻最多有一个未确认的 progress 投影；已有 `projection_pending` 时，后续该 work 的 progress event 保持待处理，直到 state 与 catalog 确认后再复核基线，不得基于尚未发布的本地 revision 连续应用。

所有同步真相——事件账本、SyncImportReceipt、绝对 progress 状态、progress-state/catalog intents、投影阶段和 ack 原始字节——必须在独立 `legadom_ink_sync.db` 中使用同一 Room 数据库事务保证一致性，不依赖跨数据库事务。该名称是协议边界，不要求本轮创建数据库实体。

**原子接收事务。** 事务内最终裁定 applied、stale_progress、stale_toc、rejected 或 event_id_conflict。applied 时同一事务必须插入唯一事件账本、更新绝对进度、递增 progress_revision、确定性编码最终状态字节并保存不可变 progress-state intent（service/root/work、TOC/progress revision、snapshot/path、原始字节、SHA-256、create_mode、PUT 次数、发布阶段），确定性编码终态 ack 原始字节并保存 SyncImportReceipt；receipt 阶段置 `projection_pending`。编码纯内存、确定性，不得访问网络或文件；任一步失败则整个事务回滚。不需要新投影且依赖已确认的其他终态可进入 `ack_publishable`；stale ack 所引用的 revision/chapters 尚未发布时不能生成可发布终态。

**发布状态机。**

1. `projection_pending` 只从事务保存的 progress-state intent 发布；PUT 复用已保存路径/snapshot/字节/摘要，回读必须原始字节和摘要都一致。结果不明先 GET；仅 GET 404 时才可按原 intent 有限重试。不重序列化、不换 snapshot、不增加 revision。
2. progress-state 确认后，在同步数据库事务内分配 catalog revision，并原子生成保存 catalog snapshot/path/完整字节/SHA-256/create_mode/PUT 次数。catalog 必须引用已经确认的 progress revision；事务内分配或保存失败时整体回滚，不能留下“revision 已增加但 intent 不存在”。事务提交后的上传失败保留原 catalog intent 供恢复，不重分 revision。随后上传并回读确认。
3. progress-state 与 catalog 都确认后，在数据库事务将 receipt 置为 `projection_confirmed` 与 `ack_publishable`。只有此后可以上传 receipt 中保存的 ack 原始字节、路径、摘要、acknowledged_at 和终态字段。不得重生成 ack 或改变时间。

**崩溃和重复恢复。** 事务提交前崩溃不能留下 receipt/账本/revision/intent；提交后从原 intent 继续，不重应用或重增 revision。progress-state 上传后崩溃则回读原 intent 后创建 catalog intent；catalog 上传后崩溃则回读原 intent 后推进 receipt 阶段；ack 结果不明先 GET，字节及摘要一致即成功，冲突即停止，404 才用原 intent 有限重试。duplicate 命中 `projection_pending` receipt 时只继续 state→catalog→ack，不重新插入账本、应用事件、增加 revision、重新序列化或提前发 ack。所有阶段不得使用当前数据库值重建已持久化字节。

## 6. 进度基线、冲突与绝对状态（v1 冻结）

Progress 是按 work 维护的 Android 绝对状态。所有 service/root 范围的判断均使用当前规范化的 `serviceIdentity + rootIdentity + work_id`；更换服务或根目录不得继承旧 baseline、缓存或发布状态。

### 6.1 catalog baseline 状态

catalog 每个 work 可包含 `progress_baseline_status`。线上可写值仅为 `zero_confirmed`、`available`、`unavailable`。Schema 保持该字段可选，以读取 A3 旧 catalog；Android A5 producer 对每个 work 必须写入，独立 producer 语义测试必须检查缺失即失败。

旧 catalog 缺字段时，consumer 仅在本地派生 `legacy_unknown`：不得回写或推断 `zero_confirmed`；仍允许 K2 catalog/chapters 和 K3 binding，禁止 progress event 上传。该派生值绝不是线上枚举。

状态组合必须符合以下规则，并由独立语义校验检查：

| baseline | catalog progress_revision | 远端状态 | 行为 |
|---|---:|---|---|
| `zero_confirmed` | 0 | 当前 service/root/work 已权威确认无本地进度；永远不存在 revision 0 文件 | 可用 0 创建事件 |
| `unavailable` | 0 | 无可发布基线，catalog 不引用 progress-state | 禁止上传；不得列举或猜测 `0-*.json` |
| `available` | >=1 | 被引用 progress-state 已上传，原始字节与 SHA-256 回读验证通过 | 可读取并以该 revision 作并发基线 |
| 字段缺失 | 任意旧值 | 仅旧 catalog 兼容 | 派生 `legacy_unknown`；允许 K2/K3，禁止 K4 |

组合不合法即拒绝 catalog，不修正、不回退。revision 0 永远没有 progress-state 文件。短暂网络、认证、列表或超时故障不得将已确认的 `available` 降为 `unavailable`。Android 同步数据库丢失时，先扫描当前根目录并验证现存有效 progress-state，再恢复 baseline；不能仅凭本地库缺失清零。不能确认、映射失败或初始发布失败时置 `unavailable`，或保持尚未完成的旧状态。

`progress_toc_stale` 是本地派生状态，不是线上字段。baseline 仍为 `available`；它表示存在的 progress-state.toc_revision 与当前最高有效 TOC 不同。可缓存并用于并发控制，但 Kindle 不得将其应用到当前 TOC，也不得跨 toc_revision 复用 local_toc_index。

### 6.2 revision 0 的初始基线确认

Android 在处理任何 `based_on_progress_revision=0` 的事件前，必须先检查当前 service/root/work：

1. **发现可安全映射的 Android 本地进度**：先生成不可变初始 progress-state，`progress_revision=1`、`source_event_id=null`、`updated_by_device_id=Android publisher ID`；持久化 intent，上传并回读原始字节及 SHA-256；随后发布并回读 `progress_baseline_status=available, progress_revision=1` 的 catalog。完成后，原 revision 0 事件只能收到 `stale_progress`（current_progress_revision=1，recovery_action=download_progress_state）。禁止创建指向 revision 0 文件的 ack。
2. **权威确认无本地进度**：在独立同步数据库持久化 zero baseline；发布并回读 `zero_confirmed, progress_revision=0` 的 catalog，之后才允许接受基于 0 的事件。
3. **无法确认、无法映射或初始发布失败**：标记 `unavailable` 或保持未完成状态；事件继续待处理，不应用、不增 revision、不写终态 SyncImportReceipt、不生成 stale ack，也不生成 revision 0 下载动作。基线完成后再处理。

Kindle 仅在 catalog baseline 为 `zero_confirmed` 或 `available` 时可上传；`legacy_unknown` 与 `unavailable` 必须阻止上传。零基线只是 catalog 声明，绝不对应远端 progress-state。

### 6.3 TOC 与 progress revision 解耦

progress_revision 可因以下任一动作递增：初次导出 Android 已有进度、首次成功应用新 progress event、TOC 变化后将旧位置安全重投影到新 TOC。TOC 变化重投影成功时生成新不可变状态：`progress_revision=previous+1`、`source_event_id=null`、publisher ID 为 Android、toc_revision 为当前 TOC revision，并按第 5.1 节事务发布流程发布新状态与 catalog。

若 TOC 变化但不能安全重投影：保留旧 progress-state 和 progress_revision，不修改旧文件，不回退其他 revision；保留 baseline `available`，本地派生 `progress_toc_stale`。TOC 变化本身不等于 `revision_conflict`，也不得仅因 TOC 改变而在相同 progress_revision 下发布新内容。只有安全重投影成功后才可将新 TOC revision 写入新的 progress revision。

### 6.4 事件 revision 基线与内容

每个 progress event 包含独立的 `based_on_toc_revision` 与 `based_on_progress_revision`：

- based_on_toc_revision 取自当前最高有效 catalog 所指定、已验证并缓存的 chapters TOC revision；
- based_on_progress_revision 在 baseline=available 时取 catalog 引用且已确认的 progress-state revision，在 zero_confirmed 时为 0。

创建事件前必须有当前有效绑定、有效 catalog/TOC、zero_confirmed 或 available baseline、当前 TOC 中安全定位的章节，以及未变化的 service/root identity。两个 revision 不要求同来自旧 progress-state。

progress event 至少含以上两 revision、chapter_title、local_toc_index、字符位置字段、`chapter_fraction_ppm`、固定的 `chapter_fraction_is_estimated=true`、fingerprint、reported confidence 与 normalization_version。前后章节标题仍为可选上下文。event sequence 仅表设备事件顺序，不定义 observed_sequence。

### 6.5 progress-state 发现、选择与字节限制

progress-state 的解析前 UTF-8 原始字节上限精确为 **65,536 bytes**。必须先量取实际原始字节长度再解析 JSON：65,536 允许，65,537 以 `progress_state_too_large` 拒绝；不得截断、解析后估算或在超限时回退旧 revision。

消费边界是最高有效 catalog：

- zero_confirmed：确认 revision=0，不列举 `0-*.json`，按零基线处理。
- unavailable/legacy_unknown：不下载或猜测状态，禁止上传。
- available：只列 `android/progress/<work_id>/` 直接子项，候选文件名严格为 `<无前导零正整数revision>-<小写UUIDv4>.json`。先应用上述 64 KiB 原始长度门，再解析并验证 Schema、UTF-8、安全整数、work_id/revision/snapshot_id 与路径一致性、禁止额外字段；progress-state 的 toc_revision 与 progress_revision 都必须 >=1。

catalog 引用的 revision 必须对应唯一有效对象。同 revision 多 snapshot、原始字节不一致或摘要不一致统一报 `revision_conflict`，不任选。冲突检查后，若 catalog 引用状态不存在，优先报 `progress_state_missing`；远端存在高于 catalog 引用值的有效 state 且引用状态存在时，报 `progress_catalog_pending`，刷新 catalog/等待 Android 完成发布，不直接采用。若格式上属于更高 revision 的候选内容非法，也不得静默回退旧 revision。普通陌生文件忽略并计入 ignored_entries；网络、认证、超限、Schema 或冲突失败时保留最后有效离线缓存并明确标记旧缓存，不得伪装刷新成功或用缓存绕过冲突。service/root 改变后缓存不可复用。

### 6.6 ppm 估算值

所有事件、progress-state 与 snapshot progress 投影使用整数比例成员 `chapter_fraction_ppm`：整数或 null，范围 0..1,000,000。0 表章首，1,000,000 表章末；它始终只是估算提示，不代表精确字符位置、不能替代 visible_char_offset/count，也不能提升 locator confidence。null 表 producer 没有可靠整数比例来源；即使 null，`chapter_fraction_is_estimated=true` 仍固定存在。

只有 position_units、total_units 都是非负安全整数、total_units>0、position_units<=total_units，且全部中间整数不超过 9,007,199,254,740,991 时才能计算。纯整数 round-half-up：

```text
numerator = position_units * 1,000,000
rounded = floor((numerator + floor(total_units / 2)) / total_units)
chapter_fraction_ppm = clamp(rounded, 0, 1,000,000)
```

任何乘法或加法中间值越过安全整数范围、输入不是整数、position>total、total=0 或输入无效，一律输出 null。禁止浮点比例、默认浮点 JSON 序列化或字符串修补。consumer 不将 ppm 转为小数后再序列化。

### 6.7 chapter_only

Kindle capability 为 `chapter_only` 时 progress event 固定满足：`visible_char_offset=null`、`visible_char_count=null`、`chapter_text_fingerprint=null`、`reported_locator_confidence="chapter"`。local_toc_index 和 chapter_title 必须来自当前 TOC revision；前后章节标题可选。ppm 可为整数或 null，但 estimated 固定为 true，不能提升 chapter confidence、替代字符位置或覆盖 Android 精确位置。禁止读取或上传章节正文、生成 high/estimated 字符定位。Android 必须独立验证目录与标题。

### 6.8 stale、重新基线与确认

若 based_on_toc_revision 与 based_on_progress_revision 同时过期，优先返回 stale_toc，避免在旧目录位置尚未重映射时要求 Kindle重提；Kindle刷新当前 catalog 与 chapters 后同时取得两个新基线。否则，若 based_on_progress_revision 不等于 Android 当前 progress_revision，事件不得应用。只有当前 progress-state 与其 catalog 已上传并回读确认后，才可产生 `stale_progress` ack，包含 current_progress_revision、`recovery_action=download_progress_state`、`reason_code=stale_progress`。依赖投影未发布时事件保持待处理，不提前确认不可下载的状态。Kindle 下载最新 catalog/state 后由用户决定是否重新基线；新事件必须使用新 event_id、新 sequence、新基线和重新生成的原始字节，绝不按设备时间覆盖或复用旧事件。

若 based_on_toc_revision 过期，只有当前 catalog 与 chapters 已上传并回读确认后才可返回 `stale_toc`（current_toc_revision、download_chapters、stale_toc）。Kindle 获取 TOC 后重算映射，以新 event_id/sequence 创建事件；不得跨 revision 复用 local_toc_index。chapter 标题及上下文、正文规范化指纹必须由 Android独立校验，不能信任 reported confidence。指纹一致才可考虑字符偏移；指纹不一致而章节目录匹配时只能降为章节级。非空字符位置要求 offset<=count，Schema 无法跨字段比较的由语义校验拒绝，不截断。

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

normalization-v1 保留给未来正文指纹和可见字符偏移协议使用。A3 chapters projection 的 chapter_key 直接使用原始 title，不依赖 normalization-v1。后续需要规范化正文时，仍使用以下冻结规则：

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

事实来源为独立 `legadom_ink_sync.db`（协议名称）：

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

所有文件上限均按解析前 UTF-8 原始字节计算，先检查长度再解析，超限拒绝且不截断。设备注册、device-state 和 progress-state 上限均为 65,536 bytes；progress-state 允许恰好 65,536，65,537 返回 `progress_state_too_large`，不能回退 revision。事件与 ack 上限均为 256 KiB（正常建议分别不超过 16 KiB 与 64 KiB），catalog 1 MiB，chapters 2 MiB 且最多 6000 项；超限章节整体拒绝并返回 `chapters_limit_exceeded`。10000 章是 v1.1 可选扩展。Kindle 后台解析，只向 UI 返回摘要/状态/错误。Schema 限制 catalog 最多 2500 works、chapters 最多 6000 项、绑定 TOC 与 ack 候选各最多 20 项及核心文本长度。Draft 2020-12 Schema 不能跨字段比较或检查：服务端 producer 对 baseline 字段的必填、baseline 与 revision 组合、路径与对象内容、状态文件存在性/唯一性/最高候选不可回退、revision 冲突、progress-state 字节数、catalog work_id 唯一性、chapters 的唯一/顺序/Unicode/chapter_key 重算、offset<=count、ppm整数算术溢出、stale 新事件身份、投影阶段和事务恢复；这些由独立语义测试和实现层强制校验。

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
- `schemas/progress-semantics-vectors.schema.json` — progress baseline、state 选择、ppm、stale 与恢复语义向量。

固定样例位于 `fixtures/`，文件名以 `.valid.json` 结尾。它们是规范样例，不含真实用户/服务器数据；ack 样例中的摘要必须与各自对应事件 fixture 的 Git blob 原始字节一致。

进度事件与两种绑定请求 fixture 是无 BOM 的紧凑 JSON；摘要以仓库中实际原始字节为准，不假设解析后的字段顺序或空白。每个 ack fixture 的 `payload_sha256` 与其对应事件 fixture 原始字节完全一致；设备状态 fixture 的注册摘要与注册 fixture 原始字节一致；snapshot-selection 向量中的每个 payload 摘要与 `payload_utf8` 精确字节一致。真实客户端只能对最终原子保存并实际上传的同一字节缓冲区计算摘要，重试不得重新编码对象。`create_mode` 只存在于协商状态和本地 intent，不得出现在 event 或 ack payload。

## 11. 尚待 Android / Kindle 双方确认

1. work_id 的首次分配与已有书籍去重规则，以及跨设备如何识别两个不同 bookUrl 是同一作品。
2. bookUrl canonicalization/脱敏后 alias hash 方案；当前明确不发布含凭据 URL 的任何派生值。
3. stale_progress 后的用户重新基线 UI 文案与交互；协议安全门已冻结，自动合并绝不使用设备时间。
4. 其他 WebDAV 服务的 conditional_create 能力仍需按同一向量逐服务验证；当前目标服务已确认只能使用 uuid_verified。
5. 快照生成频率和保留策略；Kindle v1 不要求读取 snapshot，snapshot 不能作为增量事件导入，且 v1 禁止删除原始事件。
6. Android 与 Kindle 的 normalization-v1 实现能否满足未来正文指纹和字符偏移所需的 Unicode 15.1.0 数据版本；此要求不适用于 A3 chapter_key，chapter_key 使用原始 title 字节。

A3 的 toc_revision、章节语义、local_toc_index 和 chapter_key，以及本次进度 baseline、revision、ppm、state 选择和事务恢复规则构成 Sync-v1 EPUB 基础协议冻结范围。以上待确认项属于产品/部署策略，不阻止 Schema 与协议基线冻结；不得据此扩展 TXT、阅读时间同步或自动同步范围。


## 12. 兼容部署顺序（冻结）

为避免带新 baseline 字段的 Android catalog 影响旧 Kindle K2，部署顺序固定：

1. 先部署 Kindle K4-A parser：接受可选 `progress_baseline_status`；缺失派生 `legacy_unknown`；K2 与 K3 正常工作，K4 上传关闭。
2. 再部署 Android A5：完成每个 work 的 baseline 检查并发布带明确 baseline 字段的新 catalog。
3. 最后才启用 Kindle K4-B progress event 上传。

Android A5 不得在 Kindle 兼容 parser 尚未部署的联调环境中覆盖正常 K2/K3 验收。

## 13. 本轮基础协议范围排除项

本轮冻结仅覆盖 EPUB 基础进度协议。TXT、TXT 虚拟目录、目录插件生成身份、TXT 转 EPUB 身份继承、跨排版字符定位、阅读时间事件、自动同步、远端事件清理、Android 主数据库投影及 KOReader 自动跳转均不在此冻结范围。
