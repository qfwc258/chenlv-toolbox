package com.wb.mdgw

/**
 * 律师个人执业档案（对齐参照仓库 qwertyzhu/cn-lawyer-ai-toolbox 的 LawyerProfile）。
 *
 * 纯 JVM data class，由 [SettingsStore] 以 SharedPreferences 持久化。
 * 用途：要素式文书「委托诉讼代理人」行一键带入姓名/律所/执业证号/电话；
 * 也可供文书落款与其它工具复用。
 */
data class LawyerProfile(
    val name: String = "",
    val licenseNo: String = "",
    val firmName: String = "",
    val phone: String = "",
    val address: String = "",
    val email: String = ""
) {
    /** 姓名与律所都为空时视为未建档，一键带入会提示先去设置页填写 */
    val isEmpty: Boolean get() = name.isBlank() && firmName.isBlank()

    /** 用于 toast / 预览的简短称呼 */
    val displayName: String get() = name.ifBlank { firmName }
}
