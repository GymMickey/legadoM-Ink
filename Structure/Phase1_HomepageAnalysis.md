# Phase 1: 首页架构分析报告

> 日期：2026-07-03 | 状态：只读分析完成

---

## 一、整体导航架构

### MainActivity (`ui/main/MainActivity.kt`)

**继承链**: `MainActivity` → `VMBaseActivity<ActivityMainBinding, MainViewModel>` → `BaseActivity`

**Fragment 托管方式**: ViewPager + `FragmentStatePagerAdapter` (BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT)

**底部 5 Tab** (定义于 `res/menu/main_bnv.xml`):

| 位置 | ID | Fragment | 可隐藏 |
|------|-----|----------|--------|
| 0 | `menu_homepage` | `HomepageFragment` | ✅ (`AppConfig.showHomepage`) |
| 1 | `menu_bookshelf` | `BookshelfFragment1/2` | ❌ 不可隐藏 |
| 2 | `menu_discovery` | `ExploreFragment` | ✅ (`AppConfig.showDiscovery`) |
| 3 | `menu_rss` | `RssFragment` | ✅ (`AppConfig.showRSS`) |
| 4 | `menu_my_config` | `MyFragment` | ❌ 不可隐藏 |

**关键机制**:
- `realPositions` 数组动态计算各 Tab 在 ViewPager 中的实际位置
- Tab 切换通过 `viewPager.setCurrentItem()` 实现
- `offscreenPageLimit = 3`（预加载相邻 3 页）
- 返回键：先切到书架 tab → 双击退出（朗读未暂停时 `finish()`，否则 `moveTaskToBack(true)`）

### MainViewModel (`ui/main/MainViewModel.kt`)

负责**全局后台任务**，与首页无直接耦合：
- 书架书籍目录更新 (`upAllBookToc`)
- 规则订阅自动更新 (`ruleSubsUp`)
- 书籍缓存 (`CacheBook`)
- WebDAV 备份恢复
- 书架更新徽章计数 (`onUpBooksLiveData`)

---

## 二、当前首页架构（Compose 动态模块系统）

### 核心文件

| 文件 | 职责 |
|------|------|
| `HomepageFragment.kt` | Fragment 容器，创建 ComposeView |
| `HomepageScreen.kt` | 主 Composable，顶栏 + 模块列表 + 管理弹窗 |
| `HomepageViewModel.kt` (1624 行) | 数据加载、状态管理、模块 CRUD、集管理 |
| `HomepageContract.kt` | UI 状态模型（State + Sealed Interface） |
| `HomepageEffect.kt` | 一次性副作用（导航、Snackbar） |
| `HomepageConfig.kt` | SharedPreferences 配置（布局模式、隐藏集） |

### 技术栈

- **Jetpack Compose** (Material3)
- **StateFlow** 响应式架构
- **Clean Architecture** 分层：Gateway/Repository → UseCase → ViewModel → Compose UI
- Room DAO + KSP 编译时代码生成

### 数据流架构

```
                    ┌──────────────┐
                    │  Room DB     │
                    │  (4 个 DAO)  │
                    └──────┬───────┘
                           ↓
              ┌────────────────────────┐
              │ HomepageModulesGateway │  ← Clean Architecture Gateway
              │ HomepageModulesRepo    │
              └────────────┬───────────┘
                           ↓
         ┌─────────────────────────────────┐
         │      HomepageViewModel          │
         │                                 │
         │  localModulesFlow ─┐            │
         │  allModulesCache ──┤            │
         │  customSetsFlow ───┤ combine    │
         │  _moduleContentStates ─┤ chains │
         │  _bookSourcesCache ─┤         │
         │  _bookshelf ────────┘            │
         │                                 │
         │  → uiState: StateFlow<UiState>  │
         │  → effects: SharedFlow<Effect>  │
         │  → manageStateFlow              │
         └──────────────┬──────────────────┘
                        ↓
         ┌─────────────────────────────────┐
         │      HomepageScreen.kt          │
         │  (Compose UI)                   │
         │  - TopAppBar + 管理按钮          │
         │  - 混合列表 / 分源Tab 布局        │
         │  - HomepageModuleManageSheet     │
         └─────────────────────────────────┘
```

