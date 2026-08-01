<h1 align="center">AppSlim Analyzer</h1>

<h3 align="center">Android 应用运行时分析与智能精简平台</h3>

<p align="center">
  Languages:
  <a href="./README.md">简体中文</a> ·
  <a href="./docs/README_en.md">English</a>
</p>

<p align="center">
  <a href="https://github.com/Horizen5/Appslim/releases/latest"><b>⬇ 下载最新版 APK</b></a>
</p>

<p align="center">
  <a href="https://github.com/Horizen5/Appslim/releases/latest"><img src="https://img.shields.io/github/v/release/Horizen5/Appslim?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC" alt="最新版本"></a>
  <a href="https://github.com/Horizen5/Appslim/releases"><img src="https://img.shields.io/github/downloads/Horizen5/Appslim/total?label=%E4%B8%8B%E8%BD%BD%E9%87%8F" alt="下载量"></a>
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10+">
</p>

---

> 不通过盲目删除 APK，而是在应用**运行过程中**分析其真实行为，识别无价值模块，并通过 Hook、规则和动态策略实现轻量化。

## ✨ 功能特性

- 🚀 **一键检测（启动行为分析）**：点一次即自动打开目标 App，前台运行 10 秒、每 500ms 采样一次，记录启动了哪些 Activity 及各进程 CPU / 内存占用，**检测完自动返回本应用并直接展示结果**（需授予「显示在其他应用上层」权限才能自动返回；不授予也可点通知返回）。结果高亮「占用最高」的 Activity / 进程，可一键返回详情页管理（阻断 / 一键优化）。
- 📋 **Activity 枚举**：静态枚举目标应用的所有 Activity（含 `exported` / `launcher` 标记）。
- 🔍 **Dex 行为扫描**：扫描 APK 的 Dex，识别系统能力调用与 SDK 特征。
- 📊 **运行时监控**：前台服务采集进程 CPU / 内存 / 网络 / WakeLock（基于 UsageStats）。
- 🪝 **LSPosed 模块**：本应用本身即 Xposed 模块，可按规则阻断指定 Activity / Service / Provider。
- 🔐 **多级权限适配**：普通模式（UsageStats）/ ADB / Root（Magisk、KernelSU）三级，自动降级。
- 💥 **崩溃日志**：全局捕获未处理异常并落盘，便于快速定位问题。
- 🔋 **后台自动降频（v0.5.8）**：应用退到后台即暂停所有采样协程（前台检测服务除外），前台刷新间隔由 500ms 降至 2s，避免持续占用 CPU 核心。
- 🛡 **IFW + PM 双层拦截（v0.6.1）**：冻结真正落地到系统（不只是生成配置），Intent Firewall 层让应用自己改不回来；行为特征分类 + 冻结前提示「关了会怎样」。
- 📑 **应用列表秒开（v0.6.2）**：MT Manager 式架构——Room 缓存避免重复扫描、图标只解可见项、智能排序（名称 / 可瘦身 / 最近更新 / 最近使用），首屏即时出现，深度分析仍只在点击时做。

## 🛠 技术栈

| 层 | 选型 |
|---|---|
| UI | Kotlin + Jetpack Compose（Material3） |
| 架构 | ViewModel + StateFlow + Navigation |
| 持久化 | Room |
| 异步 | Kotlin Coroutines |
| Root 模式 | Magisk / KernelSU（`RootShell`） |
| 非 Root 模式 | Shizuku |
| Hook | LSPosed（本应用即 Xposed 模块） |

## 🔬 实现原理（检测用的是哪套代码）

