package com.wb.mdgw.wechat

import android.content.Context
import android.content.SharedPreferences

/**
 * 主题样式存储（SharedPreferences）。
 *
 * 读取优先级：用户自定义 CSS > 官方默认 CSS
 * 恢复默认：删除当前主题对应的 Key，下次读取即回落到官方默认。
 *
 * SP Key 与 ThemePreset 中每套主题的 key 完全一致：
 *   css_law_blue / css_law_clean / css_simple / css_tech / css_business
 */
object ThemeStorage {

    private const val SP_NAME = "md2wechat_themes"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /**
     * 获取某主题「生效中的 CSS」：
     * - 存在用户自定义 → 返回用户 CSS
     * - 不存在 → 返回官方默认 CSS
     */
    fun getCss(context: Context, themeKey: String): String {
        val custom = sp(context).getString(themeKey, null)
        return if (custom.isNullOrBlank()) {
            ThemePreset.getTheme(themeKey).css
        } else {
            custom
        }
    }

    /** 保存用户自定义 CSS（自动落盘） */
    fun saveCss(context: Context, themeKey: String, css: String) {
        sp(context).edit().putString(themeKey, css).apply()
    }

    /** 恢复默认：清除当前主题自定义缓存 */
    fun resetCss(context: Context, themeKey: String) {
        sp(context).edit().remove(themeKey).apply()
    }

    /** 是否存在用户自定义样式（用于 UI 提示） */
    fun hasCustom(context: Context, themeKey: String): Boolean =
        !sp(context).getString(themeKey, null).isNullOrBlank()
}
