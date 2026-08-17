package com.wb.mdgw.wechat

/**
 * 5 套公众号主题（2 套法律自媒体专属 + 3 套通用）。
 *
 * 设计要点：
 * 1. 每套主题以「标准 CSS 文本」保存，由 MdWechatConverter 解析后**内联**到每个 HTML 元素，
 *    保证粘贴进微信公众号后台不丢样式（公众号只识别内联 style，不识别 <style>/外链）。
 * 2. CSS 中的选择器：body 用于外层容器背景/边距；h1~h3 / p / blockquote / ul,ol,li /
 *    table,th,td / pre,code / a / img / hr / strong 均为公众号常见语义标签。
 * 3. 兼容性已内置：段落 text-align:justify（两端对齐高级感）、表格 width:100%、代码块
 *    overflow-x:auto（横向滚动）、禁止外链字体/外部 CSS。
 *
 * SP 存储 Key 与各主题 key 一一对应，见 ThemeStorage。
 */
object ThemePreset {

    data class Theme(
        val key: String,   // 与 SP 存储 Key 完全一致
        val name: String,  // 下拉框展示名
        val css: String    // 官方默认 CSS
    )

    fun getTheme(key: String): Theme =
        THEMES.firstOrNull { it.key == key } ?: THEMES.first()

    fun getThemeName(key: String): String = getTheme(key).name

    // ===================== 主题 CSS 定义 =====================

    /** 1. 法律深蓝普法：深蓝标题 + 浅蓝背景块，专业稳重，适合普法/案例/律所推文 */
    private val LAW_BLUE_CSS = """
        body {
          font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
          font-size: 16px;
          line-height: 1.6;
          color: #2c3e50;
          letter-spacing: 0.5px;
          padding: 16px;
          background: #f4f8ff;
          word-break: break-word;
        }
        h1 {
          text-align: center;
          font-size: 24px;
          font-weight: 700;
          color: #0b3d91;
          padding: 18px 12px;
          margin: 24px 0 20px;
          background: #e8f1ff;
          border-radius: 8px;
        }
        h2 {
          font-size: 20px;
          font-weight: 700;
          color: #0b3d91;
          margin: 28px 0 14px;
          padding-left: 12px;
          border-left: 5px solid #0b3d91;
        }
        h3 {
          font-size: 17px;
          font-weight: 600;
          color: #1a4fb0;
          margin: 20px 0 10px;
        }
        p {
          margin: 10px 0;
          text-align: justify;
        }
        blockquote {
          margin: 16px 0;
          padding: 12px 16px;
          background: #eef5ff;
          border-left: 4px solid #4a90e2;
          color: #34506b;
          border-radius: 4px;
        }
        ul, ol {
          padding-left: 22px;
          margin: 14px 0;
        }
        li {
          margin: 6px 0;
        }
        strong {
          color: #0b3d91;
        }
        a {
          color: #1a4fb0;
          text-decoration: none;
          border-bottom: 1px solid #1a4fb0;
        }
        img {
          max-width: 100%;
          height: auto;
          border-radius: 6px;
          display: block;
          margin: 12px auto;
        }
        hr {
          border: none;
          border-top: 1px solid #cfe0ff;
          margin: 24px 0;
        }
        table {
          width: 100%;
          border-collapse: collapse;
          table-layout: fixed;
          margin: 16px 0;
          font-size: 15px;
        }
        th, td {
          border: 1px solid #bcd4f5;
          padding: 8px 10px;
          text-align: left;
        }
        th {
          background: #e8f1ff;
          color: #0b3d91;
          font-weight: 600;
        }
        pre {
          background: #f2f7ff;
          border: 1px solid #d6e6ff;
          border-radius: 6px;
          padding: 14px;
          overflow-x: auto;
          margin: 16px 0;
          font-size: 14px;
        }
        code {
          font-family: Consolas, Monaco, "Courier New", monospace;
          background: #eef3fb;
          color: #c0392b;
          padding: 2px 5px;
          border-radius: 3px;
        }
        pre code {
          background: transparent;
          color: #2c3e50;
          padding: 0;
        }
    """.trimIndent()

