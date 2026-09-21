package com.wb.mdgw.ftp

import android.content.Context
import android.os.Environment
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.net.NetworkInterface
import java.util.Collections

/**
 * FTP 服务的可配置项（端口 / 根目录），落盘到应用私有目录。
 *
 * - 本机 IP 不允许在 App 内修改（Android 普通应用无此权限），仅自动检测后只读展示；
 *   监听始终绑定 0.0.0.0，PASV 回显使用控制连接实际到达的本机地址。
 * - 匿名、全权限（列/读/写/删/建目录/改名），与参考的 pyftpdlib 行为一致。
 */
object FtpSettings : JsonFileStore<FtpSettings.Config>() {

    override val fileName: String = "ftp_settings.json"
    override fun serializer(): KSerializer<Config> = Config.serializer()

    /** 默认根目录：/sdcard/scan（内部存储下的 scan 目录） */
    val DEFAULT_ROOT: String =
        java.io.File(Environment.getExternalStorageDirectory(), "scan").absolutePath

    @Serializable
    data class Config(
        val port: Int = 5656,
        val rootDir: String = DEFAULT_ROOT
    )

    fun load(context: Context): Config = read(context) ?: Config()

    fun save(context: Context, config: Config) = write(context, config)

    /** 首个可用 IPv4，用于界面展示；没有时回退 127.0.0.1。 */
    fun firstIpv4(): String = enumerateIpv4().firstOrNull() ?: "127.0.0.1"

    /** 枚举本机所有可用、非回环的 IPv4 地址（WiFi / 热点等），只读展示。 */
    fun enumerateIpv4(): List<String> {
        val out = linkedSetOf<String>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (nif in Collections.list(interfaces)) {
                if (nif.isLoopback || !nif.isUp) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    val host = addr.hostAddress ?: continue
                    // 排除 IPv6（含 ':'）与回环
                    if (host.contains(':')) continue
                    if (addr.isLoopbackAddress) continue
                    out.add(host.substringBefore('%'))
                }
            }
        }
        return out.toList()
    }
}
