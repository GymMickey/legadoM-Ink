/**
 * 首页副作用定义（E-Ink 精简版）
 *
 * 阅读仪表盘的一次性 UI 事件（导航、提示）。
 */
package io.legado.app.ui.main.homepage

sealed interface HomepageEffect {
    /**
     * 跳转到书籍详情页
     */
    data class NavigateToBookInfo(
        val name: String?,
        val author: String?,
        val bookUrl: String,
        val origin: String? = null,
        val coverPath: String? = null,
    ) : HomepageEffect

    /**
     * 跳转到 WebDAV 设置页
     */
    data object NavigateToWebDavSettings : HomepageEffect

    /**
     * 显示恢复备份确认弹窗
     */
    data class ShowRestoreDialog(val backupName: String) : HomepageEffect

    /**
     * 显示 Snackbar / Toast 提示
     */
    data class ShowSnackbar(val message: String) : HomepageEffect

    data object NavigateToReadRecord : HomepageEffect

    data object NavigateToBookshelf : HomepageEffect
}
