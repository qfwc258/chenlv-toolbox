package com.wb.mdgw.elementdoc

/**
 * 要素式文书「载入虚构案例体验」引擎（移植参照仓库 handleFillFictionalSample，Apache-2.0）。
 *
 * 用一套虚构当事人（杭州云栖示例商贸 诉 宁波海潮示例实业）按官方 roleTag 逐行填入，
 * 让新用户一键看到完整填好的效果；所有演示内容带【虚构演示样例】标记，
 * 导出文件名自动加「虚构样例_不可提交」，避免误用于真实立案。
 * 纯 JVM 实现，便于单元测试。
 */
object DemoSampleEngine {

    const val DEMO_CASE_NAME = "虚构样例（杭州云栖诉宁波海潮）"
    const val DEMO_COURT = "杭州市西湖区人民法院"

    private const val PLAINTIFF = "杭州云栖示例商贸有限公司"
    private const val DEFENDANT = "宁波海潮示例实业有限公司"

    /** 生成一份虚构案例草稿（不含 caseName 之外的签名/日期，由界面填） */
    fun build(tpl: SpcTemplate): ElementDraft {
        val rowValues = mutableMapOf<String, String>()
        var sampleTextIdx = 0

        for (r in tpl.rows) {
            when (r.roleTag) {
                "plaintiff_org" -> {
                    val q = PartyFillEngine.Data(
                        name = PLAINTIFF,
                        creditCode = "91330106MA2DEMO01X",
                        legalRep = "林示例",
                        orgType = "有限责任公司",
                        ownershipNature = "民营",
                        domicile = "杭州市西湖区文三路100号示例大厦8层",
                        regAddress = "杭州市西湖区文三路100号示例大厦8层"
                    )
                    rowValues[r.id] = PartyFillEngine.applyToCellText(r.templateText, r.roleTag, q)
                }
                "defendant_org" -> {
                    val q = PartyFillEngine.Data(
                        name = DEFENDANT,
                        creditCode = "91330200MA2DEMO02Y",
                        legalRep = "陈示例",
                        orgType = "有限责任公司",
                        ownershipNature = "民营",
                        domicile = "宁波市鄞州区天童南路200号示例中心12层",
                        regAddress = "宁波市鄞州区天童南路200号示例中心12层"
                    )
                    rowValues[r.id] = PartyFillEngine.applyToCellText(r.templateText, r.roleTag, q)
                }
                "plaintiff_person", "defendant_person", "third_person" -> {
                    // 自然人主体：填一个示例姓名，避免示例里主体全空
                    val label = if (r.roleTag == "plaintiff_person") "王示例" else if (r.roleTag == "defendant_person") "李示例" else "赵示例"
                    val q = PartyFillEngine.Data(name = label, gender = "男")
                    rowValues[r.id] = PartyFillEngine.applyToCellText(r.templateText, r.roleTag, q)
                }
                "mediation_benefit" -> {
                    rowValues[r.id] = ElementEngine.setAllMediationBenefitChoices(r.templateText, true)
                }
                "mediation_know" -> {
                    // 了解□不了解□：勾选"了解"
                    rowValues[r.id] = runCatching { ElementEngine.toggleCheckboxAtIndex(r.templateText, 0) }
                        .getOrDefault(r.templateText)
                }
                "mediation_agree" -> {
                    rowValues[r.id] = runCatching { ElementEngine.toggleCheckboxAtIndex(r.templateText, 0) }
                        .getOrDefault(r.templateText)
                }
                else -> {
                    if ((r.kind == "field" || r.kind == "full_input") && r.roleTag.isEmpty() && sampleTextIdx < 3) {
                        val base = r.templateText
                        val tokens = ElementEngine.parseCheckboxTokens(base)
                        val checkedBase = if (tokens.isNotEmpty()) ElementEngine.toggleCheckboxAtIndex(base, 0) else base
                        val note = when (sampleTextIdx) {
                            0 -> "【虚构演示样例】判令被告支付尚欠款项人民币 380,000 元及逾期付款损失。"
                            1 -> "【虚构演示样例】判令本案诉讼费用由被告承担。"
                            else -> "【虚构演示样例】2026年3月1日双方签订书面合同，原告已按约履行全部义务并经签收确认。"
                        }
                        rowValues[r.id] = "$checkedBase\n$note"
                        sampleTextIdx++
                    }
                }
            }
        }

        return ElementDraft(
            templateId = tpl.id,
            caseName = DEMO_CASE_NAME,
            rowValues = rowValues,
            signerName = "$PLAINTIFF（虚构样例）",
            signDate = "2026 年 3 月 1 日",
            includeAttachments = true
        )
    }

    /** 虚构样例导出的文件名标记，防止误用 */
    const val DEMO_FILE_TAG = "虚构样例_不可提交"
}
