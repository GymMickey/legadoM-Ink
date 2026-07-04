package io.legado.app.ui.book.read.websearch

/**
 * 搜索引擎数据类
 * 
 * 用于定义网页搜索的搜索引擎配置
 * 包含搜索引擎的名称和搜索 URL 模板
 */
data class SearchEngine(
    val title: String = "",
    val url: String = ""
) {

    /**
     * 构建搜索 URL
     * 将查询关键词替换到 URL 模板中的 {query} 占位符
     * 
     * @param query 搜索关键词
     * @return 完整的搜索 URL
     */
    fun buildUrl(query: String): String {
        return url.replace(QUERY_PLACEHOLDER, encodeQuery(query))
    }

    companion object {
        /** URL 模板中的查询关键词占位符 */
        const val QUERY_PLACEHOLDER = "{query}"

        /** 必应搜索模板 */
        val BING_TEMPLATE = SearchEngine("必应", "https://www.bing.com/search?q={query}")

        /** 百度搜索模板 */
        val BAIDU_TEMPLATE = SearchEngine("百度", "https://www.baidu.com/s?wd={query}")

        /**
         * URL 编码查询关键词
         * 
         * @param query 原始查询关键词
         * @return 编码后的查询关键词
         */
        fun encodeQuery(query: String): String {
            return java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        }

        /**
         * 获取默认搜索引擎列表
         * 
         * @return 默认的必应和百度搜索引擎列表
         */
        fun defaultEngines(): List<SearchEngine> {
            return listOf(BING_TEMPLATE, BAIDU_TEMPLATE)
        }
    }
}