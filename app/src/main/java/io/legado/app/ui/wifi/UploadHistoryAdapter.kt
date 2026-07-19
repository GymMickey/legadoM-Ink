package io.legado.app.ui.wifi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.WifiUploadRecord

/**
 * 上传历史 Adapter
 * 单行文件名
 */
class UploadHistoryAdapter : ListAdapter<WifiUploadRecord, UploadHistoryAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_upload_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.fileName.text = item.fileName
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.tv_file_name)
    }

    object DiffCallback : DiffUtil.ItemCallback<WifiUploadRecord>() {
        override fun areItemsTheSame(old: WifiUploadRecord, new: WifiUploadRecord) = old.id == new.id
        override fun areContentsTheSame(old: WifiUploadRecord, new: WifiUploadRecord) =
            old.fileName == new.fileName && old.uploadTime == new.uploadTime
    }
}