    /** 2. 法律极简纯白：纯白干净、低对比、极简线条，适合条文解读/问答科普 */
    private val LAW_CLEAN_CSS = """
        body {
          font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
          font-size: 16px;
          line-height: 1.6;
          color: #333333;
          letter-spacing: 0.3px;
          padding: 16px;
          background: #ffffff;
          word-break: break-word;
        }
        h1 {
          text-align: center;
          font-size: 23px;
          font-weight: 700;
          color: #222222;
          margin: 22px 0 18px;
        }
        h2 {
          font-size: 19px;
          font-weight: 600;
          color: #222222;
          margin: 26px 0 12px;
          padding-bottom: 6px;
          border-bottom: 1px solid #eeeeee;
        }
        h3 {
          font-size: 17px;
          font-weight: 600;
          color: #333333;
          margin: 20px 0 10px;
        }
        p {
          margin: 10px 0;
          text-align: justify;
          color: #444444;
        }
        blockquote {
          margin: 16px 0;
          padding: 10px 16px;
          color: #666666;
          border-left: 3px solid #dddddd;
          background: #fafafa;
        }
        ul, ol {
          padding-left: 22px;
          margin: 14px 0;
        }
        li {
          margin: 6px 0;
        }
        strong {
          color: #111111;
        }
        a {
          color: #555555;
          text-decoration: underline;
        }
        img {
          max-width: 100%;
          height: auto;
          display: block;
          margin: 12px auto;
        }
        hr {
          border: none;
          border-top: 1px solid #eeeeee;
          margin: 24px 0;
        }
        table {
          width: 100%;
          border-collapse: collapse;
          table-layout: fixed;
          margin: 16px 0;
          font-size: 15px;
        }
        th, td {
          border: 1px solid #e5e5e5;
          padding: 8px 10px;
        }
        th {
          background: #f7f7f7;
          font-weight: 600;
        }
        pre {
          background: #f7f7f7;
          border: 1px solid #ececec;
          border-radius: 4px;
          padding: 14px;
          overflow-x: auto;
          margin: 16px 0;
          font-size: 14px;
        }
        code {
          font-family: Consolas, Monaco, monospace;
          background: #f0f0f0;
          color: #c0392b;
          padding: 2px 5px;
          border-radius: 3px;
        }
        pre code {
          background: transparent;
          color: #333333;
          padding: 0;
        }
    """.trimIndent()

    /** 3. 简约阅读：暖色舒适，长时间阅读不累，通用自媒体 */
    private val SIMPLE_CSS = """
        body {
          font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
          font-size: 16px;
          line-height: 1.6;
          color: #3f3f3f;
          padding: 18px;
          background: #fffdf8;
          word-break: break-word;
        }
        h1 {
          font-size: 23px;
          font-weight: 700;
          color: #2b2b2b;
          text-align: center;
          margin: 22px 0 18px;
        }
        h2 {
          font-size: 19px;
          font-weight: 700;
          color: #2b2b2b;
          margin: 26px 0 12px;
        }
        h3 {
          font-size: 17px;
          font-weight: 600;
          color: #444444;
          margin: 20px 0 10px;
        }
        p {
          margin: 10px 0;
          text-align: justify;
        }
        blockquote {
          margin: 16px 0;
          padding: 12px 16px;
          color: #6b6b6b;
          background: #f5f0e6;
          border-left: 4px solid #d9c9a3;
        }
        ul, ol {
          padding-left: 22px;
          margin: 14px 0;
        }
        li {
          margin: 6px 0;
        }
        strong {
          color: #b5651d;
        }
        a {
          color: #b5651d;
          text-decoration: none;
        }
        img {
          max-width: 100%;
          height: auto;
          display: block;
          margin: 12px auto;
          border-radius: 4px;
        }
        hr {
          border: none;
          border-top: 1px solid #e7ddc9;
          margin: 24px 0;
        }
        table {
          width: 100%;
          border-collapse: collapse;
          table-layout: fixed;
          margin: 16px 0;
          font-size: 15px;
        }
        th, td {
          border: 1px solid #e0d6bf;
          padding: 8px 10px;
        }
        th {
          background: #f5f0e6;
          color: #6b5526;
          font-weight: 600;
        }
        pre {
          background: #f5f0e6;
          border: 1px solid #e7ddc9;
          border-radius: 6px;
          padding: 14px;
          overflow-x: auto;
          margin: 16px 0;
          font-size: 14px;
        }
        code {
          font-family: Consolas, Monaco, monospace;
          background: #efe7d6;
          color: #9c4221;
          padding: 2px 5px;
          border-radius: 3px;
        }
        pre code {
          background: transparent;
          color: #3f3f3f;
          padding: 0;
        }
    """.trimIndent()

