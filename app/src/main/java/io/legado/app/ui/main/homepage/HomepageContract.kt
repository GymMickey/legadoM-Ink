/**
 * 首页 UI 契约定义
 *
 * 包含 E-Ink 精简版阅读仪表盘状态模型 + 旧模块系统兼容类型（Phase 3+ 清理）。
 */
package io.legado.app.ui.main.homepage

import androidx.compose.runtime.Stable
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.domain.model.BookShelfState
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.domain.model.ModuleDef

// ==================== E-Ink 精简版仪表盘状态 ====================

/**
 * 阅读仪表盘 UI 状态
 */
@Stable
data class HomepageDashboardState(
    val lastReadBook: Book? = null,
    val totalBooksRead: Int = 0,
    val totalReadTimeMs: Long = 0,
    val recentBooks: List<Book> = emptyList(),
    val webDavConfigured: Boolean = false,
    val isBackingUp: Boolean = false,
    val lastBackupTime: Long = 0L,
)

// ==================== 旧模块系统类型（Phase 3+ 清理移除） ====================

@Stable
data class HomepageBookItemUi(
    val book: SearchBook,
    val shelfState: BookShelfState = BookShelfState.NOT_IN_SHELF,
)

@Stable
data class HomepageUiState(
    val modules: List<HomepageModuleUi> = emptyList(),
    val isManageMode: Boolean = false,
    val isRefreshing: Boolean = false,
    val manageState: HomepageManageUiState = HomepageManageUiState(),
)

@Stable
data class HomepageModuleUi(
    val sourceUrl: String,
    val setName: String,
    val globalId: String,
    val type: HomepageModuleType,
    val title: String,
    val exploreUrl: String? = null,
    val customSetId: String? = null,
    val layoutConfig: String? = null,
    val state: ModuleLoadState = ModuleLoadState.Loading,
    val config: Map<String, String> = emptyMap()
)

@Stable
sealed interface ModuleLoadState {
    @Stable
    data object Loading : ModuleLoadState

    @Stable
    data class Loaded(
        val books: List<HomepageBookItemUi>,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        val page: Int = 1
    ) : ModuleLoadState

    @Stable
    data class Buttons(val kinds: List<ExploreKind>) : ModuleLoadState

    @Stable
    data class RankingTabs(
        val tabs: List<RankingTabData>,
        val selectedIndex: Int = 0
    ) : ModuleLoadState

    @Stable
    data class Error(val message: String) : ModuleLoadState
}

@Stable
data class RankingTabData(
    val title: String,
    val exploreUrl: String?,
    val books: List<HomepageBookItemUi>? = null,
    val errorMessage: String? = null,
)

@Stable
data class HomepageManageUiState(
    val sets: List<HomepageSourceManageUi> = emptyList(),
    val browseSources: List<HomepageSourceManageUi> = emptyList(),
    val allJoinedModules: List<HomepageModuleManageUi> = emptyList(),
    val sourceNames: Map<String, String> = emptyMap(),
)

@Stable
data class HomepageSourceManageUi(
    val sourceUrl: String,
    val sourceName: String,
    val sourceGroup: String? = null,
    val isSelected: Boolean = false,
    val moduleCount: Int = 0,
    val isCustomSet: Boolean = false,
    val sourceType: String? = null,
)

@Stable
data class HomepageModuleManageUi(
    val id: String = "",
    val sourceUrl: String = "",
    val sourceName: String = "",
    val moduleKey: String = "",
    val title: String = "",
    val customSetTitle: String? = null,
    val customSetId: String? = null,
    val isVisible: Boolean = true,
    val type: String = "",
    val url: String? = null,
    val args: String? = null,
    val layoutConfig: String? = null,
    val originalTitle: String = "",
    val sourceType: String = "book",
)

@Stable
data class HomepageManageActions(
    val onToggleSet: (String, Boolean) -> Unit,
    val onGetSourceModules: (String, String?) -> List<HomepageModuleManageUi>,
    val onSyncSourceModules: (String) -> Unit,
    val onToggleModule: (String, Boolean) -> Unit,
    val onJoinModule: (String, String?, ModuleDef) -> Unit,
    val onAddCustomModule: (String, String?, ModuleDef) -> Unit,
    val onAddButtonGroupFromKinds: (String, String?, String, List<String>) -> Unit,
    val onGetExploreKinds: suspend (String) -> List<ExploreKind>,
    val onGetRssKinds: suspend (String) -> List<Pair<String, String>>,
    val onAddRssCustomModule: (String, String?, ModuleDef) -> Unit,
    val onAddRssButtonGroupFromKinds: (String, String?, String, List<String>) -> Unit,
    val onAddRankingGroupFromKinds: (String, String?, String, List<Pair<String, String>>, String) -> Unit,
    val onAddRssRankingGroupFromKinds: (String, String?, String, List<Pair<String, String>>, String) -> Unit,
    val onUpdateModule: (String, ModuleDef) -> Unit,
    val onDeleteModule: (String) -> Unit,
    val onReorderModules: (List<String>) -> Unit,
    val onReorderSets: (List<String>) -> Unit,
    val onSetCustomSetTitle: (String, String?) -> Unit,
    val onCreateCustomSet: (String) -> Unit,
    val onRenameCustomSet: (String, String) -> Unit,
    val onDeleteCustomSet: (String) -> Unit,
    val onAssignModuleToCustomSet: (String, String?) -> Unit,
)