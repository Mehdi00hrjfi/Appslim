<h1 align="center">AppSlim Analyzer</h1>

<h3 align="center">Android App Runtime Analysis & Smart Slimming Platform</h3>

<p align="center">
  Languages:
  <a href="../README.md">简体中文</a> ·
  <a href="./README_en.md">English</a>
</p>

---

> Instead of blindly deleting APKs, AppSlim Analyzer inspects an app's **real behavior at runtime**, identifies low-value components, and slims it down through hooks, rules, and dynamic strategies.

## ✨ Features

- 🚀 **One-tap Detection (launch behavior analysis)**: Launch the target app, keep it in the foreground for 10 seconds, sample every 500ms, and record which Activities were started plus CPU / memory usage of each component and process.
- 📋 **Activity Enumeration**: Statically enumerate all Activities of the target app (with `exported` / `launcher` flags).
- 🔍 **Dex Behavior Scan**: Scan the target APK's Dex to spot system-capability calls and SDK fingerprints.
- 📊 **Runtime Monitoring**: A foreground service samples process CPU / memory / network / WakeLock (via UsageStats).
- 🪝 **LSPosed Module**: This app is itself an Xposed module and can block specific Activities / Services / Providers by rule.
- 🔐 **Tiered Permission Support**: Normal mode (UsageStats) / ADB / Root (Magisk, KernelSU), with automatic downgrade.
- 💥 **Crash Logging**: Globally captures uncaught exceptions to disk for fast diagnosis.

## 🛠 Tech Stack

| Layer | Choice |
|---|---|
| UI | Kotlin + Jetpack Compose (Material3) |
| Architecture | ViewModel + StateFlow + Navigation |
| Persistence | Room |
| Async | Kotlin Coroutines |
| Root | Magisk / KernelSU (`RootShell`) |
| Non-root | Shizuku |
| Hook | LSPosed (this app is an Xposed module) |

## 📦 Build & Install

### Requirements

- Android Studio Hedgehog or newer
- JDK 17
- Android SDK (`compileSdk` / `targetSdk` = 34, `minSdk` = 29 → Android 10+)

### Build

```bash
git clone https://github.com/Horizen5/Appslim.git
cd Appslim
./gradlew assembleRelease
```

> Note: `assembleRelease` signs with the bundled `keystore.jks`, which is **excluded from this repository**. Provide your own signing key.

### Install

1. Enable "Install unknown apps" on your device.
2. Install `app/build/outputs/apk/release/app-release.apk`.
3. Open the app, go to any app's detail page, and tap **一键检测 (One-tap Detection)**.

### Permission note

- **Usage Access**: Before the first detection, tap "去开启使用情况访问 (open usage access)" inside the app and grant the permission in system settings; otherwise the target app's foreground Activity cannot be read (CPU / memory still work in normal mode).

## 📂 Project Structure (excerpt)

```
app/src/main/java/com/appslim/analyzer/
├── monitor/        # Runtime data collection (MonitorService, etc.)
├── analysis/       # Risk scoring / SDK identification / dependency analysis
├── lens/           # One-tap detection: Activity enumeration + Dex scan + launch sampling
│   ├── launcher/   # ProfileService (foreground service) / ApplensSampler / Shell
│   ├── collector/  # CPU / memory / process collectors
│   └── scanner/    # Activity enumeration / Dex analysis / package scan
├── hook/           # LSPosed hook entry
├── root/           # RootShell / Shizuku bridge
├── rules/          # rules.json generation
└── ui/             # Compose UI & ViewModel
```

## ⚠️ Disclaimer

This project is for learning and researching Android runtime behavior analysis only. Do not use it to violate others' privacy or any vendor's terms of service.

## 📄 License

For learning and research purposes only. All rights reserved.