    /** 4. 科技干货：青蓝科技感、代码块深色高亮，技术文章 */
    private val TECH_CSS = """
        body {
          font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
          font-size: 16px;
          line-height: 1.6;
          color: #1f2933;
          padding: 16px;
          background: #ffffff;
          word-break: break-word;
        }
        h1 {
          font-size: 24px;
          font-weight: 700;
          color: #0f172a;
          text-align: center;
          letter-spacing: 1px;
          margin: 22px 0 18px;
        }
        h2 {
          font-size: 20px;
          font-weight: 700;
          color: #0ea5e9;
          margin: 28px 0 12px;
          padding-left: 10px;
          border-left: 4px solid #0ea5e9;
        }
        h3 {
          font-size: 17px;
          font-weight: 600;
          color: #0369a1;
          margin: 20px 0 10px;
        }
        p {
          margin: 10px 0;
          text-align: justify;
        }
        blockquote {
          margin: 16px 0;
          padding: 12px 16px;
          color: #334155;
          background: #f0f9ff;
          border-left: 4px solid #38bdf8;
        }
        ul, ol {
          padding-left: 22px;
          margin: 14px 0;
        }
        li {
          margin: 6px 0;
        }
        strong {
          color: #0284c7;
        }
        a {
          color: #0ea5e9;
          text-decoration: none;
        }
        img {
          max-width: 100%;
          height: auto;
          display: block;
          margin: 12px auto;
        }
        hr {
          border: none;
          border-top: 1px dashed #bae6fd;
          margin: 24px 0;
        }
        table {
          width: 100%;
          border-collapse: collapse;
          table-layout: fixed;
          margin: 16px 0;
          font-size: 15px;
        }
        th, td {
          border: 1px solid #cbeafe;
          padding: 8px 10px;
        }
        th {
          background: #e0f2fe;
          color: #0369a1;
          font-weight: 600;
        }
        pre {
          background: #0f172a;
          border-radius: 8px;
          padding: 16px;
          overflow-x: auto;
          margin: 16px 0;
          font-size: 14px;
        }
        code {
          font-family: "SF Mono", Consolas, Monaco, monospace;
          background: #e2e8f0;
          color: #db2777;
          padding: 2px 6px;
          border-radius: 4px;
        }
        pre code {
          background: transparent;
          color: #e2e8f0;
          padding: 0;
        }
    """.trimIndent()

    /** 5. 商务正式：衬线字体、克制留白，公告/合作推文 */
    private val BUSINESS_CSS = """
        body {
          font-family: "Songti SC", "SimSun", Georgia, serif;
          font-size: 16px;
          line-height: 1.6;
          color: #333333;
          padding: 18px 20px;
          background: #ffffff;
          word-break: break-word;
        }
        h1 {
          font-size: 23px;
          font-weight: 700;
          color: #1a1a1a;
          text-align: center;
          letter-spacing: 2px;
          margin: 20px 0 18px;
        }
        h2 {
          font-size: 19px;
          font-weight: 700;
          color: #1a1a1a;
          margin: 26px 0 12px;
        }
        h3 {
          font-size: 17px;
          font-weight: 600;
          color: #333333;
          margin: 20px 0 10px;
        }
        p {
          margin: 10px 0;
          text-align: justify;
        }
        blockquote {
          margin: 16px 0;
          padding: 12px 18px;
          color: #555555;
          background: #f5f5f5;
          border-left: 4px solid #999999;
        }
        ul, ol {
          padding-left: 24px;
          margin: 14px 0;
        }
        li {
          margin: 6px 0;
        }
        strong {
          color: #000000;
        }
        a {
          color: #1a1a1a;
          text-decoration: underline;
        }
        img {
          max-width: 100%;
          height: auto;
          display: block;
          margin: 12px auto;
        }
        hr {
          border: none;
          border-top: 1px solid #cccccc;
          margin: 24px 0;
        }
        table {
          width: 100%;
          border-collapse: collapse;
          table-layout: fixed;
          margin: 16px 0;
          font-size: 15px;
        }
        th, td {
          border: 1px solid #cccccc;
          padding: 8px 10px;
        }
        th {
          background: #f0f0f0;
          font-weight: 700;
          color: #1a1a1a;
        }
        pre {
          background: #f5f5f5;
          border: 1px solid #dddddd;
          border-radius: 4px;
          padding: 14px;
          overflow-x: auto;
          margin: 16px 0;
          font-size: 14px;
        }
        code {
          font-family: Consolas, Monaco, monospace;
          background: #eeeeee;
          color: #b00020;
          padding: 2px 5px;
          border-radius: 3px;
        }
        pre code {
          background: transparent;
          color: #333333;
          padding: 0;
        }
    """.trimIndent()

    // 注意：THEMES 必须置于各 CSS 变量之后，否则按 Kotlin 顶层属性初始化顺序，
    // 会在 LAW_BLUE_CSS 等尚未初始化时就被引用，报 "must be initialized"。
    val THEMES: List<Theme> = listOf(
        Theme("css_law_blue", "法律深蓝普法", LAW_BLUE_CSS),
        Theme("css_law_clean", "法律极简纯白", LAW_CLEAN_CSS),
        Theme("css_simple", "简约阅读", SIMPLE_CSS),
        Theme("css_tech", "科技干货", TECH_CSS),
        Theme("css_business", "商务正式", BUSINESS_CSS)
    )
}
