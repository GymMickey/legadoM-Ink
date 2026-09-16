package io.legado.app.help

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

@Suppress("unused")
object IntentHelp {

    fun getBrowserIntent(url: String): Intent {
        return getBrowserIntent(Uri.parse(url))
    }

    fun getBrowserIntent(uri: Uri): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = uri
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(appCtx.packageManager) == null) {
            return Intent.createChooser(intent, "请选择浏览器")
        }
        return intent
    }

    fun toInstallUnknown(context: Context) {
        kotlin.runCatching {
            val intent = Intent()
            intent.action = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }.onFailure {
            context.toastOnUi("无法打开设置")
        }
    }

}
