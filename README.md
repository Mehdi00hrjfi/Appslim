<h1 align="center">AppSlim Analyzer</h1>

<h3 align="center">Android 应用运行时分析与智能精简平台</h3>

<p align="center">
  Languages:
  <a href="./README.md">简体中文</a> ·
  <a href="./docs/README_en.md">English</a>
</p>

---

> 不通过盲目删除 APK，而是在应用**运行过程中**分析其真实行为，识别无价值模块，并通过 Hook、规则和动态策略实现轻量化。

## ✨ 功能特性

- 🚀 **一键检测（启动行为分析）**：启动目标 App，前台运行 10 秒，每 500ms 采样一次，记录启动了哪些 Activity 以及各组件 / 进程的 CPU 与内存占用。
- 📋 **Activity 枚举**：静态枚举目标应用的所有 Activity（含 `exported` / `launcher` 标记）。
- 🔍 **Dex 行为扫描**：扫描 APK 的 Dex，识别系统能力调用与 SDK 特征。
- 📊 **运行时监控**：前台服务采集进程 CPU / 内存 / 网络 / WakeLock（基于 UsageStats）。
- 🪝 **LSPosed 模块**：本应用本身即 Xposed 模块，可按规则阻断指定 Activity / Service / Provider。
- 🔐 **多级权限适配**：普通模式（UsageStats）/ ADB / Root（Magisk、KernelSU）三级，自动降级。
- 💥 **崩溃日志**：全局捕获未处理异常并落盘，便于快速定位问题。

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

## 📦 构建与安装

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK（`compileSdk` / `targetSdk` = 34，`minSdk` = 29，即 Android 10+）

### 构建

```bash
git clone https://github.com/Horizen5/Appslim.git
cd Appslim
./gradlew assembleRelease
```

> 说明：`assembleRelease` 使用项目内置的 `keystore.jks` 签名，该文件**已排除在仓库之外**，请自行替换或配置你自己的签名密钥。

### 安装

1. 在手机上开启「未知来源应用」安装权限。
2. 安装 `app/build/outputs/apk/release/app-release.apk`。
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

## ⚠️ 免责声明

本项目仅供学习与研究 Android 运行时行为分析之用，请勿用于侵犯他人隐私或违反厂商服务条款的场景。

## 📄 许可证

本项目仅供学习与研究使用，保留所有权利。
