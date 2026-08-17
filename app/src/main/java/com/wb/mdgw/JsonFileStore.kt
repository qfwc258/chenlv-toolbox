package com.wb.mdgw

import android.content.Context
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * 通用「应用私有 JSON 文件」持久化基类。
 *
 * 收敛各草稿/样式 Store 的重复样板：JSON 编解码 + openFileOutput 写入 + 文件读取 +
 * 存在性判断 + 删除。读写全部失败静默（`runCatching`），绝不影响正常编辑。
 *
 * 子类只需提供 [fileName] 与 [serializer]，再按需用 [write]/[read] 封装各自的公开 API。
 */
abstract class JsonFileStore<T : Any> {

    /** 应用私有目录内的文件名（如 `"govdoc_draft.json"`） */
    protected abstract val fileName: String

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** 序列化器，由子类通过 `Foo.serializer()` 提供 */
    protected abstract fun serializer(): KSerializer<T>

    /** 覆盖式写入（失败静默忽略） */
    protected fun write(context: Context, value: T) {
        runCatching {
            val txt = json.encodeToString(serializer(), value)
            context.openFileOutput(fileName, Context.MODE_PRIVATE).use { it.write(txt.toByteArray()) }
        }
    }

    /** 读取；文件不存在 / 为空 / 解析失败返回 null */
    protected fun read(context: Context): T? {
        val file = File(context.filesDir, fileName)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrNull()
    }

    /** 是否存在非空文件（用于是否弹「恢复」提示） */
    fun has(context: Context): Boolean {
        val f = File(context.filesDir, fileName)
        return f.exists() && f.length() > 0
    }

    /** 删除文件（保存成功 / 恢复默认时调用，失败静默忽略） */
    fun clear(context: Context) {
        runCatching { context.deleteFile(fileName) }
    }
}