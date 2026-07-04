package io.legado.app.ui.book.read.websearch

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

/**
 * 搜索引擎存储帮助类
 * 
 * 负责搜索引擎配置的持久化存储和读取
 * 使用 SharedPreferences 存储 JSON 格式的搜索引擎列表
 */
object SearchEngineHelper {

    /** 搜索引擎列表存储键 */
    private const val ENGINE_PREF_KEY = "readWebSearchEngines"

    /** 默认搜索引擎 URL 存储键 */
    private const val DEFAULT_ENGINE_PREF_KEY = "readWebSearchDefaultEngine"

    /** 自定义反序列化器：确保 JSON null 被替换为默认值 */
    private val searchEngineDeserializer = JsonDeserializer<SearchEngine> { json, _, _ ->
        val obj = json.asJsonObject
        SearchEngine(
            title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: "",
            url = obj.get("url")?.takeIf { !it.isJsonNull }?.asString ?: ""
        )
    }

    /** 使用自定义反序列化器的 GSON 实例 */
    private val engineGson: com.google.gson.Gson = GsonBuilder()
        .registerTypeAdapter(SearchEngine::class.java, searchEngineDeserializer)
        .create()

    /**
     * 加载搜索引擎列表
     * 
     * @param context Android Context
     * @return 搜索引擎列表，如果存储为空则返回默认列表
     */
    fun loadSearchEngines(context: Context): List<SearchEngine> {
        val stored = context.getPrefString(ENGINE_PREF_KEY) ?: return SearchEngine.defaultEngines()
        if (stored.isBlank()) return SearchEngine.defaultEngines()
        return try {
            engineGson.fromJson(stored, Array<SearchEngine>::class.java)
                ?.toList()
                ?.filter { it.title.isNotBlank() && it.url.contains(SearchEngine.QUERY_PLACEHOLDER) }
                .orEmpty()
                .ifEmpty { SearchEngine.defaultEngines() }
        } catch (e: Exception) {
            SearchEngine.defaultEngines()
        }
    }

    /**
     * 保存搜索引擎列表
     * 
     * @param context Android Context
     * @param engines 搜索引擎列表
     */
    fun saveSearchEngines(context: Context, engines: List<SearchEngine>) {
        context.putPrefString(ENGINE_PREF_KEY, engineGson.toJson(engines))
    }

    /**
     * 获取默认搜索引擎 URL
     * 
     * @param context Android Context
     * @return 默认搜索引擎 URL，如果没有设置则返回 null
     */
    fun getDefaultEngineUrl(context: Context): String? {
        return context.getPrefString(DEFAULT_ENGINE_PREF_KEY)
    }

    /**
     * 保存默认搜索引擎 URL
     * 
     * @param context Android Context
     * @param url 默认搜索引擎 URL
     */
    fun saveDefaultEngineUrl(context: Context, url: String?) {
        context.putPrefString(DEFAULT_ENGINE_PREF_KEY, url.orEmpty())
    }

    /**
     * 获取默认搜索引擎在列表中的索引
     * 
     * @param context Android Context
     * @param engines 搜索引擎列表
     * @return 默认搜索引擎的索引，如果没有匹配则返回 0
     */
    fun defaultEngineIndex(context: Context, engines: List<SearchEngine>): Int {
        val defaultUrl = getDefaultEngineUrl(context)
        val index = engines.indexOfFirst { it.url == defaultUrl }
        return if (index >= 0) index else 0
    }

    /**
     * 确保默认搜索引擎有效
     * 如果当前默认搜索引擎不在列表中，则设置为列表中的第一个
     * 
     * @param context Android Context
     * @param engines 搜索引擎列表
     */
    fun ensureValidDefaultEngine(context: Context, engines: List<SearchEngine>) {
        val defaultUrl = getDefaultEngineUrl(context)
        if (defaultUrl.isNullOrBlank() || engines.none { it.url == defaultUrl }) {
            saveDefaultEngineUrl(context, engines.firstOrNull()?.url)
        }
    }

    /**
     * 判断某个搜索引擎是否为默认搜索引擎
     * 
     * @param context Android Context
     * @param position 搜索引擎在列表中的位置
     * @param engine 搜索引擎对象
     * @return true 表示是默认搜索引擎
     */
    fun isDefaultEngine(context: Context, position: Int, engine: SearchEngine): Boolean {
        val defaultUrl = getDefaultEngineUrl(context)
        return if (defaultUrl.isNullOrBlank()) {
            position == 0
        } else {
            engine.url == defaultUrl
        }
    }
}