### UI 状态模型

```
HomepageUiState
├── modules: List<HomepageModuleUi>     ← 所有已启用模块
├── isManageMode: Boolean               ← 管理模式开关
├── isRefreshing: Boolean               ← 下拉刷新状态
└── manageState: HomepageManageUiState  ← 管理界面完整状态

HomepageModuleUi
├── globalId: String                    ← 格式: setId::sourceUrl::key
├── type: HomepageModuleType            ← 8 种模块类型
├── title: String / sourceUrl / setName
├── state: ModuleLoadState              ← Loading | Loaded | Buttons | Error | RankingTabs
└── layoutConfig / config / exploreUrl
```

### 8 种模块类型 (`HomepageModuleType`)

| 类型 | key | 布局描述 |
|------|-----|---------|
| Banner | `banner` | 横向滚动封面大图 |
| Card | `card` | 卡片式书单 |
| Grid | `grid` | 2 行网格 |
| InfiniteGrid | `infiniteGrid` | 无限滚动网格 |
| Ranking | `ranking` | 带序号排行列表 |
| GridRanking | `gridRanking` | 网格排行 |
| ButtonGroup | `buttonGroup` | 分类快捷按钮组 |
| Waterfall | `waterfall` | 双列瀑布流 |

### 两种布局模式

1. **Mode 0 - 混合列表**（默认）: 所有模块在一个 `LazyColumn` 中展示，无限流模块排在底部
2. **Mode 1 - 分源Tab**: `ScrollableTabRow` + `HorizontalPager` 按书源集分组，支持左右滑动切换

### 模块管理功能（20+ 操作）

- **集管理**: 创建/重命名/删除/排序自定义集，显示/隐藏书源集
- **模块管理**: 加入/删除/编辑/启用/禁用/排序模块
- **源同步**: 基于 MD5 哈希的书源模块增量同步
- **自定义模块**: 支持手动添加 RSS/书源模块
- **按钮组**: 从探索分类创建分类导航按钮

### 数据源依赖

| DAO / 数据源 | 用途 |
|-------------|------|
| `HomepageModuleDao` | 模块 CRUD |
| `HomepageCustomSetDao` | 自定义集管理 |
| `BookSourceDao.flowExploreSourcesLite()` | 书源列表缓存 |
| `RssSourceDao.flowAllLite()` | RSS 订阅源名称 |
| `BookDao.flowAll()` | 书架状态（判断书籍是否在架） |
| `RssStarDao` | RSS 收藏 |

---

## 三、与待删除功能的耦合度分析

### 有声书 (AudioPlay) — Phase 4 删除

| 检查点 | 结论 |
|--------|------|
| HomepageFragment 引用 | ❌ **无引用** |
| HomepageViewModel 引用 | ❌ **无引用** |
| HomepageScreen 引用 | ❌ **无引用** |
| MainActivity 引用 | ❌ **无引用** |

### 视频 (VideoPlay) — Phase 5 删除

| 检查点 | 结论 |
|--------|------|
| HomepageFragment 引用 | ❌ **无引用** |
| HomepageViewModel 引用 | ❌ **无引用** |
| HomepageScreen 引用 | ❌ **无引用** |
| MainActivity 引用 | ❌ **无引用** |

### TTS (ReadAloud) — Phase 6 删除

| 检查点 | 结论 |
|--------|------|
| HomepageFragment 引用 | ❌ **无引用** |
| HomepageViewModel 引用 | ❌ **无引用** |
| HomepageScreen 引用 | ❌ **无引用** |
| MainActivity 引用 | ⚠️ `BaseReadAloudService.pause` 在返回键逻辑中使用（行 135） |

