package io.legado.app.ui.replace

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import java.io.File

class ReplaceRuleContentSearchViewModel(application: Application) : BaseViewModel(application) {

    fun loadRules(allRules: Boolean, callback: (List<ReplaceRule>) -> Unit) {
        execute {
            if (allRules) {
                appDb.replaceRuleDao.all
            } else {
                appDb.replaceRuleDao.allEnabled
            }
        }.onSuccess {
            callback(it ?: emptyList())
        }.onError {
            callback(emptyList())
        }
    }

    fun exportRules(ruleIds: List<Long>, success: (File) -> Unit) {
        execute {
            val rules = appDb.replaceRuleDao.findByIds(*ruleIds.toLongArray())
            val path = "${context.filesDir}/shareReplaceRule.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            file.outputStream().buffered().use { out ->
                GSON.writeToOutputStream(out, rules)
            }
            file
        }.onSuccess {
            if (it != null) success(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }
}
