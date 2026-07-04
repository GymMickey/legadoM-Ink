package io.legado.app.help.update

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import io.legado.app.exception.NoStackTraceException
import java.time.Instant

data class AppReleaseInfo(
    val appVariant: AppVariant,
    val createdAt: Long,
    val note: String,
    val name: String,
    val downloadUrl: String,
    val assetUrl: String
) {
    val versionName: String by lazy {
        extractVersionName(name)
    }

    fun isNewerThan(currentVersionName: String): Boolean {
        return compareVersionName(versionName, currentVersionName) > 0
    }
}

enum class AppVariant {
    OFFICIAL,
    BETA_LEGACY,    // 兼容版 - io.legado.app - 可覆盖原版
    BETA_COEXIST,   // 共存版 - io.legado.app.yuedu.a - 测试版的共存包
    BETA_RELEASE,   // 测试版 - io.legado.app.yuedu - 原包名测试版
    UNKNOWN;

    fun isBeta(): Boolean {
        return this == BETA_RELEASE || this == BETA_LEGACY || this == BETA_COEXIST
    }

}

fun appVariantFromAssetName(name: String, preRelease: Boolean = false): AppVariant {
    return when {
        name.contains("releaseS", ignoreCase = true) || name.contains("正式版") -> AppVariant.BETA_COEXIST
        name.contains("legacy", ignoreCase = true) || name.contains("兼容版") -> AppVariant.BETA_LEGACY
        name.contains("release", ignoreCase = true) || name.contains("测试版") -> AppVariant.BETA_RELEASE
        preRelease -> AppVariant.BETA_RELEASE
        else -> AppVariant.OFFICIAL
    }
}

private fun extractVersionName(name: String): String {
    return Regex("""\d+(?:\.\d+)+""").find(name)?.value.orEmpty()
}

private fun compareVersionName(remoteVersionName: String, currentVersionName: String): Int {
    val remoteVersion = extractVersionName(remoteVersionName)
    val currentVersion = extractVersionName(currentVersionName)
    if (remoteVersion.isBlank() || currentVersion.isBlank()) {
        return remoteVersion.compareTo(currentVersion)
    }
    if (remoteVersion == currentVersion) {
        return 0
    }
    val remoteParts = remoteVersion.split(".")
    val currentParts = currentVersion.split(".")
    val maxSize = maxOf(remoteParts.size, currentParts.size)
    for (index in 0 until maxSize) {
        val remotePart = remoteParts.getOrElse(index) { "0" }
        val currentPart = currentParts.getOrElse(index) { "0" }
        val compare = comparePart(remotePart, currentPart)
        if (compare != 0) {
            return compare
        }
    }
    return 0
}

private fun comparePart(remote: String, current: String): Int {
    val remoteNum = remote.toLongOrNull()
    val currentNum = current.toLongOrNull()
    if (remoteNum != null && currentNum != null) {
        val maxLen = maxOf(remote.length, current.length)
        val isDateTimePart = maxLen > 5
        val remotePadded = if (isDateTimePart) remote.padEnd(maxLen, '0') else remote.padStart(maxLen, '0')
        val currentPadded = if (isDateTimePart) current.padEnd(maxLen, '0') else current.padStart(maxLen, '0')
        return remotePadded.compareTo(currentPadded)
    }
    return remote.compareTo(current)
}

@Keep
data class GithubRelease(
    val assets: List<Asset>?,
    val body: String,
    @SerializedName("prerelease")
    val isPreRelease: Boolean,
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(isPreRelease, body) }
    }
}
@Keep
data class GiteeRelease(
    val assets: List<GiteeAsset>?,
    val body: String,
    @SerializedName("prerelease")
    val prerelease: Boolean,
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(prerelease, body) }
    }
}

@Keep
data class Asset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("content_type")
    val contentType: String,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("download_count")
    val downloadCount: Int,
    val id: Int,
    val name: String,
    val state: String,
    val url: String
) {
    val isValid: Boolean
        get() = (contentType == "application/vnd.android.package-archive") && (state == "uploaded")

    fun assetToAppReleaseInfo(preRelease: Boolean, note: String): AppReleaseInfo {
        val instant = Instant.parse(createdAt)
        val timestamp: Long = instant.toEpochMilli()

        val appVariant = appVariantFromAssetName(name, preRelease)

        return AppReleaseInfo(appVariant, timestamp, note, name, apkUrl, url)
    }
}

@Keep
data class GiteeAsset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("name")
    val name: String
) {
    val isValid: Boolean
        get() = apkUrl.contains(".apk")

    fun assetToAppReleaseInfo(preRelease: Boolean, note: String): AppReleaseInfo {
        val appVariant = appVariantFromAssetName(name, preRelease)

        return AppReleaseInfo(appVariant, 0, note, name, apkUrl, "")
    }
}