> **结论**: 首页与 Audio/Video/TTS 零耦合。删除这些功能不会影响首页编译。唯一需要注意的是 MainActivity 中 `BaseReadAloudService.pause` 的引用（删除 TTS 时需要处理）。

---

## 四、Phase 2 改造要点分析

### 目标：从动态模块发现页 → 固定阅读仪表盘

| 当前状态 | Phase 2 目标 |
|---------|-------------|
| 由书源动态驱动的内容发现 | 由本地阅读数据驱动的仪表盘 |
| 用户配置的模块系统（增删改查） | 固定布局，5 个卡片区域 |
| 复杂的集/模块管理界面 | 无管理界面 |
| Compose + LazyColumn/ViewPager | 保持 Compose（已建立的 UI 框架） |
| 8 种模块类型 | 5 个固定卡片 |

### 可复用的组件

| 组件 | 复用方式 |
|------|---------|
| `HomepageFragment` | **保留骨架**：Fragment + ComposeView + LegadoThemeWithBackground 包装 |
| `HomepageScreen.kt` | **完全重写**：替换为仪表盘布局 |
| `HomepageViewModel.kt` | **大幅精简或替换**：去掉模块管理，改为直接查询本地数据 |
| `HomepageContract.kt` | **重写**：新 UI 状态模型 |
| `HomepageEffect.kt` | **保留结构**：更新副作用定义 |
| `HomepageConfig.kt` | **可保留**：仍用于 SharedPreferences 配置 |

### 新增数据源需求

| 仪表盘卡片 | 数据来源 | 状态 |
|-----------|---------|------|
| 最近阅读卡片 | `ReadBook` + `appDb.bookDao` | ✅ 已存在 |
| 统计双卡 | `ReadRecordDao.getTotalReadTime()` + 阅读计数 | ⚠️ 需确认 DAO 是否存在 |
| 最近书籍横滑列表 | `ReadRecordDao.getAllReadRecordsSortedByLastRead()` | ⚠️ 需确认 DAO 是否存在 |
| 今日阅读目标 | 新存储（SharedPreferences）+ 弧形 View | ❌ 需新增 |
| WebDAV 备份卡 | `AppWebDav` + `Backup` | ✅ 已存在 |

### 底部 Tab 保持不变

底部 5 Tab 由 `MainActivity.bottomNavigationView` + `main_bnv.xml` 控制，Phase 2 不涉及修改。

---

## 五、修改风险矩阵

| 风险 | 等级 | 说明 |
|------|------|------|
| Room schema 兼容 | 🟢 低 | 不修改数据库，只读已有 DAO |
| Backup/Restore 兼容 | 🟢 低 | 不修改备份逻辑 |
| 书源/RSS 功能 | 🟢 低 | 首页改造不影响书源和 RSS 核心 |
| 编译兼容 | 🟡 中 | 删除模块管理相关代码可能导致引用链错误 |
| UI 一致性 | 🟡 中 | 新仪表盘需保持与 Legado 现有设计语言一致 |
| 模块系统残留 | 🟢 低 | 旧模块相关文件不再被引用，后续可清理 |
| 公共组件误删 | 🟢 低 | 所有公共组件（如 `GlassCard`、`BookBottomSheet`）需确认其他页面仍使用 |

---

## 六、Phase 2 推荐实施路径

1. **重写 `HomepageScreen.kt`** — 替换为固定仪表盘布局
2. **新建简化 `HomepageViewModel`** — 直接查询 DAO，去掉模块管理
3. **保留 `HomepageFragment.kt`** — 骨架不变，仅换 Compose 内容
4. **新增** — 阅读目标存储 + 弧形进度 View
5. **编译 + 修复** — 删除旧模块管理 UI 文件引用
6. **Phase 3-6 之后再做** — 旧模块系统相关文件的最终清理
