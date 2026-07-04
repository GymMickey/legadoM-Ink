package io.legado.app.ui.widget.dialog

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemBackupCategoryBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.BackupInfoHelper
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupInfoDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    enum class Mode {
        Backup,
        Restore
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val mode: Mode
        get() = arguments?.getString(ARG_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
            ?: Mode.Backup
    private val adapter by lazy { BackupInfoAdapter(requireContext(), mode) }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.85f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitleTextColor(primaryTextColor)
        toolBar.title = getString(
            if (mode == Mode.Restore) R.string.view_restore_info else R.string.view_backup_info
        )

        val lastTime = if (mode == Mode.Restore) LocalConfig.lastRestore else LocalConfig.lastBackup
        if (lastTime > 0) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            toolBar.subtitle = if (mode == Mode.Restore) {
                "上次恢复: ${dateFormat.format(Date(lastTime))}"
            } else {
                "上次备份: ${dateFormat.format(Date(lastTime))}"
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        loadInfo()
    }

    private fun loadInfo() {
        val overview = if (mode == Mode.Restore) {
            BackupInfoHelper.getRestoreOverview()
        } else {
            BackupInfoHelper.getBackupOverview()
        }

        if (overview.items.isEmpty()) {
            binding.tvMsg.visibility = View.VISIBLE
            binding.tvMsg.text = getString(
                if (mode == Mode.Restore) R.string.no_restore_data else R.string.no_backup_data
            )
            return
        }

        val items = mutableListOf<BackupInfoItem>()
        val selectedCount = overview.items.count { it.selected }
        items.add(
            BackupInfoItem.Header(
                title = if (mode == Mode.Restore) "恢复数据统计" else "备份数据统计",
                subtitle = if (mode == Mode.Restore) {
                    "将恢复 $selectedCount/${overview.items.size} 项"
                } else {
                    "已选择 $selectedCount/${overview.items.size} 项"
                },
                totalSize = BackupInfoHelper.formatSize(overview.selectedSize)
            )
        )

        BackupInfoHelper.categorizeItems(overview.items, onlySelected = false).forEach { cat ->
            items.add(
                BackupInfoItem.Category(
                    name = cat.name,
                    icon = cat.icon,
                    count = cat.items.size,
                    totalSize = BackupInfoHelper.formatSize(cat.totalSize)
                )
            )
            cat.items.forEach { item ->
                items.add(
                    BackupInfoItem.File(
                        fileName = item.fileName,
                        displayName = item.displayName,
                        size = BackupInfoHelper.formatSize(item.size),
                        selected = item.selected
                    )
                )
            }
        }

        adapter.setItems(items)
    }

    companion object {
        private const val ARG_MODE = "mode"

        fun newInstance(mode: Mode = Mode.Backup): BackupInfoDialog {
            return BackupInfoDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode.name)
                }
            }
        }
    }

    sealed class BackupInfoItem {
        data class Header(
            val title: String,
            val subtitle: String,
            val totalSize: String
        ) : BackupInfoItem()

        data class Category(
            val name: String,
            val icon: String,
            val count: Int,
            val totalSize: String
        ) : BackupInfoItem()

        data class File(
            val fileName: String,
            val displayName: String,
            val size: String,
            val selected: Boolean
        ) : BackupInfoItem()
    }

    class BackupInfoAdapter(
        context: Context,
        private val mode: Mode
    ) : RecyclerAdapter<BackupInfoItem, ItemBackupCategoryBinding>(context) {

        override fun getItemViewType(item: BackupInfoItem, position: Int): Int {
            return when (item) {
                is BackupInfoItem.Header -> 0
                is BackupInfoItem.Category -> 1
                is BackupInfoItem.File -> 2
            }
        }

        override fun getViewBinding(parent: ViewGroup): ItemBackupCategoryBinding {
            return ItemBackupCategoryBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemBackupCategoryBinding,
            item: BackupInfoItem,
            payloads: MutableList<Any>
        ) {
            when (item) {
                is BackupInfoItem.Header -> bindHeader(binding, item)
                is BackupInfoItem.Category -> bindCategory(binding, item)
                is BackupInfoItem.File -> bindFile(binding, item)
            }
        }

        private fun bindHeader(binding: ItemBackupCategoryBinding, item: BackupInfoItem.Header) {
            binding.root.visibility = View.VISIBLE
            binding.apply {
                tvIcon.text = "📦"
                tvTitle.text = item.title
                tvSubtitle.text = item.subtitle
                tvSubtitle.visibility = View.VISIBLE
                tvCount.text = item.totalSize
                tvCount.visibility = View.VISIBLE
                tvSize.visibility = View.GONE
            }
        }

        private fun bindCategory(binding: ItemBackupCategoryBinding, item: BackupInfoItem.Category) {
            binding.root.visibility = View.VISIBLE
            binding.apply {
                tvIcon.text = item.icon
                tvTitle.text = item.name
                tvSubtitle.visibility = View.GONE
                tvCount.text = "${item.count} 项"
                tvCount.visibility = View.VISIBLE
                tvSize.text = item.totalSize
                tvSize.visibility = View.VISIBLE
            }
        }

        private fun bindFile(binding: ItemBackupCategoryBinding, item: BackupInfoItem.File) {
            binding.root.visibility = View.VISIBLE
            binding.apply {
                tvIcon.text = if (item.selected) "  ✓" else "  -"
                tvTitle.text = item.displayName
                tvSubtitle.text = item.fileName
                tvSubtitle.visibility = View.VISIBLE
                tvCount.text = if (mode == Mode.Restore && !item.selected) "忽略" else ""
                tvCount.visibility = if (mode == Mode.Restore && !item.selected) View.VISIBLE else View.GONE
                tvSize.text = item.size
                tvSize.visibility = View.VISIBLE
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemBackupCategoryBinding) {
        }
    }
}
