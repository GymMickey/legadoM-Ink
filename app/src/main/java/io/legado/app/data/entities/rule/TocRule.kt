package io.legado.app.data.entities.rule

import android.os.Parcelable
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSyntaxException
import io.legado.app.utils.INITIAL_GSON
import kotlinx.parcelize.Parcelize

@Parcelize
data class TocRule(
    var preUpdateJs: String? = null,
    var chapterList: String? = null,
    var chapterName: String? = null,
    var chapterUrl: String? = null,
    var formatJs: String? = null,
    var isVolume: String? = null,
    var isVip: String? = null,
    var isPay: String? = null,
    var updateTime: String? = null,
    var nextTocUrl: String? = null
) : Parcelable {

    companion object {

        val jsonDeserializer = JsonDeserializer<TocRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, TocRule::class.java)
                json.isJsonPrimitive -> runCatching {
                    INITIAL_GSON.fromJson(json.asString, TocRule::class.java)
                }.getOrElse {
                    if (it is JsonSyntaxException || it is ClassCastException) {
                        TocRule(chapterList = json.asString)
                    } else {
                        throw it
                    }
                }
                else -> null
            }
        }

    }

}
