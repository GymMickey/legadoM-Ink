package io.legado.app.help

import android.content.res.AssetManager

object HelpDocManager {
    // 帮助文档切换分组（显示在文档切换器中）
    val helpDocGroups = listOf(
        HelpDocGroup(
            "legado基本文档",
            listOf(
                HelpDoc("ruleHelp", "书源制作教程"),
                HelpDoc("jsHelp", "js变量和函数"),
                HelpDoc("rssRuleHelp", "订阅源规则帮助"),
                HelpDoc("xpathHelp", "xpath语法教程"),
                HelpDoc("regexHelp", "正则表达式教程"),
                HelpDoc("txtTocRuleHelp", "txt目录正则说明"),
                HelpDoc("dictRuleHelp", "字典规则说明"),
                HelpDoc("httpTTSHelp", "在线朗读规则"),
                HelpDoc("debugHelp", "书源调试说明"),
                HelpDoc("homepageHelp", "首页功能使用帮助")
            )
        ),
        HelpDocGroup(
            "legado高级文档",
            listOf(
                HelpDoc("jsPackagesHelp", "JS Packages使用指南"),
                HelpDoc("legado_java_api_reference", "Java/Android/第三方库API参考"),
                HelpDoc("书源字段规则类型", "书源字段规则类型"),
                HelpDoc("订阅源字段规则类型", "订阅源字段规则类型"),
                HelpDoc("打印源对象的JS对象", "打印源JS对象"),
                HelpDoc("legado_data_objects", "书源JS格式文档"),
                HelpDoc("legado_data_url_guide", "Data URL使用指南"),
                HelpDoc("legado_network_api", "网络访问 API 参考"),
                HelpDoc("highlightRules", "高亮规则说明")
            )
        ),
        HelpDocGroup(
            "深入理解legado机制",
            listOf(
                HelpDoc("订阅源机制详解", "订阅源机制详解"),
                HelpDoc("预注入JS机制详解", "订阅源预注入JS机制"),
                HelpDoc("替换规则流程与机制", "替换规则流程与机制"),
                HelpDoc("jsVariableHelp", "JS 变量存储机制详解"),
                HelpDoc("下拉刷新流程与机制", "下拉刷新流程与机制"),
                HelpDoc("图片加载机制", "图片加载机制"),
                HelpDoc("网络请求机制", "网络请求机制"),
                HelpDoc("错误处理机制", "错误处理机制"),
                HelpDoc("正文下一页懒加载与缓存机制", "正文下一页懒加载与缓存机制"),
                HelpDoc("目录不完全加载机制", "目录不完全加载机制"),
                HelpDoc("书源登录信息与运行变量备份机制", "登录信息与运行变量备份机制"),
                HelpDoc("bookCacheHelp", "书籍缓存备份机制")
            )
        ),
        HelpDocGroup(
            "其他文档",
            listOf(
                HelpDoc("部分功能需要安卓特定版本", "安卓特定版本功能"),
                HelpDoc("ExtensionContentType", "扩展内容类型")
            )
        )
    )

    // 帮助文档切换列表（显示在文档切换器中）
    val allHelpDocs: List<HelpDoc>
        get() = helpDocGroups.flatMap { it.docs }

    // 隐藏的帮助文档（可以在某些界面加载查看，但不会出现在切换列表中）
    private val hiddenHelpDocs = listOf(
        HelpDoc("SourceMBookHelp", "书源管理界面帮助"),
        HelpDoc("SourceMRssHelp", "订阅源管理界面帮助"),
        HelpDoc("replaceRuleHelp", "替换规则说明"),
        HelpDoc("readMenuHelp", "阅读界面帮助文档"),
        HelpDoc("webDavBookHelp", "WebDav书籍简明使用教程"),
        HelpDoc("webDavHelp", "WebDav备份教程"),
        HelpDoc("updateLog", "更新日志")
    )
    
    // 所有帮助文档（切换列表 + 隐藏文档）
    val allDocs: List<HelpDoc>
        get() = allHelpDocs + hiddenHelpDocs
    
    // 加载帮助文档
    fun loadDoc(assets: AssetManager, fileName: String): String {
        return String(assets.open("web/help/md/${fileName}.md").readBytes())
    }
    
    // 获取帮助文档在切换列表中的索引
    fun getDocIndex(fileName: String): Int {
        return allHelpDocs.indexOfFirst { it.fileName == fileName }
    }

    // 获取帮助文档所在分组的索引
    fun getDocGroupIndex(fileName: String): Int {
        return helpDocGroups.indexOfFirst { group ->
            group.docs.any { it.fileName == fileName }
        }
    }

    // 获取帮助文档在所在分组内的索引
    fun getDocIndexInGroup(fileName: String): Int {
        val group = helpDocGroups.firstOrNull { group ->
            group.docs.any { it.fileName == fileName }
        } ?: return -1
        return group.docs.indexOfFirst { it.fileName == fileName }
    }
    
    // 根据文件名获取文档（优先从切换列表查找，找不到再从隐藏文档查找）
    fun getDocByFileName(fileName: String): HelpDoc? {
        return allHelpDocs.find { it.fileName == fileName }
            ?: hiddenHelpDocs.find { it.fileName == fileName }
    }
    
    // 判断文档是否为隐藏文档
    fun isHiddenDoc(fileName: String): Boolean {
        return hiddenHelpDocs.any { it.fileName == fileName }
    }

    // 自定义文档分组(延迟加载)
    private var customGroupsCache: List<CustomHelpDocGroup>? = null

    /**
     * 获取自定义文档分组
     */
    fun getCustomGroups(context: android.content.Context): List<CustomHelpDocGroup> {
        if (customGroupsCache == null) {
            customGroupsCache = CustomHelpDocManager.scanCustomDocs(context)
        }
        return customGroupsCache ?: emptyList()
    }

    /**
     * 刷新自定义文档缓存
     */
    fun refreshCustomGroups(context: android.content.Context) {
        customGroupsCache = CustomHelpDocManager.scanCustomDocs(context, forceRefresh = true)
    }

}
