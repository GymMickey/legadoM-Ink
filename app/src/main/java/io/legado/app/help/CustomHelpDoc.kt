package io.legado.app.help

/**
 * 自定义帮助文档分组
 *
 * @param displayName 分组显示名(文件夹名)
 * @param docs 文档列表
 * @param folderPath 文件夹绝对路径
 */
data class CustomHelpDocGroup(
    val displayName: String,
    val docs: List<CustomHelpDoc>,
    val folderPath: String
)

/**
 * 自定义帮助文档
 *
 * @param fileName 文件名(不含扩展名)
 * @param displayName 显示名(文件名)
 * @param filePath 文件绝对路径
 * @param extension 文件扩展名("md" 或 "txt")
 */
data class CustomHelpDoc(
    val fileName: String,
    val displayName: String,
    val filePath: String,
    val extension: String
) {
    /**
     * 获取完整文件名(含扩展名)
     */
    fun getFullFileName(): String = "$fileName.$extension"
}
