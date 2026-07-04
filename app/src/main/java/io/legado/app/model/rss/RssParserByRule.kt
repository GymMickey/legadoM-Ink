package io.legado.app.model.rss

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.debug.RssExecutionRecorder
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setRuleData
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.model.debug.DebugCategory
import io.legado.app.model.debug.RssExecutionStep
import io.legado.app.model.debug.RuleExecutionTracker
import io.legado.app.model.debug.RuleType
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.currentCoroutineContext
import splitties.init.appCtx
import java.util.Locale

/**
 * RSS规则解析器
 *
 * 根据订阅源定义的规则解析文章列表，支持：
 * - 列表规则解析（HTML/XML/JSON）
 * - 标题、时间、描述、图片、链接规则
 * - 下一页规则
 * - 列表反转
 *
 * 当列表规则为空时，回退到 [RssParserDefault] 使用标准XML解析。
 *
 * @see Rss.getArticles 网络请求入口
 * @see RssParserDefault 默认XML解析器
 */
@Keep
object RssParserByRule {

    /**
     * 解析RSS内容
     *
     * @param sortName 分类名称
     * @param sortUrl 分类URL
     * @param redirectUrl 重定向后的URL
     * @param body 页面内容
     * @param rssSource 订阅源
     * @param ruleData 规则数据对象
     * @return 文章列表和下一页URL的Pair
     * @throws NoStackTraceException 当内容为空时抛出
     */
    @Throws(Exception::class)
    suspend fun parseXML(
        sortName: String,
        sortUrl: String,
        redirectUrl: String,
        body: String?,
        rssSource: RssSource,
        ruleData: RuleData
    ): Pair<MutableList<RssArticle>, String?> {
        val sourceUrl = rssSource.sourceUrl
        val recorder = RssExecutionRecorder
        var nextUrl: String? = null
        if (body.isNullOrBlank()) {
            recorder.failed(RssExecutionStep.PARSE_LIST, "响应内容为空")
            throw NoStackTraceException(
                appCtx.getString(R.string.error_get_web_content, rssSource.sourceUrl)
            )
        }
        Debug.log(sourceUrl, body, state = 10, category = DebugCategory.RSS)
        var ruleArticles = rssSource.ruleArticles
        if (ruleArticles.isNullOrBlank()) {
            Debug.log(sourceUrl, "⇒列表规则为空, 使用默认规则解析", category = DebugCategory.RSS)
            val result = RssParserDefault.parseXML(sortName, body, sourceUrl)
            if (result.first.isNotEmpty()) {
                recorder.success(RssExecutionStep.PARSE_LIST,
                    detail = "默认XML解析获取${result.first.size}条")
            } else {
                recorder.failed(RssExecutionStep.PARSE_LIST, "默认XML解析未获取到文章")
            }
            return result
        } else {
            val articleList = mutableListOf<RssArticle>()
            val analyzeRule = AnalyzeRule(ruleData, rssSource)
            analyzeRule.setCoroutineContext(currentCoroutineContext())
            analyzeRule.setContent(body).setBaseUrl(sortUrl)
            analyzeRule.setRedirectUrl(redirectUrl)
            var reverse = false
            if (ruleArticles.startsWith("-")) {
                reverse = true
                ruleArticles = ruleArticles.substring(1)
            }
            // 追踪列表规则执行，构建执行树用于调试展示
            Debug.log(sourceUrl, "┌获取列表", category = DebugCategory.RSS)
            val parseStart = System.currentTimeMillis()
            val listTracker = RuleExecutionTracker(rssSource, ruleArticles, "列表规则")
            listTracker.startStep(RuleType.DEFAULT, ruleArticles, body)
            val collections = analyzeRule.getElements(ruleArticles)
            val listDuration = System.currentTimeMillis() - parseStart
            listTracker.endStep("${collections.size}个元素", matchCount = collections.size)
            val listTree = listTracker.buildTree()
            Debug.log(sourceUrl, "└列表大小:${collections.size}", category = DebugCategory.RSS)
            if (collections.isNotEmpty()) {
                recorder.success(RssExecutionStep.PARSE_LIST,
                    detail = "获取${collections.size}条", duration = listDuration)
                // 记录列表规则执行的详细信息（规则内容、执行树、输入输出、匹配数量、耗时）
                recorder.ruleSuccess(
                    step = RssExecutionStep.PARSE_RULE_ARTICLES,
                    ruleContent = ruleArticles,
                    executionTree = listTree,
                    input = body.take(200),
                    output = "${collections.size}个元素",
                    matchCount = collections.size,
                    duration = listDuration
                )
            } else {
                recorder.failed(RssExecutionStep.PARSE_LIST, "列表为空",
                    duration = listDuration)
            }
            // 追踪下一页规则执行
            if (!rssSource.ruleNextPage.isNullOrEmpty()) {
                Debug.log(sourceUrl, "┌获取下一页链接", category = DebugCategory.RSS)
                val nextStart = System.currentTimeMillis()
                val nextTracker = RuleExecutionTracker(rssSource, rssSource.ruleNextPage!!, "下一页规则")
                nextTracker.startStep(RuleType.DEFAULT, rssSource.ruleNextPage!!, null)
                if (rssSource.ruleNextPage!!.uppercase(Locale.getDefault()) == "PAGE") {
                    nextUrl = sortUrl
                } else {
                    nextUrl = analyzeRule.getString(rssSource.ruleNextPage)
                    if (nextUrl.isNotEmpty()) {
                        nextUrl = NetworkUtils.getAbsoluteURL(sortUrl, nextUrl)
                    }
                }
                val nextDuration = System.currentTimeMillis() - nextStart
                nextTracker.endStep(nextUrl)
                val nextTree = nextTracker.buildTree()
                Debug.log(sourceUrl, "└$nextUrl", category = DebugCategory.RSS)
                recorder.ruleSuccess(
                    step = RssExecutionStep.PARSE_RULE_NEXT_PAGE,
                    ruleContent = rssSource.ruleNextPage,
                    executionTree = nextTree,
                    output = nextUrl,
                    duration = nextDuration
                )
            }
            val ruleTitle = analyzeRule.splitSourceRule(rssSource.ruleTitle)
            val rulePubDate = analyzeRule.splitSourceRule(rssSource.rulePubDate)
            val ruleDescription = analyzeRule.splitSourceRule(rssSource.ruleDescription)
            val ruleImage = analyzeRule.splitSourceRule(rssSource.ruleImage)
            val ruleLink = analyzeRule.splitSourceRule(rssSource.ruleLink)
            val variable = ruleData.getVariable()
            for ((index, item) in collections.withIndex()) {
                getItem(
                    sourceUrl, item, analyzeRule, variable, rssSource.type, index == 0,
                    ruleTitle, rulePubDate, ruleDescription, ruleImage, ruleLink,
                    rssSource, recorder
                )?.let {
                    it.sort = sortName
                    it.origin = sourceUrl
                    articleList.add(it)
                }
            }
            if (reverse) {
                articleList.reverse()
            }
            return Pair(articleList, nextUrl)
        }
    }

