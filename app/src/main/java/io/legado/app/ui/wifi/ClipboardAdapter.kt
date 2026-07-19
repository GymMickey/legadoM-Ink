package io.legado.app.ui.wifi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.WifiClipboard

class ClipboardAdapter(
    private val onCopy: (WifiClipboard) -> Unit,
    private val onDelete: (WifiClipboard) -> Unit
) : ListAdapter<WifiClipboard, ClipboardAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_clipboard, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.content.text = item.content
        holder.copy.setOnClickListener { onCopy(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val content: TextView = view.findViewById(R.id.tv_content)
        val copy: TextView = view.findViewById(R.id.tv_copy)
        val delete: TextView = view.findViewById(R.id.tv_delete)
    }

    object DiffCallback : DiffUtil.ItemCallback<WifiClipboard>() {
        override fun areItemsTheSame(old: WifiClipboard, new: WifiClipboard) =
            old.id == new.id
        override fun areContentsTheSame(old: WifiClipboard, new: WifiClipboard) =
            old.content == new.content && old.time == new.time
    }
}