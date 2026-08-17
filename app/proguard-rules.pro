# ============================================================
# 陈律工具箱 · Release R8 混淆/收缩规则
# 说明：release 已开启 isMinifyEnabled=true + isShrinkResources=true，
# 开启前请用现有的 JVM 回归测试 + 手工冒烟验证（docx/PDF/pptx/公众号）。
# ============================================================

# ---------- kotlinx.serialization ----------
# 生成的 serializer 类与 Companion 查找需保留，否则运行时抛 MissingFieldException。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# 保留各 @Serializable 类生成的 $serializer。
-keep,includedescriptorclasses class com.wb.**$$serializer { *; }
-keepclassmembers class com.wb.** {
    *** Companion;
}
-keepclasseswithmembers class com.wb.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- PDFBox (tom-roush pdfbox-android) ----------
# PDFBox 依赖反射与资源查找定位字体/加密处理器，整体保留以保证加页码/盖章正确。
-keep,allowoptimization class org.apache.pdfbox.** { *; }
-keepclassmembers class org.apache.pdfbox.** { *; }

# ---------- jsoup ----------
# jsoup 通过反射读写 HTML 属性与数据，保留核心运行类。
-keep class org.jsoup.** { *; }

# ---------- commonmark ----------
# commonmark 解析器按类型分发，保留其访问扩展点所需成员。
-dontwarn org.commonmark.**