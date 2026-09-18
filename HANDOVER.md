# HANDOVER.md — 条码工厂 Pro HD (Barcode Factory HD)

> 仓库：`https://github.com/yz8023/barcode`（分支 `main`）
> 最近 commit：`a2a19bcd259ab61d8912bf9f212f68b00d039586`（"Upload from Android"，即原 HTML 版）
> 本次交接内容为本机未提交的**安卓重构改动**，已在本环境实际构建验证通过。
> 备用 Gradle 发行版镜像（无法直连时）：`https://mirrors.cloud.tencent.com/gradle/`

---

## 1. 项目概述

- **一句话定位**：条码批量生成 + 扫码自动记录 + 复制/导出工具，用户把每行一个的数据转成 EAN/CODE/UPC 等 7 种条码，扫码后自动追加记录，可单独或一键复制。
- **技术栈**：Kotlin、ViewBinding、Material Components、ZXing core 3.5.3、zxing-android-embedded 4.3.0（扫码）、Gradle 8.4、AGP 8.3.2、Kotlin 1.9.22。
- **SDK 版本**：`minSdk 24`（Android 7.0）/ `targetSdk 34` / `compileSdk 34`。
- **包名**：`com.barcode.factory`
- **应用名**：条码工厂 Pro HD（versionName `1.0` / versionCode `1`）
- **commit 哈希**：`a2a19bcd259ab61d8912bf9f212f68b00d039586`（远程仓库已有历史）；本次重构改动尚未提交。

## 2. 开发环境

| 项 | 值 |
|----|----|
| OS | Debian bookworm（Linux）/ 亦支持 macOS、Windows |
| JDK | OpenJDK 17（本项目用 17.0.20.1）|
| Gradle | 8.4（wrapper 自带，无需预装）|
| AGP / Kotlin | 8.3.2 / 1.9.22 |
| Android SDK | platform 34 + build-tools 34.0.0（NDK 不需要）|
| SDK 路径配置 | 写于 `local.properties` 的 `sdk.dir`（如 `sdk.dir=/opt/android-sdk`），该文件已被 git 忽略 |
| 环境变量 | `JAVA_HOME` 指向 JDK17；也可用 `ANDROID_SDK_ROOT`/`ANDROID_HOME` 替代 `local.properties` |
| 第三方 Key | 无（本项目不依赖任何第三方服务 Key）|

## 3. 构建运行

从零构建到 debug 包（复制粘贴即可）：

```bash
# 1. 环境变量（按你的 SDK 路径调整）
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # 以实际为准
export ANDROID_SDK_ROOT=/opt/android-sdk               # 以实际为准

# 2. 若没有 local.properties 则创建
echo "sdk.dir=/opt/android-sdk" > local.properties

# 3. 构建
./gradlew :app:assembleDebug

# 4. 产物
# app/build/outputs/apk/debug/app-debug.apk
```

- **已知坑与解法**
  - `SDK location not found` → 补 `local.properties` 的 `sdk.dir` 或导出 `ANDROID_SDK_ROOT`。
  - 提示 JDK 版本过低 → 安装 JDK 17（Gradle 8.4 需 JDK 17+）。
  - `./gradlew: Permission denied` → `chmod +x gradlew`。
  - 首次构建慢（下载 Gradle 发行版与依赖）→ 已内置 Aliyun 镜像，见第 4 节。
- **GitHub Actions**：脚本 `/.github/workflows/build-apk.yml`，触发方式：push 到 `main`/`master`、PR、手动 `workflow_dispatch`（仓库 Actions 页面点 Run workflow）。流程 = setup-java 17 → setup-android → sdkmanager 装 34 → `./gradlew :app:assembleDebug` → upload-artifact 产出 `app-debug`。

## 4. 依赖与镜像

主要依赖来源：Google Maven（AGP、AndroidX）、Maven Central（ZXing、JourneyApps）。
获取优先级：**本地缓存 → 国内镜像 → 官方源 → 手动下载**。

已在 `settings.gradle.kts` 内置镜像（官方源兜底）：

