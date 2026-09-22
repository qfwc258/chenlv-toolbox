package com.wb.mdgw.update

import com.wb.mdgw.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 最新版本查询与版本比较。
 *
 * 零新依赖：HttpURLConnection + kotlinx.serialization，匿名访问公开的 APK 分发仓库
 * [RELEASES_REPO]。该仓库同时承载多个 App 的 Release，因此本 App 的 Release tag 统一带
 * [TAG_PREFIX] 前缀（形如 `chenlv-toolbox-v3.1.0`），[fetchLatest] 拉取最近 Release
 * 列表后，只取第一个带本 App 前缀的正式版，避免被其他 App 的发布顶掉。
 *
 * 本项目为单一通用 APK（不按 ABI 拆分），直接取该 Release 下的 APK 资产。
 */
object UpdateChecker {

    /** 公开的 APK 分发仓库（发布产物统一放到这里） */
    private const val RELEASES_REPO = "qfwc258/xccapk"

    /** 本 App 在分发仓库中的 Release tag 前缀 */
    private const val TAG_PREFIX = "chenlv-toolbox-v"

    /** 拉取最近若干条 Release（列表按创建时间倒序），从中筛出本 App 的最新版 */
    private const val RELEASES_URL =
        "https://api.github.com/repos/$RELEASES_REPO/releases?per_page=50"

    private val json = Json { ignoreUnknownKeys = true }
    private val VERSION_REGEX = Regex("(\\d+)\\.(\\d+)\\.(\\d+)")

    @Serializable
    private data class GithubAsset(
        val name: String? = null,
        val size: Long = 0L,
        val browser_download_url: String? = null,
    )

    @Serializable
    private data class GithubRelease(
        val tag_name: String? = null,
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GithubAsset> = emptyList(),
    )

    /** 供 UI 使用的发布信息 */
    data class ReleaseInfo(
        val versionName: String,
        val tagName: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
    )

    /** 查询本 App 最新正式 Release；网络失败 / 无匹配 / 无 APK 资产时返回 null */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "chenlv-toolbox-update")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val releases = json.decodeFromString<List<GithubRelease>>(text)
                // 列表按创建时间倒序，取第一个带本 App 前缀的正式版（跳过草稿/预发布）
                val rel = releases
                    .filter { !it.draft && !it.prerelease }
                    .firstOrNull { it.tag_name?.startsWith(TAG_PREFIX) == true }
                    ?: return@runCatching null
                // 单一通用 APK，直接取第一个 APK 资产
                val apk = rel.assets.firstOrNull { it.name?.endsWith(".apk", true) == true }
                    ?: return@runCatching null
                val url = apk.browser_download_url?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                val ver = parseVersion(rel.tag_name)?.let { "${it[0]}.${it[1]}.${it[2]}" }
                    ?: return@runCatching null
                ReleaseInfo(
                    versionName = ver,
                    tagName = rel.tag_name.orEmpty(),
                    title = rel.name?.takeIf { it.isNotBlank() } ?: "新版本 v$ver",
                    notes = rel.body.orEmpty(),
                    apkUrl = url,
                    apkName = apk.name ?: "chenlv-toolbox-update.apk",
                    apkSize = apk.size,
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** 从 tag / versionName 中解析三段数字；失败返回 null */
    fun parseVersion(text: String?): IntArray? {
        val m = VERSION_REGEX.find(text ?: return null) ?: return null
        return intArrayOf(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
        )
    }

    /** [latestVersion] 是否比当前安装版本（默认读 BuildConfig）更新 */
    fun isNewer(
        latestVersion: String,
        currentVersion: String = BuildConfig.VERSION_NAME,
    ): Boolean {
        val l = parseVersion(latestVersion) ?: return false
        val c = parseVersion(currentVersion) ?: return false
        for (i in 0..2) {
            if (l[i] != c[i]) return l[i] > c[i]
        }
        return false
    }

    /** 把 Release body 的 Markdown 轻量清理成纯文本，便于在对话框中展示 */
    fun cleanNotes(body: String): String {
        if (body.isBlank()) return "暂无更新说明"
        return body.lineSequence()
            .map { it.trim() }
            .map { it.trimStart('#').trim() }       // 标题符号（## 也一并去掉）
            .map { it.replace("`", "").replace("**", "") } // 代码/加粗标记
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
            .ifBlank { "暂无更新说明" }
    }

    /** 字节数转可读大小 */
    fun humanSize(bytes: Long): String =
        if (bytes <= 0L) "" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
