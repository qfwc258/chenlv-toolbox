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

# ---------- 可选 / 被排除的可选依赖 ----------
# JP2（JPEG2000）解码器：pdfbox-android 的可选模块，本项目未引入；R8 报 Missing class。
-dontwarn com.gemalto.jp2.**

# BouncyCastle：app/build.gradle.kts 已 exclude，PDFBox 加密（PublicKeySecurityHandler）
# 未使用。缺类仅发生在该未被调用的加密路径上，忽略即可。
-dontwarn org.bouncycastle.**

# jspecify 空值注解（jsoup 依赖）：仅为编译期元数据，运行时无需类。
-dontwarn org.jspecify.annotations.**