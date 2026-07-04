package io.legado.app.ui.association

import android.app.Application
import android.os.Bundle
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.help.source.OpenUrlConfirmMemory
import io.legado.app.help.source.SourceHelp

class OpenUrlConfirmViewModel(app: Application): BaseViewModel(app) {

    var uri = ""
    var mimeType: String? = null
    var sourceOrigin = ""
    var sourceName = ""
    var sourceType = SourceType.book

    fun initData(arguments: Bundle) {
        uri = arguments.getString("uri") ?: ""
        mimeType = arguments.getString("mimeType")
        sourceName = arguments.getString("sourceName") ?: ""
        sourceOrigin = arguments.getString("sourceOrigin") ?: ""
        sourceType = arguments.getInt("sourceType", SourceType.book)
    }

    /** 返回此源的记忆行为: "allow" / "deny" / null */
    fun getRememberedAction(): String? {
        return OpenUrlConfirmMemory.getAction(getApplication(), sourceOrigin)
    }

    /** 记住选择: allow=true 允许, allow=false 拒绝 */
    fun rememberChoice(allow: Boolean) {
        OpenUrlConfirmMemory.remember(getApplication(), sourceOrigin, allow)
    }

    fun disableSource(block: () -> Unit) {
        execute {
            SourceHelp.enableSource(sourceOrigin, sourceType, false)
        }.onSuccess {
            block.invoke()
        }
    }

    fun deleteSource(block: () -> Unit) {
        execute {
            SourceHelp.deleteSource(sourceOrigin, sourceType)
        }.onSuccess {
            block.invoke()
        }
    }

}