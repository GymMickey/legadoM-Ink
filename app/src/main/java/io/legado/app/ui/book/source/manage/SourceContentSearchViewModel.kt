package io.legado.app.ui.book.source.manage

import android.app.Application
import com.google.gson.JsonObject
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import java.io.File
// 搜索来源内容逻辑
class SourceContentSearchViewModel(application: Application) : BaseViewModel(application) {

    fun loadSources(enabledOnly: Boolean, callback: (List<Triple<String, String, JsonObject>>) -> Unit) {
        execute {
            val sources = if (enabledOnly) {
                appDb.bookSourceDao.getAllSources().filter { it.enabled }
            } else {
                appDb.bookSourceDao.getAllSources()
            }
            sources.map { source ->
                Triple(
                    source.bookSourceName,
                    source.bookSourceUrl,
                    GSON.toJsonTree(source).asJsonObject
                )
            }
        }.onSuccess {
            callback(it ?: emptyList())
        }.onError {
            callback(emptyList())
        }
    }

    fun exportSources(sourceUrls: List<String>, success: (File) -> Unit) {
        execute {
            val sources = appDb.bookSourceDao.getAllSources().filter {
                it.bookSourceUrl in sourceUrls
            }
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            file.outputStream().buffered().use { out ->
                GSON.writeToOutputStream(out, sources)
            }
            file
        }.onSuccess {
            if (it != null) success(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }
}
