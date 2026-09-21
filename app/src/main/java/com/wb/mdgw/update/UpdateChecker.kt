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
 * 零新依赖：HttpURLConnection + kotlinx.serialization，匿名访问公开仓库的
 * `/releases/latest`（单用户检查频率远低于 60 次/小时的匿名限额）。
 */
object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/qfwc258/chenlv-toolbox/releases/latest"

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

    /** 查询最新正式 Release；网络失败 / 草稿 / 预发布 / 无 APK 资产时返回 null */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "chenlv-toolbox-update")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val rel = json.decodeFromString(GithubRelease.serializer(), text)
                if (rel.draft || rel.prerelease) return@runCatching null
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
