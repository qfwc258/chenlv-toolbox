package com.wb.mdgw

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 打开 Word 文档时的「保真度预检」。
 *
 * 扫描源文件里 [DocxInPlace] 原位修改**能原样保留、但当前无法直接编辑**的特殊内容
 * （修订痕迹 / 文本框 / 域代码 / 浮动图片 / 批注等），用大白话列出来，
 * 让陈律在保存前心里有数——避免事后才发现某段内容没改到、或误以为被改动了。
 *
 * 这些结构在原位修改时整体 100% 不动（DOM 原样保留），提示的意义是
 * 「这部分此次不会进入可编辑列表」，而非「格式会丢失」。
 */
object DocxFidelity {

    /** 返回检测到的特殊内容中文标签（去重）；空列表表示常规文档、无需提示 */
    fun scan(original: ByteArray): List<String> {
        val xmlParts = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(original)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name.lowercase().endsWith(".xml")) xmlParts += zis.readBytes()
                e = zis.nextEntry
            }
        }
        val tags = linkedSetOf<String>()
        for (raw in xmlParts) {
            val dom = runCatching {
                DocumentBuilderFactory.newInstance()
                    .apply { isNamespaceAware = false }
                    .newDocumentBuilder()
                    .parse(ByteArrayInputStream(raw))
            }.getOrNull() ?: continue
            scanNode(dom.documentElement, tags)
        }
        return tags.toList()
    }

    private fun scanNode(node: Element, sink: MutableSet<String>) {
        when (node.tagLocal()) {
            "ins", "del", "rPrChange", "pPrChange" -> sink += "修订痕迹"
            "txbxContent", "textbox" -> sink += "文本框"
            "instrText", "fldSimple" -> sink += "域代码"
            "anchor" -> sink += "浮动图片/图形"
            "commentRangeStart", "commentRangeEnd", "commentReference" -> sink += "批注"
        }
        for (c in node.childElements()) scanNode(c, sink)
    }

    private fun Element.tagLocal(): String {
        val t = tagName
        val i = t.indexOf(':')
        return if (i >= 0) t.substring(i + 1) else t
    }

    private fun Element.childElements(): List<Element> {
        val list = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element) list += n
        }
        return list
    }
}
