package com.wb.mdgw.elementdoc

/**
 * 要素式文书「快速结构化填入」引擎（Kotlin 复刻参照仓库 spcOfficialTemplates.ts 的
 * applyPartyQuickFillToCellText / createEmptyPartyQuickFill，Apache-2.0）。
 *
 * 精准填入官方原表单元格，保留所有官方标签、字段顺序与 □/☑ 勾选结构，
 * 仅替换"姓名：""单位："等冒号后的占位文本与切换对应勾选项。
 * 纯 JVM 实现，无 Android 依赖，便于单元测试。
 */
object PartyFillEngine {

    /** 主体快速填入的结构化字段（对齐参照仓库 PartyQuickFillData） */
    data class Data(
        val name: String = "",
        val gender: String = "",
        val birthDate: String = "",
        val ethnicity: String = "汉族",
        val workUnit: String = "",
        val jobTitle: String = "",
        val phone: String = "",
        val domicile: String = "",
        val residence: String = "",
        val idType: String = "居民身份证",
        val idNumber: String = "",
        val regAddress: String = "",
        val legalRep: String = "",
        val repTitle: String = "法定代表人",
        val creditCode: String = "",
        val orgType: String = "有限责任公司",
        val ownershipNature: String = "民营",
        val stateControl: String = "",
        val bankAccount: String = "",
        val bankHolder: String = "",
        val bankName: String = "",
        val agentHas: Boolean = true,
        val agentAuthority: String = "特别授权"
    )

    /** 15 种官方组织类型（用于组织类型下拉与勾选还原） */
    val ORG_TYPES = listOf(
        "有限责任公司", "股份有限公司", "上市公司", "其他企业法人", "事业单位",
        "社会团体", "基金会", "社会服务机构", "机关法人", "农村集体经济组织法人",
        "城镇农村的合作经济组织法人", "基层群众性自治组织法人", "个人独资企业",
        "合伙企业", "不具有法人资格的专业服务机构"
    )

    /**
     * 用执业档案填入「委托诉讼代理人(agent)」行。
     * 姓名=律师姓名，单位=律所，职务=专职律师（执业证号：xxx），电话=联系电话，特别授权☑。
     */
    fun fromLawyerProfile(
        templateText: String,
        name: String,
        firmName: String,
        licenseNo: String,
        phone: String
    ): String {
        val data = Data(
            name = name,
            workUnit = firmName,
            jobTitle = if (licenseNo.isNotBlank()) "专职律师（执业证号：${licenseNo.trim()}）" else "专职律师",
            phone = phone,
            agentHas = true,
            agentAuthority = "特别授权"
        )
        return applyToCellText(templateText, "agent", data)
    }

