package com.wb.mdgw.docgen

/**
 * shared_text.txt 解析 / 导出（与 PC 端 Python 脚本规则一致并修正其丢内容缺陷）。
 *
 * 规则：
 *  - 行形如 `--标题--`  → 分组（仅用于表单分组展示，不参与替换）
 *  - 行以 `#` 开头      → 注释（小节标题，不参与替换）
 *  - 行形如 `key::value`→ 字段，key 为字母/数字（可含尾数字，如 gcsj1）
 *  - 空行              → 空行（仅用于排版）
 *  - 其余非空行         → 视为上一字段的「续行」（多行值），用换行并入。
 *
 * 与 PC 脚本的差异（修正）：PC 逐行读取，没有 `::` 的普通行会被直接丢弃，
 * 导致 `ajqk`（案件情况）跨两段时第二段丢失。这里将其并入上一字段；导出时
 * 字段内换行转义为 `\n`，保证一行一个字段，PC 端再读反而能拿到完整内容。
 *
 * 转义：`\r \n \t \\` 四个序列（解析时逐字符扫描，避免二次替换）。
 */
object SharedTextParser {

    private val GROUP_REGEX = Regex("""^--(.+)--\s*$""")
    private val FIELD_REGEX = Regex("""^([A-Za-z][A-Za-z0-9]*)\s*::(.*)$""")

    /** 解析整份 shared_text.txt 为结构化行 */
    fun parse(text: String): List<RuleLine> {
        val lines = mutableListOf<RuleLine>()
        // 统一换行符，兼容 CRLF / CR
        val raw = text.replace("\r\n", "\n").replace('\r', '\n')

        for (rawLine in raw.split('\n')) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> {
                    // 折叠连续空行，最多保留一个，避免表单里出现大片空白
                    if (lines.isNotEmpty() && lines.last().type != RuleLine.TYPE_BLANK) {
                        lines += RuleLine.blank()
                    }
                }
                GROUP_REGEX.matches(line) -> {
                    val title = GROUP_REGEX.matchEntire(line)!!.groupValues[1].trim()
                    lines += RuleLine.group(title)
                }
                line.startsWith("#") -> {
                    lines += RuleLine.comment(line.substring(1).trim())
                }
                FIELD_REGEX.matches(line) -> {
                    val m = FIELD_REGEX.matchEntire(line)!!
                    val key = m.groupValues[1]
                    val value = unescape(m.groupValues[2].trim())
                    lines += RuleLine.field(key, value)
                }
                else -> {
                    // 续行：并入上一个字段（多行值）
                    val lastFieldIdx = lines.indexOfLast { it.isField }
                    if (lastFieldIdx >= 0) {
                        val f = lines[lastFieldIdx]
                        f.value = if (f.value.isEmpty()) line else f.value + "\n" + line
                    }
                    // 没有上一字段的孤立普通行：忽略（PC 同样不会使用）
                }
            }
        }
        // 去掉末尾多余空行
        while (lines.isNotEmpty() && lines.last().type == RuleLine.TYPE_BLANK) lines.removeAt(lines.lastIndex)
        return lines
    }

    /** 导出为 PC 端兼容的 shared_text.txt 文本 */
    fun export(doc: FieldDoc): String = export(doc.lines)

    fun export(lines: List<RuleLine>): String {
        val sb = StringBuilder()
        for (line in lines) {
            when (line.type) {
                RuleLine.TYPE_GROUP -> sb.append("--").append(line.text).append("--")
                RuleLine.TYPE_COMMENT -> sb.append('#').append(line.text)
                RuleLine.TYPE_FIELD -> sb.append(line.key).append("::").append(escape(line.value))
                else -> { /* 空行 */ }
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** 解析转义：逐字符扫描，\r/\n/\t/\\ 各还原为对应字符 */
    fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'r' -> { sb.append('\r'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** 转义：反斜杠先行，再转写回车/换行/制表符，保证一字段占一行 */
    fun escape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
}
