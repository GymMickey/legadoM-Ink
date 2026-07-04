package io.legado.app.ui.dict.rule

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import java.io.File

class DictRuleContentSearchViewModel(application: Application) : BaseViewModel(application) {

    fun loadRules(allRules: Boolean, callback: (List<DictRule>) -> Unit) {
        execute {
            if (allRules) {
                appDb.dictRuleDao.all
            } else {
                appDb.dictRuleDao.enabled
            }
        }.onSuccess {
            callback(it ?: emptyList())
        }.onError {
            callback(emptyList())
        }
    }

    fun exportRules(ruleNames: List<String>, success: (File) -> Unit) {
        execute {
            val rules = appDb.dictRuleDao.all.filter { it.name in ruleNames }
            val path = "${context.filesDir}/shareDictRule.json"
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
