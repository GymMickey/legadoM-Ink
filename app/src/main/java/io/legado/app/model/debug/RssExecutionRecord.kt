package io.legado.app.model.debug

/**
 * 订阅源执行步骤
 *
 * 按照订阅源执行的自然顺序排列，
 * 区分"配置检查"（字段是否为空）和"执行步骤"（运行是否成功）。
 */
enum class RssExecutionStep(val displayName: String, val isConfigCheck: Boolean) {
    SOURCE_NAME("源名称", true),
    SOURCE_URL("源URL", true),
    SOURCE_ICON("图标", true),
    SOURCE_GROUP("源分组", true),
    SORT_URL("分类URL", true),
    RULE_ARTICLES("列表规则", true),
    RULE_NEXT_PAGE("下一页规则", true),
    RULE_TITLE("标题规则", true),
    RULE_PUB_DATE("发布日期规则", true),
    RULE_DESCRIPTION("描述规则", true),
    RULE_IMAGE("图片规则", true),
    RULE_LINK("链接规则", true),
    RULE_CONTENT("正文规则", true),
    SHOULD_OVERRIDE_URL("url跳转拦截", true),
    NETWORK_REQUEST("网络请求", false),
    RESPONSE_BODY("响应内容", false),
    PARSE_LIST("列表解析", false),
    PARSE_RULE_ARTICLES("列表规则解析", false),
    PARSE_RULE_NEXT_PAGE("下一页规则解析", false),
    PARSE_RULE_TITLE("标题规则解析", false),
    PARSE_RULE_PUB_DATE("发布日期规则解析", false),
    PARSE_RULE_DESCRIPTION("描述规则解析", false),
    PARSE_RULE_IMAGE("图片规则解析", false),
    PARSE_RULE_LINK("链接规则解析", false),
    EXTRACT_TITLE("提取标题", false),
    EXTRACT_PUB_DATE("提取发布日期", false),
    EXTRACT_DESCRIPTION("提取描述", false),
    EXTRACT_IMAGE("提取图片", false),
    EXTRACT_LINK("提取链接", false);
}

/**
 * 步骤执行状态
 */
enum class RssExecutionStatus(val icon: String, val displayName: String) {
    SUCCESS("✔", "执行正确"),
    FAILED("✘", "执行失败"),
    SKIPPED("⊘", "跳过执行"),
    EMPTY_SKIP("⊘", "为空跳过执行")
}

/**
 * 订阅源执行记录
 */
data class RssExecutionRecord(
    val step: RssExecutionStep,
    val status: RssExecutionStatus,
    val detail: String? = null,
    val error: String? = null,
    val duration: Long? = null,
    val time: Long = System.currentTimeMillis(),
    val sourceUrl: String = "",
    val sourceName: String = "",
    val executionId: String = "",
    val isSessionStart: Boolean = false,
    val isSessionEnd: Boolean = false
)

/**
 * 订阅源规则执行记录
 *
 * 记录规则解析的详细信息，包含执行树、输入输出、匹配数量等，
 * 用于调试界面展示规则执行的完整路径。
 *
 * @param step 执行步骤类型
 * @param ruleContent 规则内容（如 @css:.title@text）
 * @param executionTree 规则执行树，包含每一步的详细信息
 * @param input 输入数据（截取前200字符避免日志过大）
 * @param output 输出数据（截取前200字符）
 * @param matchCount 匹配数量（用于选择器类规则）
 * @param duration 执行耗时（毫秒）
 * @param error 错误信息（如果有）
 * @param time 执行时间戳
 * @param sourceUrl 订阅源URL
 * @param sourceName 订阅源名称
 * @param executionId 执行会话ID，用于关联同一次请求的所有记录
 */
data class RssRuleExecutionRecord(
    val step: RssExecutionStep,
    val ruleContent: String? = null,
    val executionTree: RuleExecutionTree? = null,
    val input: String? = null,
    val output: String? = null,
    val matchCount: Int? = null,
    val duration: Long? = null,
    val error: Throwable? = null,
    val time: Long = System.currentTimeMillis(),
    val sourceUrl: String = "",
    val sourceName: String = "",
    val executionId: String = ""
)