    /**
     * 把结构化数据填入官方原表文本，保留官方标签与勾选顺序。
     * @param roleTag 行的官方 roleTag（agent / plaintiff_person / plaintiff_org 等）
     */
    fun applyToCellText(templateText: String, roleTag: String?, data: Data): String {
        var text = templateText
        if (roleTag.isNullOrBlank()) return text

        if (roleTag == "agent") {
            if (!data.agentHas) {
                return text.replace(Regex("有[□☑]"), "有□").replace(Regex("无[□☑]"), "无☑")
            }
            text = text.replace(Regex("有[□☑]"), "有☑").replace(Regex("无[□☑]"), "无□")
            if (data.name.isNotBlank()) {
                text = text.replace(Regex("(姓名：)[^\\n]*"), "\$1${data.name.trim()}")
            }
            if (data.workUnit.isNotBlank() || data.jobTitle.isNotBlank() || data.phone.isNotBlank()) {
                text = text.replace(
                    Regex("单位：[^\\n]*职务：[^\\n]*联系电话：[^\\n]*"),
                    "单位：${data.workUnit.trim().ifBlank { "        " }}    职务：${data.jobTitle.trim().ifBlank { "律师" }}    联系电话：${data.phone.trim().ifBlank { "        " }}"
                )
            }
            if (data.agentAuthority == "一般授权") {
                text = text.replace(Regex("一般授权[□☑]"), "一般授权☑").replace(Regex("特别授权[□☑]"), "特别授权□")
            } else if (data.agentAuthority == "特别授权") {
                text = text.replace(Regex("一般授权[□☑]"), "一般授权□").replace(Regex("特别授权[□☑]"), "特别授权☑")
            }
            return text
        }

        if (roleTag in PERSON_ROLES) {
            if (data.name.isNotBlank()) {
                text = text.replace(Regex("(姓名：)[^\\n]*"), "\$1${data.name.trim()}")
            }
            when (data.gender) {
                "男" -> text = text.replace(Regex("男[□☑]"), "男☑").replace(Regex("女[□☑]"), "女□")
                "女" -> text = text.replace(Regex("男[□☑]"), "男□").replace(Regex("女[□☑]"), "女☑")
            }
            if (data.birthDate.isNotBlank() || data.ethnicity.isNotBlank()) {
                val m = Regex("^(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})").find(data.birthDate.trim())
                val birthStr = if (m != null) {
                    "${m.groupValues[1]} 年 ${m.groupValues[2].toInt()} 月 ${m.groupValues[3].toInt()} 日"
                } else {
                    data.birthDate.trim().ifBlank { "        年        月        日" }
                }
                text = text.replace(
                    Regex("出生日期：[^\\n]*民族：[^\\n]*"),
                    "出生日期：$birthStr    民族：${data.ethnicity.trim()}"
                )
            }
            if (data.workUnit.isNotBlank() || data.jobTitle.isNotBlank() || data.phone.isNotBlank()) {
                text = text.replace(
                    Regex("工作单位：[^\\n]*职务：[^\\n]*联系电话：[^\\n]*"),
                    "工作单位：${data.workUnit.trim().ifBlank { "    " }}    职务：${data.jobTitle.trim().ifBlank { "    " }}    联系电话：${data.phone.trim()}"
                )
            }
            if (data.domicile.isNotBlank()) {
                text = text.replace(Regex("(住所地（户籍所在地）：)[^\\n]*"), "\$1${data.domicile.trim()}")
            }
            if (data.residence.isNotBlank()) {
                text = text.replace(Regex("(经常居住地：)[^\\n]*"), "\$1${data.residence.trim()}")
            }
            if (data.idType.isNotBlank()) {
                text = text.replace(Regex("(证件类型：)[^\\n]*"), "\$1${data.idType.trim()}")
            }
            if (data.idNumber.isNotBlank()) {
                text = text.replace(Regex("(证件号码：)[^\\n]*"), "\$1${data.idNumber.trim()}")
            }
            return text
        }

        if (roleTag in ORG_ROLES) {
            if (data.name.isNotBlank()) {
                text = text.replace(Regex("(名称：)[^\\n]*"), "\$1${data.name.trim()}")
            }
            if (data.domicile.isNotBlank()) {
                text = text.replace(Regex("(住所地（主要办事机构所在地）：)[^\\n]*"), "\$1${data.domicile.trim()}")
            }
            if (data.regAddress.isNotBlank()) {
                text = text.replace(Regex("(注册地\\s*/\\s*登记地：)[^\\n]*"), "\$1${data.regAddress.trim()}")
            }
            if (data.legalRep.isNotBlank() || data.repTitle.isNotBlank() || data.phone.isNotBlank()) {
                text = text.replace(
                    Regex("法定代表人\\s*/\\s*负责人：[^\\n]*职务：[^\\n]*联系电话：[^\\n]*"),
                    "法定代表人 / 负责人：${data.legalRep.trim().ifBlank { "    " }}    职务：${data.repTitle.trim().ifBlank { "法定代表人" }}    联系电话：${data.phone.trim()}"
                )
            }
            if (data.creditCode.isNotBlank()) {
                text = text.replace(Regex("(统一社会信用代码：)[^\\n]*"), "\$1${data.creditCode.trim()}")
            }
            if (data.bankAccount.isNotBlank()) {
                text = text.replace(Regex("(银行账号：)[^\\n]*"), "\$1${data.bankAccount.trim()}")
            }
            if (data.bankHolder.isNotBlank()) {
                text = text.replace(Regex("(开户名：)[^\\n]*"), "\$1${data.bankHolder.trim()}")
            }
            if (data.bankName.isNotBlank()) {
                text = text.replace(Regex("(开户行：)[^\\n]*"), "\$1${data.bankName.trim()}")
            }
            if (data.orgType.isNotBlank()) {
                for (ot in ORG_TYPES) {
                    text = text.replace(Regex(Regex.escape(ot) + "[□☑]"), "$ot${if (ot == data.orgType) "☑" else "□"}")
                }
            }
            if (data.ownershipNature.isNotBlank()) {
                text = text.replace(Regex("国有[□☑]"), "国有${if (data.ownershipNature == "国有") "☑" else "□"}")
                    .replace(Regex("控股[□☑]"), "控股${if (data.ownershipNature == "国有" && data.stateControl == "控股") "☑" else "□"}")
                    .replace(Regex("参股[□☑]"), "参股${if (data.ownershipNature == "国有" && data.stateControl == "参股") "☑" else "□"}")
                    .replace(Regex("民营[□☑]"), "民营${if (data.ownershipNature == "民营") "☑" else "□"}")
                    .replace(Regex("其他[□☑]"), "其他${if (data.ownershipNature == "其他") "☑" else "□"}")
            }
            return text
        }

        return text
    }

    /** 该 roleTag 是否属于可快速填入的主体行 */
    fun isPartyFillable(roleTag: String?): Boolean =
        roleTag == "agent" || roleTag in PERSON_ROLES || roleTag in ORG_ROLES

    private val PERSON_ROLES = setOf("plaintiff_person", "defendant_person", "third_person")
    private val ORG_ROLES = setOf("plaintiff_org", "defendant_org", "third_org")
}
