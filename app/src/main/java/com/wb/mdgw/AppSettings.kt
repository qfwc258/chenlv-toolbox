package com.wb.mdgw

import android.content.Context
import com.wb.mdgw.pptx.PptDraftStore
import com.wb.mdgw.wechat.ThemePreset
import com.wb.mdgw.wechat.ThemeStorage
import com.wb.mdgw.wechat.WeChatDraftStore
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全局共享设置（单一数据源）。
 *
 * 「设置」Tab 与各功能 Tab 共用同一份内存态，任一改动即时同步到四屏；
 * 持久化落盘仍复用各自的 Store（SettingsStore / ThemeStorage / PptDraftStore）。
 * 用 StateFlow 而非屏幕内 remember：即便某屏在 AnimatedVisibility 隐藏期间组合被销毁，
 * 全局状态与后续恢复也不会丢失。
 */
object AppSettings {
    // ---- 通用 ----
    val darkMode = MutableStateFlow(false)

    // ---- WORD 公文 ----
    val wordSpec = MutableStateFlow<GovDocSpec>(SettingsStore.DEFAULT_SPEC)
    val smartQuotes = MutableStateFlow(false)
    val pageNumber = MutableStateFlow(false)
    val titleFont = MutableStateFlow("")

    // ---- 公众号 ----
    val wechatTheme = MutableStateFlow("")
    val wechatCss = MutableStateFlow("")

    // ---- PPTX ----
    val pptxTone = MutableStateFlow("2E5FA3")
    val pptxAutoPaginate = MutableStateFlow(true)

    /** 冷启动时从各持久化存储恢复内存态（仅执行一次）。 */
    fun init(context: Context) {
        val ctx = context.applicationContext
        darkMode.value = SettingsStore.isDarkMode(ctx)
        wordSpec.value = SettingsStore.spec(ctx)
        smartQuotes.value = SettingsStore.smartQuotes(ctx)
        pageNumber.value = SettingsStore.pageNumber(ctx)
        titleFont.value = SettingsStore.titleFont(ctx)

        // 公众号：主题 + 自定义 CSS（优先草稿，其次默认主题）
        val wd = WeChatDraftStore.load(ctx)
        val initTheme = wd?.themeKey?.takeIf { it.isNotBlank() } ?: ThemePreset.THEMES.first().key
        wechatTheme.value = initTheme
        wechatCss.value = wd?.customCss?.takeIf { it.isNotBlank() } ?: ThemeStorage.getCss(ctx, initTheme)

        // PPTX：色调 + 自动分页（新用户读 SettingsStore；老用户从草稿迁移旧主题映射）
        val legacyDraft = PptDraftStore.load(ctx)
        pptxTone.value = SettingsStore.pptxTone(ctx)
            ?: legacyTone(legacyDraft?.themeId, legacyDraft?.customColor)
            ?: "2E5FA3"
        pptxAutoPaginate.value = if (SettingsStore.pptxTone(ctx) != null) {
            SettingsStore.pptxAutoPaginate(ctx)
        } else {
            legacyDraft?.autoPaginate ?: true
        }
    }

    /** 旧草稿主题 → 主色调映射（兼容三套预设主题）。 */
    private fun legacyTone(themeId: String?, customColor: String?): String? = when (themeId) {
        "navy" -> "2E5FA3"
        "gray" -> "777777"
        "gov" -> "C0392B"
        "custom" -> customColor?.takeIf { it.isNotBlank() }
        else -> null
    }

    // ---------- 写方法（更新内存态并落盘） ----------

    fun setDarkMode(ctx: Context, v: Boolean) {
        darkMode.value = v
        SettingsStore.saveDarkMode(ctx, v)
    }

    fun chooseWordSpec(ctx: Context, spec: GovDocSpec) {
        wordSpec.value = spec
        titleFont.value = spec.mainTitleFont
        SettingsStore.applySpec(ctx, spec)
    }

    fun setSmartQuotes(ctx: Context, v: Boolean) {
        smartQuotes.value = v
        SettingsStore.saveSmartQuotes(ctx, v)
    }

    fun setPageNumber(ctx: Context, v: Boolean) {
        pageNumber.value = v
        SettingsStore.savePageNumber(ctx, v)
    }

    fun setTitleFont(ctx: Context, v: String) {
        titleFont.value = v
        SettingsStore.saveTitleFont(ctx, v)
    }

    fun setWechatTheme(ctx: Context, key: String) {
        wechatTheme.value = key
        wechatCss.value = ThemeStorage.getCss(ctx, key)
    }

    fun setWechatCss(ctx: Context, css: String) {
        wechatCss.value = css
        ThemeStorage.saveCss(ctx, wechatTheme.value, css)
    }

    fun resetWechatCss(ctx: Context) {
        ThemeStorage.resetCss(ctx, wechatTheme.value)
        wechatCss.value = ThemePreset.getTheme(wechatTheme.value).css
    }

    fun setPptxTone(ctx: Context, hex: String) {
        pptxTone.value = hex
        SettingsStore.savePptxTone(ctx, hex)
    }

    fun setPptxAutoPaginate(ctx: Context, v: Boolean) {
        pptxAutoPaginate.value = v
        SettingsStore.savePptxAutoPaginate(ctx, v)
    }
}
