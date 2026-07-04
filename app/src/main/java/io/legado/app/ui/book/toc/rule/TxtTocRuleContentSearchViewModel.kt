package io.legado.app.ui.book.toc.rule

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import java.io.File
// 目录规则内容查询搜索逻辑
class TxtTocRuleContentSearchViewModel(application: Application) : BaseViewModel(application) {

    fun loadRules(allRules: Boolean, callback: (List<TxtTocRule>) -> Unit) {
        execute {
            if (allRules) {
                appDb.txtTocRuleDao.all
            } else {
                appDb.txtTocRuleDao.enabled
            }
        }.onSuccess {
            callback(it ?: emptyList())
        }.onError {
            callback(emptyList())
        }
    }

    fun exportRules(ruleIds: List<Long>, success: (File) -> Unit) {
        execute {
            val rules = appDb.txtTocRuleDao.all.filter { it.id in ruleIds }
            val path = "${context.filesDir}/shareTxtTocRule.json"
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