| 官方源 | 镜像地址 |
|--------|----------|
| Google Maven | `https://maven.aliyun.com/repository/google` |
| JCenter | `https://maven.aliyun.com/repository/jcenter` |
| Maven Central | `https://maven.aliyun.com/repository/central` |
| Gradle 发行版 | `https://mirrors.cloud.tencent.com/gradle/` |

- Gradle 发行版镜像用法：把 `gradle/wrapper/gradle-wrapper.properties` 中 `distributionUrl` 改为 `https://mirrors.cloud.tencent.com/gradle/gradle-8.4-bin.zip`。
- 涉及 GitHub 资源下载失败（如 README 外链、Actions 拉包等）时，按顺序尝试以下 GitHub 加速前缀（可用性随时间变化，逐个试）：
  1. `https://ghproxy.com/`
  2. `https://mirror.ghproxy.com/`
  3. `https://gh-proxy.com/`
  4. `https://ghfast.top/`
  5. `https://gh.llkk.cc/`
  6. `https://ghproxy.net/`
  7. `https://gitclone.com/github.com/`
  8. `https://github.moeyy.xyz/`
  9. `https://raw.gitmirror.com/`
  10. `https://gh.ddlc.top/`
  11. `https://cors.isteed.cc/`
  12. `https://hub.gitmirror.com/`
- 需手动下载的场景（如代理都失效）：直接去对应官方站下载 `.aar`/`.pom` 放入本地仓库或按报错路径归档。本项目无必须手动下载的依赖。

## 5. 本地依赖服务

**不需要。** 纯本地 App，无后端、数据库、Redis、地图等外部服务；持久化用 SharedPreferences，导出走 MediaStore/本地文件。

## 6. 开发进度

- **已完成**：HTML → 原生安卓重构；扫码自动记录；单独/一键复制；格式记忆；高清拼图存相册；ZIP 打包；存档记录（30 条）；图标生成。
- **进行中**：无。
- **已搁置**：无。
- **最近可运行的 commit**：`a2a19bc`（原 HTML 版）；本次安卓重构为本机未提交改动，已构建验证（`BUILD SUCCESSFUL`）。

## 7. 待开发内容

| 功能 | 优先级 | 预估工时 | 前置依赖 |
|------|--------|----------|----------|
| 条码颜色自定义（当前仅 5 个预设色）| P2 | 0.5d | 无 |
| 双轨/扩展 Pharmacode 变体支持 | P3 | 1d | 扫码真机验证 |
| 条码内容合法性即时提示细化（校验位、长度）| P2 | 0.5d | 无 |
| 单测/UI 测试覆盖核心流程 | P1 | 2d | 测试框架接入 |
| Release 签名与混淆（ProGuard）配置 | P1 | 0.5d | keystore 由负责人提供 |
| 大列表 Bitmap 内存回收优化 | P2 | 0.5d | 无 |
| 接入 MediaStore 导出目录选择/批量分享 | P3 | 1d | 无 |

## 8. 架构与关键模块

- **模式**：单 Activity + 分层 package（无 ViewModel/协程，用线程池 + Handler 后台渲染）。
- **模块划分**（`app/src/main/java/com/barcode/factory/`）：
  - `codec/`：`BarcodeFormatSpec.kt`（7 种格式枚举）、`BarcodeGenerator.kt`（ZXing 编码 5 种 + 自移植 Pharmacode/MSI，画布渲染条码图）。
  - `data/`：`AppPrefs.kt`（SharedPreferences 持久化：上次格式/颜色/列数/草稿/存档）、`Archive.kt`、`ColorOption.kt`。
  - `ui/`：`MainActivity.kt`（入口 + 全部交互逻辑）、`BarcodeAdapter.kt`、`ArchiveAdapter.kt`。
  - `util/`：`FileExporter.kt`（保存图片到相册、ZIP 到下载，含 API 29 前后两套逻辑）。
- **核心入口**：`com.barcode.factory.ui.MainActivity`（`launchable-activity`）。
- **前后端对接点**：无后端。
- **已知技术债**：`MainActivity` 职责偏重；自定义 Pharmacode/MSI 未经过大量真实扫码器验证；无自动化测试。

## 9. 代码概览与已知问题