    /**
     * 解析单个文章项
     *
     * 从列表元素中解析单个文章信息，包括：
     * - 标题、发布时间
     * - 描述、图片链接
     * - 文章链接
     *
     * @param sourceUrl 源URL
     * @param item 列表元素
     * @param analyzeRule 规则解析器
     * @param variable 变量值
     * @param type 文章类型
     * @param log 是否输出调试日志
     * @param ruleTitle 标题规则
     * @param rulePubDate 发布时间规则
     * @param ruleDescription 描述规则
     * @param ruleImage 图片规则
     * @param ruleLink 链接规则
     * @return 文章对象，如果标题为空则返回null
     */
    private fun getItem(
        sourceUrl: String,
        item: Any,
        analyzeRule: AnalyzeRule,
        variable: String?,
        type: Int,
        log: Boolean,
        ruleTitle: List<AnalyzeRule.SourceRule>,
        rulePubDate: List<AnalyzeRule.SourceRule>,
        ruleDescription: List<AnalyzeRule.SourceRule>,
        ruleImage: List<AnalyzeRule.SourceRule>,
        ruleLink: List<AnalyzeRule.SourceRule>,
        rssSource: RssSource,
        recorder: RssExecutionRecorder
    ): RssArticle? {
        val rssArticle = RssArticle(variable = variable)
        analyzeRule.setRuleData(rssArticle)
        analyzeRule.setContent(item)
        
        // 追踪标题规则执行
        val titleRuleStr = ruleTitle.joinToString("&&") { it.rule }
        val titleStart = System.currentTimeMillis()
        Debug.log(sourceUrl, "┌获取标题", log, category = DebugCategory.RSS)
        rssArticle.title = analyzeRule.getString(ruleTitle)
        val titleDuration = System.currentTimeMillis() - titleStart
        Debug.log(sourceUrl, "└${rssArticle.title}", log, category = DebugCategory.RSS)
        if (log) {
            if (rssArticle.title.isNotBlank()) {
                recorder.success(RssExecutionStep.EXTRACT_TITLE, detail = rssArticle.title.take(50))
                if (titleRuleStr.isNotBlank()) {
                    recorder.ruleSuccess(
                        step = RssExecutionStep.PARSE_RULE_TITLE,
                        ruleContent = titleRuleStr,
                        output = rssArticle.title,
                        duration = titleDuration
                    )
                }
            } else {
                recorder.failed(RssExecutionStep.EXTRACT_TITLE, "标题为空")
            }
        }
        
        // 追踪发布日期规则执行
        val pubDateRuleStr = rulePubDate.joinToString("&&") { it.rule }
        val pubDateStart = System.currentTimeMillis()
        Debug.log(sourceUrl, "┌获取时间", log, category = DebugCategory.RSS)
        rssArticle.pubDate = analyzeRule.getString(rulePubDate)
        val pubDateDuration = System.currentTimeMillis() - pubDateStart
        Debug.log(sourceUrl, "└${rssArticle.pubDate}", log, category = DebugCategory.RSS)
        if (log) {
            if (!rssArticle.pubDate.isNullOrBlank()) {
                recorder.success(RssExecutionStep.EXTRACT_PUB_DATE, detail = rssArticle.pubDate?.take(50))
                if (pubDateRuleStr.isNotBlank()) {
                    recorder.ruleSuccess(
                        step = RssExecutionStep.PARSE_RULE_PUB_DATE,
                        ruleContent = pubDateRuleStr,
                        output = rssArticle.pubDate,
                        duration = pubDateDuration
                    )
                }
            } else {
                recorder.failed(RssExecutionStep.EXTRACT_PUB_DATE, "未提取到发布日期")
            }
        }
        
        Debug.log(sourceUrl, "┌获取描述", log, category = DebugCategory.RSS)
        if (ruleDescription.isEmpty()) {
            rssArticle.description = null
            Debug.log(sourceUrl, "└描述规则为空，将会解析内容页", log, category = DebugCategory.RSS)
            if (log) recorder.success(RssExecutionStep.EXTRACT_DESCRIPTION, detail = "规则为空，将解析内容页")
        } else {
            // 追踪描述规则执行
            val descRuleStr = ruleDescription.joinToString("&&") { it.rule }
            val descStart = System.currentTimeMillis()
            rssArticle.description = analyzeRule.getString(ruleDescription)
            val descDuration = System.currentTimeMillis() - descStart
            Debug.log(sourceUrl, "└${rssArticle.description}", log, category = DebugCategory.RSS)
            if (log) {
                if (!rssArticle.description.isNullOrBlank()) {
                    recorder.success(RssExecutionStep.EXTRACT_DESCRIPTION, detail = rssArticle.description!!.take(50))
                    if (descRuleStr.isNotBlank()) {
                        recorder.ruleSuccess(
                            step = RssExecutionStep.PARSE_RULE_DESCRIPTION,
                            ruleContent = descRuleStr,
                            output = rssArticle.description,
                            duration = descDuration
                        )
                    }
                } else {
                    recorder.failed(RssExecutionStep.EXTRACT_DESCRIPTION, "未提取到描述")
                }
            }
        }
        
        Debug.log(sourceUrl, "┌获取图片url", log, category = DebugCategory.RSS)
        try {
            // 追踪图片规则执行
            val imageRuleStr = ruleImage.joinToString("&&") { it.rule }
            val imageStart = System.currentTimeMillis()
            analyzeRule.getString(ruleImage).let {
                if (it.isNotEmpty()) {
                    rssArticle.image = NetworkUtils.getAbsoluteURL(sourceUrl, it)
                }
            }
            val imageDuration = System.currentTimeMillis() - imageStart
            Debug.log(sourceUrl, "└${rssArticle.image ?: ""}", log, category = DebugCategory.RSS)
            if (log) {
                if (!rssArticle.image.isNullOrBlank()) {
                    recorder.success(RssExecutionStep.EXTRACT_IMAGE, detail = rssArticle.image!!.take(80))
                    if (imageRuleStr.isNotBlank()) {
                        recorder.ruleSuccess(
                            step = RssExecutionStep.PARSE_RULE_IMAGE,
                            ruleContent = imageRuleStr,
                            output = rssArticle.image,
                            duration = imageDuration
                        )
                    }
                } else {
                    recorder.failed(RssExecutionStep.EXTRACT_IMAGE, "未提取到图片")
                }
            }
        } catch (e: Exception) {
            Debug.log(sourceUrl, "└${e.localizedMessage}", log, category = DebugCategory.RSS)
            if (log) recorder.failed(RssExecutionStep.EXTRACT_IMAGE, e.message ?: "提取图片异常")
        }
        
        // 追踪链接规则执行
        val linkRuleStr = ruleLink.joinToString("&&") { it.rule }
        val linkStart = System.currentTimeMillis()
        Debug.log(sourceUrl, "┌获取文章链接", log, category = DebugCategory.RSS)
        rssArticle.link = analyzeRule.getString(ruleLink, isUrl = true)
        val linkDuration = System.currentTimeMillis() - linkStart
        Debug.log(sourceUrl, "└${rssArticle.link}", log, category = DebugCategory.RSS)
        if (log) {
            if (rssArticle.link.isNotBlank()) {
                recorder.success(RssExecutionStep.EXTRACT_LINK, detail = rssArticle.link.take(80))
                if (linkRuleStr.isNotBlank()) {
                    recorder.ruleSuccess(
                        step = RssExecutionStep.PARSE_RULE_LINK,
                        ruleContent = linkRuleStr,
                        output = rssArticle.link,
                        duration = linkDuration
                    )
                }
            } else {
                recorder.failed(RssExecutionStep.EXTRACT_LINK, "未提取到链接")
            }
        }
        rssArticle.type = type
        if (rssArticle.title.isBlank()) {
            return null
        }
        return rssArticle
    }
}