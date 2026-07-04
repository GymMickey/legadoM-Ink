package io.legado.app.ui.book.search

import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.splitNotBlank
import splitties.init.appCtx

/**
 * 搜索范围
 * scope 格式说明:
 * - ""                           -> 全部书源
 * - "分组A,分组B"                 -> 多个分组（逗号分隔）
 * - "书源名::url"                 -> 单个书源（双冒号分隔）
 * - "书源名1::url1;;书源名2::url2" -> 多个书源（双分号分隔）
 */
@Suppress("unused")
data class SearchScope(private var scope: String) {

    constructor(groups: List<String>) : this(groups.joinToString(","))

    constructor(source: BookSource) : this(
        "${source.bookSourceName.replace(":", "")}::${source.bookSourceUrl}"
    )

    constructor(source: BookSourcePart) : this(
        "${source.bookSourceName.replace(":", "")}::${source.bookSourceUrl}"
    )

    override fun toString(): String {
        return scope
    }

    val stateLiveData = MutableLiveData(scope)

    fun update(scope: String, postValue: Boolean = true, save: Boolean = true) {
        this.scope = scope
        if (postValue) stateLiveData.postValue(scope)
        if (save) {
            save()
        }
    }

    fun update(groups: List<String>) {
        scope = groups.joinToString(",")
        stateLiveData.postValue(scope)
        save()
    }

    fun update(source: BookSource) {
        scope = "${source.bookSourceName}::${source.bookSourceUrl}"
        stateLiveData.postValue(scope)
        if (!isSource()) {
            save()
        }
    }

    fun isSource(): Boolean {
        return scope.contains("::")
    }

    val display: String
        get() {
            if (scope.contains("::")) {
                val sources = scope.split(";;")
                return if (sources.size == 1) {
                    scope.substringBefore("::")
                } else {
                    "${sources.first().substringBefore("::")} +${sources.size - 1}"
                }
            }
            if (scope.isEmpty()) {
                return appCtx.getString(R.string.all_source)
            }
            return scope
        }

    val displayNames: List<String>
        get() {
            val list = arrayListOf<String>()
            if (scope.contains("::")) {
                scope.split(";;").forEach {
                    list.add(it.substringBefore("::"))
                }
            } else {
                scope.splitNotBlank(",").forEach {
                    list.add(it)
                }
            }
            return list
        }

    fun remove(scope: String) {
        if (isSource()) {
            val sources = this.scope.split(";;").toMutableList()
            sources.removeAll { it.substringBefore("::") == scope }
            this.scope = sources.joinToString(";;")
        } else {
            val stringBuilder = StringBuilder()
            this.scope.split(",").forEach {
                if (it != scope) {
                    if (stringBuilder.isNotEmpty()) {
                        stringBuilder.append(",")
                    }
                    stringBuilder.append(it)
                }
            }
            this.scope = stringBuilder.toString()
        }
        stateLiveData.postValue(this.scope)
    }

    /**
     * 搜索范围书源
     * 临时分组通过展开为临时书源实现
     */
    fun getBookSourceParts(): List<BookSourcePart> {
        val list = hashSetOf<BookSourcePart>()
        if (scope.isEmpty()) {
            list.addAll(appDb.bookSourceDao.allEnabledPart)
        } else {
            if (scope.contains("::")) {
                scope.split(";;").forEach { sourceScope ->
                    sourceScope.substringAfter("::").let { url ->
                        appDb.bookSourceDao.getBookSourcePart(url)?.let { source ->
                            list.add(source)
                        }
                    }
                }
            } else {
                val oldScope = scope.splitNotBlank(",")
                val newScope = oldScope.filter {
                    val bookSources = appDb.bookSourceDao.getEnabledPartByGroup(it)
                    list.addAll(bookSources)
                    bookSources.isNotEmpty()
                }
                if (oldScope.size != newScope.size) {
                    update(newScope)
                    stateLiveData.postValue(scope)
                }
            }
            if (list.isEmpty()) {
                scope = ""
                appDb.bookSourceDao.allEnabledPart.let {
                    if (it.isNotEmpty()) {
                        stateLiveData.postValue(scope)
                        list.addAll(it)
                    }
                }
            }
        }
        return list.sortedBy { it.customOrder }
    }

    fun isAll(): Boolean {
        return scope.isEmpty()
    }

    fun save() {
        AppConfig.searchScope = scope
        if (isAll() || isSource() || scope.contains(",")) {
            AppConfig.searchGroup = ""
        } else {
            AppConfig.searchGroup = scope
        }
    }

    companion object {
        fun fromSources(sources: List<BookSourcePart>) = SearchScope(
            sources.joinToString(";;") { "${it.bookSourceName.replace(":", "")}::${it.bookSourceUrl}" }
        )
    }

}