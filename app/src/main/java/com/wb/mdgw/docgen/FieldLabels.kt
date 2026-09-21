package com.wb.mdgw.docgen

/**
 * 拼音 key → 中文标签（法律援助文书模板）。
 *
 * 表单里每个字段显示「中文标签 + 灰色小字 key」，提升可读性；
 * 未命中标签表的 key 仍原样显示，不影响通用模板使用。
 * 标签表独立成文件，后续可随时增改。
 */
object FieldLabels {

    /** 基础标签（不带序号的 key） */
    private val BASE: Map<String, String> = mapOf(
        // 委托授权
        "leib" to "类别",
        "anyou" to "案由",
        "weitr" to "委托人(受援人)",
        "bianh" to "法援编号",
        "badw" to "办案单位",
        "jied" to "阶段",
        "zprq" to "指派日期",
        // 委托人
        "wtrsfz" to "委托人身份证",
        "wtrdh" to "委托人电话",
        "wtrxb" to "委托人性别",
        "wtrcs" to "委托人出生日期",
        "wtrzz" to "委托人住址",
        // 法定代理人
        "dailr" to "法定代理人",
        "wdgx" to "与当事人关系",
        "dlrdh" to "代理人电话",
        "dlrsfz" to "代理人身份证",
        // 授权
        "wtxm" to "委托项目",
        "quanx" to "授权权限",
        "lsf" to "律师费",
        // 调证
        "cbdw" to "查证单位",
        "bdqxx" to "被调取信息",
        // 接待谈话
        "thsj" to "谈话时间",
        "thdd" to "谈话地点",
        "ajqk" to "案件情况",
        "ssqq" to "诉讼请求",
        "basl" to "办案思路",
        // 办案过程（带序号）
        "gcsj" to "过程时间",
        "gcfs" to "过程方式",
        "gcnr" to "过程内容",
        // 阅卷
        "yjrq" to "阅卷日期",
        "yjnr" to "阅卷内容",
        // 庭前准备 / 提纲
        "bgksl" to "不公开审理",
        "zztg" to "质证提纲",
        "jztg" to "举证提纲",
        "dltg" to "代理提纲",
        // 开庭通知
        "dzsj" to "通知时间",
        "ktsj" to "开庭时间",
        "ktdd" to "开庭地点",
        // 结案信息
        "anhao" to "案号",
        "jarq" to "结案日期",
        "gdrq" to "归档日期",
        "dfdsr" to "对方当事人",
        "cbxj" to "承办小结",
        "ljsm" to "阅卷说明",
        "bljg" to "办理结果"
    )

    /** 内容型字段（值可能是大段文字），表单用多行输入框 */
    private val LONG_BASE: Set<String> = setOf(
        "quanx", "lsf", "ajqk", "ssqq", "basl", "gcnr",
        "yjnr", "dltg", "cbxj", "ljsm", "bljg"
    )

    /** 取 key 去掉末尾数字后的基础部分（gcsj1 → gcsj） */
    private fun baseOf(key: String): String {
        var end = key.length
        while (end > 0 && key[end - 1].isDigit()) end--
        return key.substring(0, end)
    }

    /** 中文标签；未配置返回 null（UI 回退显示原 key） */
    fun labelOf(key: String): String? {
        BASE[key]?.let { return it }
        val base = baseOf(key)
        if (base == key) return null
        val baseLabel = BASE[base] ?: return null
        val suffix = key.substring(base.length)
        return "$baseLabel$suffix"
    }

    /** 是否为长文本字段（多行输入框） */
    fun isLong(key: String): Boolean {
        if (key in LONG_BASE) return true
        return baseOf(key) in LONG_BASE
    }
}
