package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.view.indices
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityBookshelfConfigBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.postEvent

class BookshelfConfigActivity : BaseActivity<ActivityBookshelfConfigBinding>() {

    override val binding by lazy {
        ActivityBookshelfConfigBinding.inflate(layoutInflater)
    }

    private var bookshelfSort = 0
    private var showBookname = 0
    private var bookLayout = 0
    private var folderLayout = 0

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initConfig()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bookshelf_config, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> {
                saveConfig()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initConfig() {
        val config = binding.configContent
        bookshelfSort = AppConfig.bookshelfSort
        showBookname = AppConfig.showBookname
        bookLayout = AppConfig.bookLayout
        folderLayout = AppConfig.folderLayout

        if (AppConfig.bookGroupStyle !in 0..<config.spGroupStyle.count) {
            AppConfig.bookGroupStyle = 0
        }
        if (bookshelfSort !in config.rgSort.indices) {
            bookshelfSort = 0
            AppConfig.bookshelfSort = 0
        }
        if (showBookname !in config.rgbLayout.indices) {
            showBookname = 0
            AppConfig.showBookname = 0
        }
        config.spGroupStyle.setSelection(AppConfig.bookGroupStyle)
        config.spBookView.setSelection(bookLayout)
        config.spFolderView.setSelection(folderLayout)
        config.llFolderView.visibility = if (AppConfig.bookGroupStyle == 1) View.VISIBLE else View.GONE
        config.swDropdownSelectGroup.visibility = if (AppConfig.bookGroupStyle == 0) View.VISIBLE else View.GONE
        config.swDropdownSelectGroup.isChecked = AppConfig.dropdownSelectGroup
        config.spGroupStyle.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                config.llFolderView.visibility = if (position == 1) View.VISIBLE else View.GONE
                config.swDropdownSelectGroup.visibility = if (position == 0) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        config.swShowUnread.isChecked = AppConfig.showUnread
        config.swShowLastUpdateTime.isChecked = AppConfig.showLastUpdateTime
        config.swShowWaitUpBooks.isChecked = AppConfig.showWaitUpCount
        config.swShowBookshelfFastScroller.isChecked = AppConfig.showBookshelfFastScroller
        config.llShowMoreInfo.visibility = if (bookLayout == 0) View.VISIBLE else View.GONE
        config.swShowMoreInfo.isChecked = AppConfig.showMoreInfoInList
        config.swShowIntro.isChecked = AppConfig.showIntroInList
        config.swShowTags.isChecked = AppConfig.showTagsInList
        config.swShowBookBorder.visibility = if (bookLayout <= 1) View.VISIBLE else View.GONE
        config.swShowBookBorder.isChecked = AppConfig.showBookBorder
        config.swShowIntro.visibility = if (AppConfig.showMoreInfoInList) View.VISIBLE else View.GONE
        config.swShowTags.visibility = if (AppConfig.showMoreInfoInList) View.VISIBLE else View.GONE
        config.tvIntroLines.visibility = if (AppConfig.showMoreInfoInList && AppConfig.showIntroInList) View.VISIBLE else View.GONE
        config.tvIntroLines.text = "${getString(R.string.intro_lines)}: ${AppConfig.introLinesInList}"
        config.swShowMoreInfo.setOnCheckedChangeListener { _, isChecked ->
            config.swShowIntro.visibility = if (isChecked) View.VISIBLE else View.GONE
            config.swShowTags.visibility = if (isChecked) View.VISIBLE else View.GONE
            config.tvIntroLines.visibility = if (isChecked && config.swShowIntro.isChecked) View.VISIBLE else View.GONE
        }
        config.swShowIntro.setOnCheckedChangeListener { _, isChecked ->
            config.tvIntroLines.visibility = if (isChecked && config.swShowMoreInfo.isChecked) View.VISIBLE else View.GONE
        }
        config.tvIntroLines.setOnClickListener {
            NumberPickerDialog(this)
                .setTitle(getString(R.string.intro_lines))
                .setMinValue(1)
                .setMaxValue(10)
                .setValue(AppConfig.introLinesInList)
                .show { newValue ->
                    AppConfig.introLinesInList = newValue
                    config.tvIntroLines.text = "${getString(R.string.intro_lines)}: ${AppConfig.introLinesInList}"
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
        }
        config.rgbLayout.checkByIndex(showBookname)
        config.bookNameChoice.visibility = if (bookLayout > 1) View.VISIBLE else View.GONE
        config.spBookView.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                config.bookNameChoice.visibility = if (position > 1) View.VISIBLE else View.GONE
                config.llShowMoreInfo.visibility = if (position == 0) View.VISIBLE else View.GONE
                config.swShowBookBorder.visibility = if (position <= 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        config.rgSort.checkByIndex(bookshelfSort)
        config.margin.progress = AppConfig.bookshelfMargin
    }

    private fun saveConfig() {
        val config = binding.configContent
        var recreate = false
        var refreshBookshelf = false
        if (AppConfig.bookGroupStyle != config.spGroupStyle.selectedItemPosition) {
            AppConfig.bookGroupStyle = config.spGroupStyle.selectedItemPosition
            recreate = true
        }
        if (bookLayout != config.spBookView.selectedItemPosition) {
            AppConfig.bookLayout = config.spBookView.selectedItemPosition
            refreshBookshelf = true
        }
        if (folderLayout != config.spFolderView.selectedItemPosition) {
            AppConfig.folderLayout = config.spFolderView.selectedItemPosition
            refreshBookshelf = true
        }
        if (showBookname != config.rgbLayout.getCheckedIndex()) {
            AppConfig.showBookname = config.rgbLayout.getCheckedIndex()
            recreate = true
        }
        if (AppConfig.bookshelfMargin != config.margin.progress) {
            AppConfig.bookshelfMargin = config.margin.progress
            recreate = true
        }
        if (AppConfig.showUnread != config.swShowUnread.isChecked) {
            AppConfig.showUnread = config.swShowUnread.isChecked
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        if (AppConfig.showLastUpdateTime != config.swShowLastUpdateTime.isChecked) {
            AppConfig.showLastUpdateTime = config.swShowLastUpdateTime.isChecked
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        if (AppConfig.showWaitUpCount != config.swShowWaitUpBooks.isChecked) {
            AppConfig.showWaitUpCount = config.swShowWaitUpBooks.isChecked
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        if (AppConfig.showBookshelfFastScroller != config.swShowBookshelfFastScroller.isChecked) {
            AppConfig.showBookshelfFastScroller = config.swShowBookshelfFastScroller.isChecked
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        if (AppConfig.showMoreInfoInList != config.swShowMoreInfo.isChecked) {
            AppConfig.showMoreInfoInList = config.swShowMoreInfo.isChecked
            refreshBookshelf = true
        }
        if (AppConfig.showIntroInList != config.swShowIntro.isChecked) {
            AppConfig.showIntroInList = config.swShowIntro.isChecked
            refreshBookshelf = true
        }
        if (AppConfig.showTagsInList != config.swShowTags.isChecked) {
            AppConfig.showTagsInList = config.swShowTags.isChecked
            refreshBookshelf = true
        }
        if (AppConfig.showBookBorder != config.swShowBookBorder.isChecked) {
            AppConfig.showBookBorder = config.swShowBookBorder.isChecked
            refreshBookshelf = true
        }
        if (AppConfig.dropdownSelectGroup != config.swDropdownSelectGroup.isChecked) {
            AppConfig.dropdownSelectGroup = config.swDropdownSelectGroup.isChecked
            recreate = true
        }
        if (bookshelfSort != config.rgSort.getCheckedIndex()) {
            AppConfig.bookshelfSort = config.rgSort.getCheckedIndex()
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        if (recreate) {
            postEvent(EventBus.RECREATE, "")
        } else if (refreshBookshelf) {
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        finish()
    }
}
