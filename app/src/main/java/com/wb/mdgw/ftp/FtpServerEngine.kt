package com.wb.mdgw.ftp

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简嵌入式 FTP 服务器（零依赖，纯 JDK Socket）。
 *
 * 支持：匿名登录、UTF-8 文件名、PASV/EPSV 被动模式（手机/电脑客户端主流）、
 * LIST/NLST、RETR/STOR、DELE、MKD/RMD、RNFR/RNTO、CWD/CDUP/PWD、SIZE/MDTM、
 * TYPE/SYST/FEAT/REST。所有路径强制锁定在根目录内，防 ../ 穿越。
 *
 * 监听绑定 0.0.0.0；PASV 回显 IP 优先用配置的展示 IP，否则取控制连接实际到达的本机地址。
 */
class FtpServerEngine(
    private val config: FtpSettings.Config
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val sessions = java.util.Collections.synchronizedList(mutableListOf<Session>())

    private val root: File = File(config.rootDir).canonicalFile

    /** 是否已在运行 */
    fun isRunning(): Boolean = running.get()

    /** 绑定端口并开始接受连接（端口占用会抛异常给调用方）。 */
    @Throws(Exception::class)
    fun start() {
        if (running.get()) return
        root.mkdirs()
        // 先绑定（失败直接抛出，running 保持 false），再置位
        val ss = ServerSocket(config.port)
        if (!running.compareAndSet(false, true)) {
            runCatching { ss.close() }
            return
        }
        ss.soTimeout = 0
        serverSocket = ss
        Thread({
            while (running.get()) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (running.get()) continue else break
                }
                val session = Session(client)
                sessions.add(session)
                pool.execute {
                    try {
                        session.run()
                    } catch (_: Exception) {
                    } finally {
                        sessions.remove(session)
                        runCatching { client.close() }
                    }
                }
            }
        }, "ftp-accept").start()
    }

    /** 停止监听并关闭所有会话。 */
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        val snapshot = synchronized(sessions) { sessions.toList() }
        snapshot.forEach { it.closeQuietly() }
        pool.shutdownNow()
    }

    /** 单个客户端会话（控制连接 + 被动数据连接）。 */
    private inner class Session(private val control: Socket) {
        private val reader: BufferedReader =
            BufferedReader(InputStreamReader(control.getInputStream(), Charsets.UTF_8))
        private val controlOut: OutputStream = control.getOutputStream()

        private var authed = false
        private var cwd: File = root
        private var renameFrom: File? = null
        private var restartOffset = 0L
        private var passive: ServerSocket? = null

        fun run() {
            send(220, "陈律工具箱 FTP 服务就绪（匿名）")
            while (running.get()) {
                val line = reader.readLine() ?: break
                val sp = line.indexOf(' ')
                val cmd = (if (sp >= 0) line.substring(0, sp) else line).trim().uppercase(Locale.US)
                val arg = if (sp >= 0) line.substring(sp + 1).trim() else ""
                if (!dispatch(cmd, arg)) break
            }
        }

        fun closeQuietly() {
            runCatching { passive?.close() }
            runCatching { control.close() }
        }

        private fun send(code: Int, msg: String) {
            try {
                controlOut.write("$code $msg\r\n".toByteArray(Charsets.UTF_8))
                controlOut.flush()
            } catch (_: Exception) {
            }
        }

        /** 多行响应（FEAT 等）。 */
        private fun sendMulti(code: Int, lines: List<String>, end: String) {
            try {
                val sb = StringBuilder()
                lines.forEach { sb.append("$code-$it\r\n") }
                sb.append("$code $end\r\n")
                controlOut.write(sb.toString().toByteArray(Charsets.UTF_8))
                controlOut.flush()
            } catch (_: Exception) {
            }
        }

        /** @return false 表示关闭控制连接。 */
        private fun dispatch(cmd: String, arg: String): Boolean {
            // 未登录也允许的命令
            when (cmd) {
                "QUIT" -> { send(221, "Goodbye."); return false }
                "USER" -> {
                    send(331, "Please specify the password.")
                    return true
                }
                "PASS" -> {
                    authed = true
                    send(230, "Login successful.")
                    return true
                }
                "SYST" -> { send(215, "UNIX Type: L8"); return true }
                "FEAT" -> {
                    sendMulti(211, listOf("Features:", " UTF8", " PASV", " EPSV", " SIZE"), "End")
                    return true
                }
                "NOOP" -> { send(200, "NOOP ok."); return true }
                "AUTH", "PBSZ", "PROT" -> { send(200, "ok"); return true }
            }
            if (!authed) {
                send(530, "Please login with USER and PASS.")
                return true
            }
            when (cmd) {
                "PWD", "XPWD" -> {
                    send(257, "\"${logicalPath(cwd)}\" is current directory.")
                }
                "CWD", "XCWD" -> {
                    val f = if (arg.isBlank()) root else resolve(arg)
                    if (isInside(f) && f.isDirectory) {
                        cwd = f
                        send(250, "Directory successfully changed.")
                    } else {
                        send(550, "Failed to change directory.")
                    }
                }
                "CDUP", "XCUP" -> {
                    val up = cwd.parentFile
                    if (up != null && isInside(up)) cwd = up else cwd = root
                    send(250, "Directory successfully changed.")
                }
                "TYPE" -> send(200, "Type set.")
                "PASV" -> {
                    val p = openPassive()
                    if (p == null) {
                        send(425, "Can't open passive connection.")
                    } else {
                        val port = p.localPort
                        val ip = pasvIp()
                        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
                        if (parts.size != 4) {
                            send(425, "Can't determine passive IP.")
                        } else {
                            send(227, "Entering Passive Mode (${parts[0]},${parts[1]},${parts[2]},${parts[3]},${port / 256},${port % 256}).")
                        }
                    }
                }
                "EPSV" -> {
                    val p = openPassive()
                    if (p == null) send(425, "Can't open passive connection.")
                    else send(229, "Entering Extended Passive Mode (|||${p.localPort}|).")
                }
                "LIST", "NLST" -> doList(cmd, arg)
                "RETR" -> doRetr(arg)
                "STOR" -> doStor(arg)
                "DELE" -> {
                    val f = resolve(arg)
                    if (isInside(f) && f.isFile && f.delete()) send(250, "Deleted.")
                    else send(550, "Delete failed.")
                }
                "MKD", "XMKD" -> {
                    val f = resolve(arg)
                    if (isInside(f) && (f.exists() || f.mkdirs())) send(257, "\"${logicalPath(f)}\" created.")
                    else send(550, "Create directory failed.")
                }
                "RMD", "XRMD" -> {
                    val f = resolve(arg)
                    if (isInside(f) && f.isDirectory && f.delete()) send(250, "Removed.")
                    else send(550, "Remove directory failed.")
                }
                "RNFR" -> {
                    val f = resolve(arg)
                    if (isInside(f) && f.exists()) {
                        renameFrom = f
                        send(350, "Ready for RNTO.")
                    } else send(550, "File not found.")
                }
                "RNTO" -> {
                    val from = renameFrom
                    val to = resolve(arg)
                    if (from != null && isInside(to) && from.renameTo(to)) {
                        renameFrom = null
                        send(250, "Rename successful.")
                    } else {
                        renameFrom = null
                        send(550, "Rename failed.")
                    }
                }
                "SIZE" -> {
                    val f = resolve(arg)
                    if (isInside(f) && f.isFile) send(213, f.length().toString())
                    else send(550, "File not found.")
                }
                "MDTM" -> {
                    val f = resolve(arg)
                    if (isInside(f) && f.exists()) {
                        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                        send(213, sdf.format(Date(f.lastModified())))
                    } else send(550, "File not found.")
                }
                "REST" -> {
                    restartOffset = arg.trim().toLongOrNull() ?: 0L
                    send(350, "Restarting at $restartOffset.")
                }
                "ALLO", "OPTS" -> send(200, "ok")
                "PORT", "EPRT", "LPRT" -> send(502, "Active mode not supported; use PASV.")
                else -> send(502, "Command not implemented.")
            }
            return true
        }

        // ---------- 路径处理 ----------
        private fun resolve(arg: String): File {
            var p = arg.trim()
            if (p.startsWith('"') && p.endsWith('"') && p.length >= 2) p = p.substring(1, p.length - 1)
            val base = if (p.startsWith("/")) root else cwd
            return File(base, p).canonicalFile
        }

        private fun isInside(f: File): Boolean {
            val rp = root.path
            val fp = f.path
            return fp == rp || fp.startsWith(rp + File.separator)
        }

        private fun logicalPath(f: File): String {
            val rp = root.path
            val fp = f.path
            if (fp == rp) return "/"
            return if (fp.startsWith(rp)) fp.substring(rp.length) else "/"
        }

        // ---------- 被动数据连接 ----------
        private fun openPassive(): ServerSocket? {
            runCatching { passive?.close() }
            return runCatching {
                ServerSocket(0, 8, InetAddress.getByName("0.0.0.0")).apply {
                    soTimeout = 30000
                    passive = this
                }
            }.getOrNull()
        }

        private fun acceptData(): Socket? {
            val p = passive ?: return null
            return runCatching { p.accept() }.also {
                runCatching { passive?.close() }
                passive = null
            }.getOrNull()
        }

        private fun pasvIp(): String {
            if (config.ip.isNotBlank()) return config.ip.trim()
            return runCatching {
                val a = control.localAddress.hostAddress ?: ""
                a.substringBefore('%')
            }.getOrDefault("127.0.0.1")
        }

        // ---------- 数据传输命令 ----------
        private fun doList(cmd: String, arg: String) {
            val target = if (arg.isBlank()) cwd else resolve(arg)
            if (!isInside(target) || !target.exists()) {
                send(550, "Not found.")
                return
            }
            send(150, "Opening data connection.")
            val data = acceptData()
            if (data == null) {
                send(425, "Can't open data connection.")
                return
            }
            try {
                data.use { sock ->
                    val out = sock.getOutputStream()
                    val files = if (target.isDirectory) target.listFiles() ?: emptyArray() else arrayOf(target)
                    val sb = StringBuilder()
                    for (f in files) {
                        if (cmd == "NLST") {
                            sb.append(f.name).append("\r\n")
                        } else {
                            sb.append(formatListEntry(f))
                        }
                    }
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                send(226, "Transfer complete.")
            } catch (e: Exception) {
                send(550, "List failed.")
            }
        }

        private fun formatListEntry(f: File): String {
            val type = if (f.isDirectory) "drwxrwxrwx" else "-rw-rw-rw-"
            val size = if (f.isDirectory) 0 else f.length()
            val date = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
            return "$type 1 owner group $size $date ${f.name}\r\n"
        }

        private fun doRetr(arg: String) {
            val f = resolve(arg)
            if (!isInside(f) || !f.isFile) {
                send(550, "File not found.")
                return
            }
            send(150, "Opening data connection.")
            val data = acceptData()
            if (data == null) {
                send(425, "Can't open data connection.")
                return
            }
            try {
                data.use { sock ->
                    f.inputStream().use { input ->
                        val out = sock.getOutputStream()
                        val buf = ByteArray(8192)
                        var skipped = 0L
                        while (skipped < restartOffset) {
                            val s = input.skip(restartOffset - skipped)
                            if (s <= 0) break
                            skipped += s
                        }
                        restartOffset = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                        out.flush()
                    }
                }
                send(226, "Transfer complete.")
            } catch (e: Exception) {
                send(550, "Retrieve failed.")
            }
        }

        private fun doStor(arg: String) {
            val f = resolve(arg)
            if (!isInside(f)) {
                send(550, "Invalid path.")
                return
            }
            runCatching { f.parentFile?.mkdirs() }
            send(150, "Opening data connection.")
            val data = acceptData()
            if (data == null) {
                send(425, "Can't open data connection.")
                return
            }
            try {
                data.use { sock ->
                    f.outputStream().use { target ->
                        val input = sock.getInputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            target.write(buf, 0, n)
                        }
                        target.flush()
                    }
                }
                send(226, "Transfer complete.")
            } catch (e: Exception) {
                runCatching { f.delete() }
                send(550, "Store failed.")
            }
        }
    }
}
