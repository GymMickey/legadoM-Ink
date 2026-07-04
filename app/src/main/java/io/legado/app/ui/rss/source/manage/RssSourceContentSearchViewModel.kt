package io.legado.app.ui.rss.source.manage

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import java.io.File

class RssSourceContentSearchViewModel(application: Application) : BaseViewModel(application) {

    fun loadSources(allSources: Boolean, callback: (List<io.legado.app.data.entities.RssSource>) -> Unit) {
        execute {
            val sources = if (allSources) {
                appDb.rssSourceDao.all
            } else {
                appDb.rssSourceDao.all.filter { it.enabled }
            }
            sources
        }.onSuccess {
            callback(it ?: emptyList())
        }.onError {
            callback(emptyList())
        }
    }

    fun exportSources(sourceUrls: List<String>, success: (File) -> Unit) {
        execute {
            val sources = appDb.rssSourceDao.all.filter {
                it.sourceUrl in sourceUrls
            }
            val path = "${context.filesDir}/shareRssSource.json"
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
