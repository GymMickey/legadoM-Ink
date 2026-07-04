package io.legado.app.help.config

import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit
import com.google.gson.reflect.TypeToken
import io.legado.app.utils.GSON
import splitties.init.appCtx

object SourceConfig {
    private val sp = appCtx.getSharedPreferences("SourceConfig", MODE_PRIVATE)
    private const val KEY_BOOK_SOURCE_GROUP_ORDER = "bookSourceGroupOrder"

    fun setBookScore(origin: String, name: String, author: String, score: Int) {
        sp.edit {
            val preScore = getBookScore(origin, name, author)
            var newScore = score
            if (preScore != 0) {
                newScore = score - preScore
            }

            putInt(origin, getSourceScore(origin) + newScore)

            putInt("${origin}_${name}_${author}", score)
        }
    }

    fun getBookScore(origin: String, name: String, author: String): Int {
        return sp.getInt("${origin}_${name}_${author}", 0)
    }

    fun getSourceScore(origin: String): Int {
        return sp.getInt(origin, 0)
    }


    fun removeSource(origin: String) {
        sp.all.keys.filter {
            it.startsWith(origin)
        }.let {
            sp.edit {
                it.forEach {
                    remove(it)
                }
            }
        }
    }

    fun getBookSourceGroupOrder(): List<String> {
        val json = sp.getString(KEY_BOOK_SOURCE_GROUP_ORDER, null) ?: return emptyList()
        return try {
            val type = TypeToken.getParameterized(List::class.java, String::class.java).type
            GSON.fromJson(json, type) as? List<String> ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setBookSourceGroupOrder(groups: List<String>) {
        sp.edit {
            putString(KEY_BOOK_SOURCE_GROUP_ORDER, GSON.toJson(groups))
        }
    }

}