**结构**：`app/src/main/res/layout/`（主界面 + 2 个 item 布局）、`res/drawable`（矢量图标与背景）、`res/mipmap-*`（已生成图标）、`AndroidManifest.xml`（CAMERA + 低版本存储权限）。

**致命/严重问题**：无致命问题。仅提示：
- 严重：自定义 `Pharmacode`/`MSI` 编码逻辑（`BarcodeGenerator.kt` 内移植自 JsBarcode）未在真实扫码器上验证，若用于生产扫码场景需先行验证。修复方向：真机多款 App 扫码验证，必要时核对 JsBarcode 官方行为。

## 10. 架构简评

- **架构模式判断**：经典 Android 分层（Activity 即 Controller + ViewBinding + 静态工具类），可归为「良好」。
- **突出问题与改进方向**：
  1. `MainActivity` 承担输入/渲染/导出/存档全部逻辑，建议拆出 ViewModel + LiveData/Flow，UI 逻辑用协程。
  2. 预览图列表每次全量重绘且未回收 Bitmap，长列表存在内存风险，建议 DiffUtil + 图片回收/缓存。
  3. 缺自动化测试（单测/仪器测试），核心编码逻辑（ZXing 包装 + 自定义编码）值得优先补测。
  4. 无 Release 签名与混淆配置，上架前需补齐（含 keystore 管理）。
  5. 资源存在少量未使用的图标/文案（如 `ic_arrow_back.xml`、`bg_dock.xml`），可清理。

## 11. 测试建议

- **核心流程能否走通**：能。本机已构建出 APK；输入数据 → 实时预览 → 复制 → 导出链路代码完整。
- **重点测试模块/场景**：
  - 多行输入 → 网格预览数量/序号正确。
  - 切换格式 → 重启后仍记住上次格式（验证持久化）。
  - 扫码 → 结果自动追加为一条记录（需真机相机）。
  - 单独复制某条 / 「一键复制」全部。
  - 高清拼图保存到相册（Android 10+ MediaStore 路径；Android 9 及以下需授权存储）。
  - ZIP 打包保存到下载目录。
  - 存档：保存 → 抽屉恢复 → 删除。
- **已知/可能的边界问题**：
  - 非数字或长度不符输入（如 EAN-13 少于 12 位、Pharmacode 不在 3~131070）会显示"格式错误"，需确认提示文案友好。
  - 大量条码（>100）时预览刷新可能卡顿（内存/主线程）。
  - Android 9 及以下保存导出文件依赖存储权限，未授权时会提示失败。

## 12. 账号与密钥

- 本项目**无任何后端调用**，不需要 API Key / 密钥。
- 仅需系统权限：`CAMERA`（扫码）、`WRITE_EXTERNAL_STORAGE`（仅 Android 9 及以下保存导出文件）。
- 若将来接入第三方服务，Key 一律走环境变量或 `local.properties`（占位符 `<YOUR_API_KEY>`），禁止硬编码入库。

## 13. 常见问题

- **`SDK location not found`**：按第 3 节配置 `local.properties` 或 `ANDROID_SDK_ROOT`。
- **`Could not find com.android.tools.build:gradle:8.3.2`**：网络拉不到 Google Maven，已内置 Aliyun 镜像；仍失败则手动下载 AGP 包到本地仓库。
- **`Unsupported class file major version`**：JDK 版本过高/过低，使用 JDK 17。
- **首次构建极慢**：Gradle 发行版 + 依赖首次下载，可用 Tencent Gradle 镜像（见第 4 节）并保留本地缓存。
- **真机装不上/扫码黑屏**：确认授予相机权限（App 内已请求，拒绝后需到系统设置开启）。
- **导出文件找不到**：Android 10+ 在「相册 / BarcodeFactory」「下载 / BarcodeFactory」目录；Android 9- 需先允许存储权限。

## 14. 验收标准

1. `./gradlew :app:assembleDebug` 在本机成功产出 `app-debug.apk`（本环境已验证：`BUILD SUCCESSFUL in 2m 44s`）。
2. 安装 APK 能启动到首页，输入数据出现实时条码预览。
3. 将源码（含 `.github/workflows/build-apk.yml`）push 到远程后，GitHub Actions 自动构建出 `app-debug` 可下载 artifact。
4. 满足以上即视为接管成功。
