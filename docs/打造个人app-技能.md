---
name: 打造个人app
description: "从零打造一款个人 Android App 的可复用全流程技能。覆盖需求澄清、技术选型、项目骨架、功能开发、签名构建、云编译、分发、迭代的完整闭环。不绑定任何具体业务领域——遇到任何『把某个需求做成可安装 APK 并持续迭代』的任务都适用。"
version: "2.0.0"
author: "CodeBuddy AI"
created: "2026-08-13"
updated: "2026-08-13"
license: MIT
---

# 打造个人 App

把一个**领域需求**快速做成可安装、可分发、可持续迭代的 Android App。本技能是**通用方法论**——不绑定任何具体业务（工具类、内容类、效率类、游戏类皆可）。

技能末尾附有一份**实战案例对照表**，用真实项目说明每一步如何落地，但每一步的准则本身与业务无关。

## 适用场景

- 有一个明确的领域需求，想做成手机 App
- 不想上架应用商店，需要**下载即装 / 扫码安装**
- 需要持续迭代，每次改动后快速出包
- 目标平台 Android（iOS 不适用本技能）

## 核心原则（不变式）

> **① 先澄清需求，再动手。** 开始写代码前，先用一个「最小功能」问题问清用户：这个 App 最核心要解决的 1 件事是什么？选哪个功能打头阵？

> **② 最小闭环优先。** 先让 1 个功能跑通「编写 → 编译 → 签名 → 安装 → 使用」，再横向加功能。不要一开始铺满功能。

> **③ 脚本化一切。** 构建、签名、推送全部脚本化，一条命令出包。手动操作是迭代速度的天敌。

> **④ APK 入库。** 编译好的 release APK 纳入版本控制，用户随时可从仓库下载最新版，不依赖应用商店。

---

## 全流程七步

### 第 1 步：需求澄清与范围界定

动手前先与用户对齐（用 AskUserQuestion 提问，不要自己猜）：

| 必答问题 | 目的 |
|---------|------|
| 这个 App 给谁用？解决什么核心痛点？ | 定义目标与范围 |
| 必须包含哪几个功能？优先顺序？ | 决定先做哪个最小闭环 |
| 是纯离线，还是需要联网/账号？ | 决定是否引入网络层 |
| 目标 Android 版本范围？ | 决定 minSdk |
| 分发方式？应用商店 / 直装 APK / 内部分发？ | 决定签名与 CI 策略 |

**范围界定准则：**
- 首版只做 **1 个核心功能**，其余放后续迭代
- 每个功能问一句"**没有它用户会用吗？**"，答案是"不会"才保留
- 把"锦上添花"的需求记入 backlog，不塞进首版

### 第 2 步：技术选型

**基础栈（通用，直接采用）：**

| 维度 | 选择 | 理由 |
|------|------|------|
| 语言 | **Kotlin** | Android 官方语言 |
| UI 框架 | **Jetpack Compose + Material 3** | 声明式 UI，开发效率高 |
| 构建 | **Gradle + Kotlin DSL** | 官方默认，类型安全 |
| minSdk | **24**（覆盖 95%+ 设备） | 兼容性好 |
| 版本管理 | **Git + Gitee / GitHub** | 私有仓库免费，支持 CI |
| 分发 | **APK 直装** | 无审核、迭代快 |

**依赖选型（按功能需要，不要全上）：**

| 功能需求 | 推荐依赖 | 说明 |
|---------|----------|------|
| 界面 | `compose-bom` + material3 + icons-extended | 基础 |
| 网络请求 | `Retrofit` + `OkHttp` | 仅当需要联网 |
| 本地数据库 | `Room` / DataStore | 仅当数据量大/需查询；小数据用 SharedPreferences 即可 |
| 图片加载 | `Coil` | 仅当展示网络图片 |
| JSON | `kotlinx-serialization-json` / `Gson` | 网络/持久化常用 |
| 协程 | `kotlinx-coroutines` | 异步标准 |
| 文件访问 | `androidx.documentfile` | SAF 文档 URI |
| 其他 | 按业务选，**用多少引多少** | 控制包体积 |

> **选型铁律**：从"业务需要"反推依赖，不要一股脑全上。每多一个库，就多一份体积和坑。

### 第 3 步：项目骨架

```
app/
├── build.gradle.kts          # 构建配置（依赖、签名、SDK）
├── src/main/
│   ├── AndroidManifest.xml   # 应用名、图标、权限
│   ├── java/com/<pkg>/
│   │   ├── MainActivity.kt   # 入口 + 底部导航 + 路由
│   │   ├── ui/               # 界面层（每个页面一个 Screen）
│   │   │   ├── theme/        # 颜色/主题
│   │   │   └── <Feature>Screen.kt
│   │   ├── data/             # 数据层（持久化、网络、仓库）
│   │   └── domain/           # 业务逻辑层（纯 Kotlin）
│   └── res/                  # 图标、字符串、主题
├── build_apk.sh              # 一键编译（见第 4 步）
├── publish_apk.sh            # 一键编译+提交+推送（见第 6 步）
├── setup_env.sh              # 环境变量注入
├── app-release.jks           # 签名密钥（入库）
└── gradle/wrapper/           # Gradle Wrapper（入库）
```

