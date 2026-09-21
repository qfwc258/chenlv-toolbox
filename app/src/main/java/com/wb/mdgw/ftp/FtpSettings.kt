package com.wb.mdgw.ftp

import android.content.Context
import android.os.Environment
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.net.NetworkInterface
import java.util.Collections

/**
 * FTP 服务的可配置项（本机 IP / 端口 / 根目录），落盘到应用私有目录。
 *
 * - [ip] 为空时自动检测首个可用 IPv4；监听始终绑定 0.0.0.0，该 IP 仅用于界面展示与 PASV 回显。
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
        val ip: String = "",
        val port: Int = 5656,
        val rootDir: String = DEFAULT_ROOT
    )

    fun load(context: Context): Config = read(context) ?: Config()

    fun save(context: Context, config: Config) = write(context, config)

    /** 生效的展示 IP：用户手填优先，否则自动检测；都没有时回退 127.0.0.1。 */
    fun effectiveIp(context: Context): String {
        val c = load(context)
        if (c.ip.isNotBlank()) return c.ip.trim()
        return enumerateIpv4().firstOrNull() ?: "127.0.0.1"
    }

    /** 枚举本机所有可用、非回环的 IPv4 地址（WiFi / 热点等），供下拉选择。 */
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
