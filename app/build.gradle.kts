plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// ============================================================
// 版本号：单一来源 = gradle.properties 的 VERSION_* 键。
// 默认走「提交数驱动」的自动递增，具体算法见下。
// 特殊发布需要手工覆盖时，在 gradle.properties 显式设置 VERSION_CODE / VERSION_NAME。
// ============================================================
// 从 gradle.properties 读取整数属性（缺省用默认值）
fun propInt(name: String, default: Int): Int =
    (project.findProperty(name) as? String)?.toIntOrNull() ?: default

// git 提交数（versionCode 单调递增的基准）
val gitCommitCount = runCatching {
    val tmp = createTempFile("gitcount", ".txt")
    exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        standardOutput = tmp.outputStream()
    }
    val n = tmp.readText().trim().toIntOrNull()
    tmp.delete()
    n
}.getOrNull() ?: 1

// 版本号参数收口到 gradle.properties（单一来源）。
// 仓库经多次 filter-repo 瘦身，提交数重置后需同步调整 VERSION_BASE_COMMIT。
// 当前公式：15 commits + BASE=5 → v3.1.0，每 +1 commit → patch 号自动 +1。
val VERSION_BASE_COMMIT = propInt("VERSION_BASE_COMMIT", 5)
val VERSION_MAJOR_BASE = propInt("VERSION_MAJOR_BASE", 3)
val VERSION_CODE_OFFSET = propInt("VERSION_CODE_OFFSET", 186)

// 三段版本号推导：v = (提交数 - BASE) + MAJOR*100，三位各自按 base-10 自然进位（逢十进一）。
val derivedV = (if (gitCommitCount > VERSION_BASE_COMMIT) (gitCommitCount - VERSION_BASE_COMMIT) else 0) + VERSION_MAJOR_BASE * 100
val autoMajor = (derivedV / 100) % 10
val autoMinor = (derivedV / 10) % 10
val autoPatch = derivedV % 10
val autoVersionName = "$autoMajor.$autoMinor.$autoPatch"

// 允许显式覆盖（发版兜底）
val overrideVersionCode = propInt("VERSION_CODE", 0)
val overrideVersionName = project.findProperty("VERSION_NAME") as? String

fun fileProperty(name: String, default: String): String =
    (project.findProperty(name) as? String) ?: default

android {
    namespace = "com.wb.mdgw"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.wb.mdgw"
        minSdk = 24
        targetSdk = 34
        versionCode = if (overrideVersionCode > 0) overrideVersionCode else gitCommitCount + VERSION_CODE_OFFSET
        versionName = overrideVersionName ?: autoVersionName
    }

    signingConfigs {
        create("release") {
            // 签名信息来自 Gradle 属性（CI 经 secrets 传入，本地见 signing.properties）。
            // 默认值保留是为了兼容本地 build_apk.sh 直接 assembleRelease 的用法。
            val storeFileParam = fileProperty("storeFile", "mdgw-release.jks")
            val storePasswordParam = fileProperty("storePassword", "mdgw123456")
            val keyAliasParam = fileProperty("keyAlias", "mdgw")
            val keyPasswordParam = fileProperty("keyPassword", "mdgw123456")
            val storeTypeParam = fileProperty("storeType", "PKCS12")

            storeFile = rootProject.file(storeFileParam)
            storePassword = storePasswordParam
            keyAlias = keyAliasParam
            keyPassword = keyPasswordParam
            storeType = storeTypeParam
        }
    }

    buildTypes {
        release {
            // R8 代码压缩 + 资源收缩，显著减小 APK 体积。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(libs.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)

    // PDF 页码：排除 BouncyCastle（仅加密 PDF 才需要），未加密文件加页码不受影响；
    // 若遇到加密 PDF，引擎会捕获并提示，不会崩溃。
    implementation(libs.pdfbox.android) {
        exclude(group = "org.bouncycastle")
    }

    // 单元测试（校验 docx / OOXML 生成正确性）
    testImplementation(libs.junit)

    // 公文草稿序列化：GovDoc 模型 ↔ JSON（零反射、体积可控）
    implementation(libs.kotlinx.serialization.json)

    // 协程（显式锁定 1.7.3，避免 suspendCancellableCoroutine 签名差异导致编译失败）
    implementation(libs.kotlinx.coroutines.android)

    // 公众号排版：Markdown -> 微信公众号 HTML（commonmark-java 解析 + Jsoup 内联样式）
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.commonmark.gfm.strikethrough)
    implementation(libs.commonmark.task.list)
    implementation(libs.jsoup)
}