**骨架要点：**

1. **UI 用 `NavigationBar` + 枚举路由**，每个 Tab 对应一个枚举值，`when(mode)` 分发：
   ```kotlin
   enum class Tab { HOME, SETTINGS }
   // when(mode) { Tab.HOME -> HomeScreen(...) ; Tab.SETTINGS -> SettingsScreen(...) }
   ```
   多页面用 Jetpack Navigation；个人工具几个 Tab 用枚举即可。

2. **三层分离**（无论 App 多小都保持）：
   | 层 | 职责 | 约束 |
   |----|------|------|
   | `ui` | 界面 + 状态 | 不写业务规则 |
   | `domain` | 业务逻辑 | 纯 Kotlin，可单测 |
   | `data` | 持久化/网络 | 只读写，不决策 |

   > 逻辑层独立后，可脱离 Android 环境用 Java/JUnit 验证，开发快、不易出 bug。

3. **签名密钥入库**（私有仓库）：
   ```kotlin
   signingConfigs {
       create("release") {
           storeFile = rootProject.file("app-release.jks")
           storePassword = "<密码>"; keyAlias = "<别名>"; keyPassword = "<密码>"
       }
   }
   buildTypes["release"] { signingConfig = signingConfigs.getByName("release") }
   ```

### 第 4 步：功能开发

按**首版 1 个核心功能**推进，复用以下通用模式：

#### 4.1 「编辑 + 预览」互斥模式

需要编辑/预览切换的功能（排版、代码、笔记），用**子 Tab 切换**：
- 编辑态只挂编辑器，预览态才挂 `WebView`
- **不要同时挂载**——WebView 是独立 surface，会和 Compose 叠层冲突（表现为界面重叠）

#### 4.2 复制富文本到第三方 App

若功能涉及"复制排版后粘贴到第三方 App"：
- 第三方编辑器常剥离 `<head>/<style>/class`，需 **100% 内联样式** 才能保留排版
- 最稳的复制 = **WebView 原生 `selectAll + execCommand('copy')`**，与输入法复制同源
- 启用 WebView JS 前，对 HTML 做 **sanitize**（剥离 `script/iframe/on*`）防 XSS

#### 4.3 草稿自动保存

用户输入防丢失：`kotlinx.serialization` 序列化 → 防抖 1.5s 写 `SharedPreferences` → 进入页面恢复 + 顶栏"未保存/已保存"状态。

#### 4.4 耗时任务处理

文件转换、网络、大数据处理放 `Dispatchers.IO` + `withContext`，UI 用 `Dispatchers.Main`，配进度条/加载态。

> 以上是通用模式。**具体业务逻辑（转 PDF、排表格、算坐标等）由领域代码实现**，本技能不预设——这正是可复用的关键。

### 第 5 步：签名构建

#### 5.1 生成密钥（一次性）

```bash
keytool -genkeypair \
  -keystore app-release.jks -alias <别名> \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <密码> -keypass <密码> -dname "CN=<名字>"
```