「一键检测」（启动行为分析）的实现**直接移植自本仓库作者的另一个项目 [APPLENS](https://github.com/Horizen5/APPLENS)**（`ActivitySampler` + `SamplingService`），并落地为以下模块：

- **前台 Activity 识别**：用 `UsageStatsManager.queryEvents` 读取最近进入前台的 Activity（普通模式即可，只需授予「使用情况访问」权限），取不到再用 `dumpsys activity activities` 兜底；不依赖跨 UID 受限的 `dumpsys`。
- **采样节奏**：每 500ms 采样一次、持续 10s（共 20 个采样点）。
- **CPU / 内存**：`top -b -n 1` 与 `dumpsys meminfo`（有 root 时走 `su`）。
- **保活**：检测在**前台 Service（`ProfileService`）+ `PARTIAL_WAKE_LOCK`** 中执行，启动目标 App 后本应用退到后台也不被杀。
- **规避 HyperOS 限制**：不扫描 `/proc`，也不在后台强行拉起自身 Activity（避免后台启动限制导致闪退）。

关键源码位置：

| 文件 | 作用 |
|---|---|
| `app/.../lens/launcher/ProfileService.kt` | 前台检测服务（移植自 APPLENS `SamplingService`） |
| `app/.../lens/launcher/ApplensSampler.kt` | 采样器（移植自 APPLENS `ActivitySampler`） |
| `app/.../lens/launcher/ApplensShell.kt` | Shell 执行（移植自 APPLENS `ShellUtils`，优先 `su`） |
| `app/.../lens/launcher/ProfileSession.kt` | 服务 → ViewModel → UI 的状态桥接 |
| `app/.../util/CrashLogger.kt` | 全局异常捕获，落盘到 `files/crash/crash.log` |
| `app/.../monitor/MonitorService.kt` | 运行时组件监控（Activity / Service / CPU / 网络） |
| `app/.../hook/HookEntry.kt` | LSPosed Hook 入口（本应用即 Xposed 模块） |

## 📦 构建与安装

### 构建方式

本项目使用 Gradle（AGP 8.5.2 / Kotlin 2.0.20 / Compose BOM 2024.09.00），无需额外安装 Gradle，仓库根目录已包含 Gradle Wrapper。

```bash
# 1) 克隆
git clone https://github.com/Horizen5/Appslim.git
cd Appslim

# 2) 打 Release 包（输出：app/build/outputs/apk/release/app-release.apk）
./gradlew assembleRelease

# 或打 Debug 包
./gradlew assembleDebug
```

> 说明：`assembleRelease` 会使用项目内置的 `keystore.jks` 签名，该文件**已排除在仓库之外**。
> 若要自行签名，请把你的密钥放到项目根目录的 `keystore.jks`，或在 `app/build.gradle.kts` 的 `signingConfigs` 中修改路径与密码。

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK（`compileSdk` / `targetSdk` = 34，`minSdk` = 29，即 Android 10+）

### 安装

1. 在手机上开启「未知来源应用」安装权限。
2. 安装上一步生成的 `app/build/outputs/apk/release/app-release.apk`（或从 [Releases](../../releases) 下载已构建好的 APK）。
3. 打开 App，进入任意应用详情页，点击「一键检测」即可。

### 权限提示

- **使用情况访问**：首次检测前请在应用内点击「去开启使用情况访问」，并在系统设置中授予权限；否则无法读取目标 App 的前台 Activity（普通模式下 CPU / 内存仍可用）。

## 📂 项目结构（节选）

```
app/src/main/java/com/appslim/analyzer/
├── monitor/        # 运行时数据采集（MonitorService 等）
├── analysis/       # 风险评分 / SDK 识别 / 依赖分析
├── lens/           # 一键检测：Activity 枚举 + Dex 扫描 + 启动行为采样
│   ├── launcher/   # ProfileService（前台服务）/ ApplensSampler / Shell
│   ├── collector/  # CPU / 内存 / 进程采集
│   └── scanner/    # Activity 枚举 / Dex 分析 / 包扫描
├── hook/           # LSPosed Hook 入口
├── root/           # RootShell / Shizuku 通道
├── rules/          # rules.json 生成
└── ui/             # Compose 界面与 ViewModel
```

## 📌 更新日志

### v0.6.3 — 列表缓存一致性：广播实时层
- 新增 `PackageWatcher`：动态注册 `PACKAGE_ADDED / REMOVED / REPLACED` 广播，运行中装/卸 App 立即刷新列表。
- 与「启动即 `getInstalledApplications` 全量同步」互为兜底：重新打开不漏新装 App，运行中也不 stale。
- 动态注册（Application 上下文）不受 Android 8+ 后台静态广播限制影响。

### v0.6.2 — 应用列表模块重构（MT Manager 式）
- **快列表**：`getInstalledApplications(0)` 零额外 flag 出列表，不碰组件、不解码图标，100–300ms 可见。
- **Room 缓存**：新增 `app_cache` 表，二次启动只富化「新装 / 更新」的应用，不再每次重扫。
- **异步图标**：`IconCache`（LruCache 内存）只在卡片可见时解码，滚回来直接命中，不再一次性解码全部。
- **智能排序**：名称 / 可瘦身（后台组件优先）/ 最近更新 / 最近使用（基于 UsageStats）。
- 深度分析（点击才 `ManifestScanner` 全量探针）保持不变。
- 详见 [`dev-craft`](https://github.com/Horizen5/dev-craft) 仓库的《让 Android 应用列表秒开》一篇。

### v0.6.1 — IFW + PM 双层拦截
- 冻结真正落地到系统（非仅生成配置）；Intent Firewall 层防止应用自恢复。
- 行为特征分类、冻结前提示「关了会怎样」。

### v0.6.0
- 行为分类器、安全冻结判定（桌面入口 / 前台服务 / 深链三项否决）、一键优化只挑 `safeToBlock`。

## ⚠️ 免责声明

本项目仅供学习与研究 Android 运行时行为分析之用，请勿用于侵犯他人隐私或违反厂商服务条款的场景。

## 📄 许可证

本项目仅供学习与研究使用，保留所有权利。
