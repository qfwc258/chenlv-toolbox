plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 自动版本号：
// - versionCode 以 git 提交数为基准（每次提交自增，保证单调递增）；
// - versionName 自动派生：基于里程碑锚点，每累积 10 个提交 minor 进一位（逢十进一），
//   末位 patch 按「提交数 % 10」自动递增，每次发布均进位，无需手动维护版本号。
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

// 自动版本号：三段均为个位数(0–9)，且随提交数自动递增。
// 版本值 v = (提交数 - VERSION_BASE_COMMIT) + VERSION_MAJOR_BASE*100，作为单一递增整数；
// 当前(≈183)对应 308 → v3.0.8，每次提交 +1，三位各自按 base-10 自然进位（首位亦自动递增）。
// 满 1000（即 9.9.9）后归零循环，保证三段永远 ≤9、不超过 10。
// 注意：仓库曾做历史瘦身(filter-repo 清理 APK)，提交数 199→183；为接续 v3.0.7 且保证
// versionCode 单调递增，VERSION_BASE_COMMIT 由 92 调至 75，并为 versionCode 引入 +17 偏移。
val VERSION_BASE_COMMIT = 75
val VERSION_MAJOR_BASE = 2
// 历史瘦身补偿偏移：旧历史 count=199(v3.0.7, versionCode=199)；瘦身后 count 回落 16，
// +17 使当前 versionCode=200 > 199，升级链不中断（此后随提交继续 +1 递增）。
val VERSION_CODE_OFFSET = 17
val v = (if (gitCommitCount > VERSION_BASE_COMMIT) (gitCommitCount - VERSION_BASE_COMMIT) else 0) + VERSION_MAJOR_BASE * 100
val autoMajor = (v / 100) % 10
val autoMinor = (v / 10) % 10
val autoPatch = v % 10
val autoVersionName = "$autoMajor.$autoMinor.$autoPatch"

android {
    namespace = "com.wb.mdgw"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.wb.mdgw"
        minSdk = 24
        targetSdk = 34
        versionCode = gitCommitCount + VERSION_CODE_OFFSET
        versionName = autoVersionName
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("mdgw-release.jks")
            storePassword = "mdgw123456"
            keyAlias = "mdgw"
            keyPassword = "mdgw123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // PDF 页码：Apache-2.0 的 PDFBox Android 移植版。
    // 排除 BouncyCastle（仅加密 PDF 才需要），未加密文件加页码不受影响；
    // 若遇到加密 PDF，引擎会捕获并提示，不会崩溃。
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }

    // 单元测试（校验 docx / OOXML 生成正确性）
    testImplementation("junit:junit:4.13.2")

    // 公文草稿序列化：GovDoc 模型 ↔ JSON（零反射、体积可控）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // 公众号排版：Markdown -> 微信公众号 HTML（commonmark-java 解析 + Jsoup 内联样式）
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.0")
    implementation("org.jsoup:jsoup:1.17.2")
}