#### 5.2 一键编译脚本 `build_apk.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/setup_env.sh"     # 注入 JAVA_HOME、ANDROID_HOME
cd "$SCRIPT_DIR"
ANDROID_HOME="$ANDROID_HOME" ./gradlew assembleRelease --no-daemon
APK_SRC="app/build/outputs/apk/release/app-release.apk"
APK_DST="$SCRIPT_DIR/<应用名>.apk"
cp -f "$APK_SRC" "$APK_DST"
echo ">>> 完成：$APK_DST ($(du -h "$APK_DST" | cut -f1))"
```

#### 5.3 环境注入 `setup_env.sh`

```bash
export JAVA_HOME="${JAVA_HOME:-/path/to/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-/path/to/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"
```

> **Gradle Wrapper 必须入库**，保证任何环境编译版本一致。

### 第 6 步：云编译（CI）

配置流水线，推送即编译，不依赖本地环境。

**关键经验：**
- Gitee Go 的 `build@gradle` 镜像版本易不匹配 → 改用 `build@shell`/`build@python` 自控
- Gradle 官方源可能超时 → 加国内镜像备选（腾讯云）
- CI 环境 Ubuntu 用 `apt`，不是 `yum`
- 顶层 `build.gradle` 若 `.kts` 报错 → 降级 Groovy DSL `.gradle`
- 用 `PREFER_SETTINGS` 避免全局 `init.gradle` 冲突

### 第 7 步：分发与迭代

#### 7.1 APK 入库（核心分发）

APK 被 `.gitignore` 排除，用 `git add -f` 强制纳入：

```bash
git add -f app/build/outputs/apk/release/app-release.apk
git commit -m "chore: 更新 release APK"
git push origin master
```

#### 7.2 一键推送脚本 `publish_apk.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TOKEN="${GITEE_TOKEN:-}"
[ -z "$TOKEN" ] && read -r -s TOKEN
./gradlew --offline assembleRelease
APK="app/build/outputs/apk/release/app-release.apk"
git add -f "$APK"
git diff --cached --quiet && { echo "APK 无变化"; exit 0; }
git commit -m "chore: 自动更新 release APK ($(date '+%Y-%m-%d %H:%M'))"
# 令牌通过 insteadOf 临时注入，不写入历史
git -c "url.https://<user>:${TOKEN}@gitee.com/.insteadOf=git@gitee.com:" push origin master
```

> **令牌安全**：推送令牌通过 `git -c url.insteadOf` **临时注入**，绝不写入仓库配置和历史。

#### 7.3 迭代节奏

每次改动：改代码 → `bash build_apk.sh` → `git add` 源码 + `git add -f` APK → commit → push。

**提交信息规范：** `feat:` 新功能 / `fix:` 修 bug / `refactor:` 重构 / `chore:` 构建发布 / `ci:` CI / `docs:` 文档。

---

## 验证清单（每次出包）

- [ ] `bash build_apk.sh` 编译通过，无 error
- [ ] APK 体积合理（个人工具 8–15MB 正常）
- [ ] 签名有效（`apksigner verify` 或安装不报未签名）
- [ ] 源码 + APK 已提交
- [ ] 已推送到远端
- [ ] 提交信息符合规范

---

## 常见坑与解法（与业务无关）

| 坑 | 症状 | 解法 |
|----|------|------|
| WebView 与编辑器叠层 | 编辑态预览也显示 | 编辑/预览互斥挂载，不要同时创建 |
| Compose 缺 import | 编译报 `Unresolved reference` | 补 `import androidx.compose.runtime.X` |
| APK 不入库 | 用户无法从仓库下载 | `git add -f` 强制纳入 |
| 令牌泄露 | 推送失败/安全风险 | `insteadOf` 临时注入，不入 config |
| CI Gradle 版本不匹配 | 云编译失败 | Wrapper 入库 + `--no-daemon` + 国内镜像 |
| 第三方粘贴丢样式 | 粘贴后排版乱 | 100% 内联 + 外层包裹 + 硬兜底 |
| 签名不生效 | 安装提示未签名 | `signingConfigs` + `buildTypes.release` |
| 草稿丢失 | 输入消失 | 防抖自动保存 + 进入恢复 |
| 主线程卡顿 | 界面 ANR/卡死 | 耗时任务放 `Dispatchers.IO` |
| 依赖过多 | 包体积大、编译慢 | 用多少引多少，按需裁剪 |

---

## 实战案例对照表（陈律工具箱）

> 用真实项目说明每一步如何落地。**这些是"一个例子"，不是技能本身**——你做其他 App 时，把下表对应列换成你自己的业务即可。

| 本技能步骤 | 陈律工具箱落地方式 |
|-----------|------------------|
| 第1步 需求 | 面向法律人：4 个痛点（MD 编辑、公文排版、PDF 页码/盖章、公众号排版） |
| 第2步 选型 | Compose + commonmark(转HTML) + jsoup(内联) + pdfbox(盖章) + 手写OOXML(免POI) |
| 第3步 骨架 | 4 Tab：MD / word / PDF / 公众号，GovDoc 模型跨 Tab 共享 |
| 第4步 功能 | 公众号排版：两路 HTML 分离（预览带style / 粘贴全内联）+ 表格固定布局 |
| 第5步 签名 | `mdgw-release.jks`，`build_apk.sh` 一键出 13MB APK |
| 第6步 CI | Gitee Go + GitHub Actions 双流水线，Wrapper 入库 + 国内镜像 |
| 第7步 分发 | `publish_apk.sh` 一键编译+`git add -f`+令牌 insteadOf 推送 |
| 迭代 | 60+ 次提交，每次改动 3 分钟内出包 |

**从本案例可复用的模板：**

- ✅ `build_apk.sh` / `publish_apk.sh` / `setup_env.sh` —— 直接复制改名可用
- ✅ 枚举路由 + 底部导航的 `MainActivity` 骨架
- ✅ UI/data/domain 三层分离 + 草稿自动保存
- ✅ 编辑/预览互斥 + WebView 原生复制 + 表格内联兜底
- ❌ 公文规范、PDF 坐标、公众号 CSS 等业务逻辑 —— 属于特定 App，不可复用
