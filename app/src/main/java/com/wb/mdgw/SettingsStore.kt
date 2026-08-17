package com.wb.mdgw

import android.content.Context
import android.content.SharedPreferences

/**
 * 公文排版设置的本地持久化。
 *
 * 用 SharedPreferences 保存用户在「公文设置」里的选择，
 * 退出 App 后再次打开仍然保留（默认规范：诉讼文书）。
 *
 * 设计要点：
 *  - 规范只存 specName（字符串），读取时按名字在 ALL_PRESETS 中回查，
 *    这样即使以后调整了某套规范的字号/页边距，老用户也能自动获得新参数。
 *  - 找不到对应名字（比如预设被重命名）时回退到默认的「诉讼文书」，不会崩。
 */
object SettingsStore {

    private const val PREF = "gov_doc_settings"

    private const val K_SPEC = "spec_name"
    private const val K_SMART_QUOTES = "smart_quotes"
    private const val K_PAGE_NUMBER = "page_number"
    private const val K_TITLE_FONT = "title_font"

    /** 默认规范：诉讼文书 */
    val DEFAULT_SPEC: GovDocSpec get() = GovDocSpec.COURT_DOC

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 已保存的公文排版规范；无记录时为「诉讼文书」 */
    fun spec(ctx: Context): GovDocSpec {
        val name = prefs(ctx).getString(K_SPEC, null) ?: return DEFAULT_SPEC
        return GovDocSpec.byName(name)
    }

    fun saveSpec(ctx: Context, spec: GovDocSpec) {
        prefs(ctx).edit().putString(K_SPEC, spec.specName).apply()
    }

    /** 中文直引号转弯引号 */
    fun smartQuotes(ctx: Context): Boolean =
        prefs(ctx).getBoolean(K_SMART_QUOTES, DEFAULT_SPEC.smartQuotes)

    fun saveSmartQuotes(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(K_SMART_QUOTES, v).apply()
    }

    /** 是否添加页码 */
    fun pageNumber(ctx: Context): Boolean =
        prefs(ctx).getBoolean(K_PAGE_NUMBER, DEFAULT_SPEC.pageNumber)

    fun savePageNumber(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(K_PAGE_NUMBER, v).apply()
    }

    /**
     * 标题字体。
     * 无记录时跟随当前规范的标题字体（诉讼文书=黑体、国标/行政=方正小标宋简体）。
     */
    fun titleFont(ctx: Context): String =
        prefs(ctx).getString(K_TITLE_FONT, null) ?: spec(ctx).mainTitleFont

    fun saveTitleFont(ctx: Context, v: String) {
        prefs(ctx).edit().putString(K_TITLE_FONT, v).apply()
    }

    /**
     * 切换规范时调用：把标题字体重置为新规范的默认值。
     * 避免出现「选了诉讼文书、标题却还是小标宋」的错配。
     */
    fun applySpec(ctx: Context, spec: GovDocSpec) {
        prefs(ctx).edit()
            .putString(K_SPEC, spec.specName)
            .putString(K_TITLE_FONT, spec.mainTitleFont)
            .apply()
    }

    /** 一次性读出完整的转换选项，供各页面直接使用 */
    fun options(ctx: Context): MdToGongwen.Options {
        val s = spec(ctx)
        return MdToGongwen.Options(
            spec = s,
            mainTitleFont = titleFont(ctx),
            smartQuotes = smartQuotes(ctx),
            pageNumber = pageNumber(ctx)
        )
    }
}
