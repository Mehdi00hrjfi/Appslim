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
- 🔋 **Background Auto-Throttle (v0.5.8)**: When the app moves to the background, all sampling coroutines pause (except the foreground detection service); foreground refresh interval is lowered from 500ms to 2s, so it no longer pins a CPU core.

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

## 🔬 How It Works (which code does the detection use)

The **One-tap Detection** (launch behavior analysis) is **ported directly from the author's other project [APPLENS](https://github.com/Horizen5/APPLENS)** (`ActivitySampler` + `SamplingService`), and lands as these modules:

- **Foreground Activity detection**: reads the most-recently-foregrounded Activity via `UsageStatsManager.queryEvents` (works in normal mode with only the "Usage Access" permission granted), falling back to `dumpsys activity activities` when unavailable; it does **not** rely on cross-UID-restricted `dumpsys`.
- **Sampling cadence**: one sample every 500ms for 10s (20 sampling points in total).
- **CPU / memory**: `top -b -n 1` and `dumpsys meminfo` (routed through `su` when root is available).
- **Staying alive**: detection runs inside a **foreground Service (`ProfileService`) + `PARTIAL_WAKE_LOCK`**, so our app is not killed after launching the target and moving to the background.
- **HyperOS compatibility**: it does not scan `/proc` nor force-launch its own Activity from the background (avoiding background-launch restrictions that cause crashes).

Key source locations:

| File | Role |
|---|---|
| `app/.../lens/launcher/ProfileService.kt` | Foreground detection service (ported from APPLENS `SamplingService`) |
| `app/.../lens/launcher/ApplensSampler.kt` | Sampler (ported from APPLENS `ActivitySampler`) |
| `app/.../lens/launcher/ApplensShell.kt` | Shell execution (ported from APPLENS `ShellUtils`, prefers `su`) |
| `app/.../lens/launcher/ProfileSession.kt` | State bridge: Service → ViewModel → UI |
| `app/.../util/CrashLogger.kt` | Global exception capture, written to `files/crash/crash.log` |
| `app/.../monitor/MonitorService.kt` | Runtime component monitoring (Activity / Service / CPU / network) |
| `app/.../hook/HookEntry.kt` | LSPosed hook entry (this app is an Xposed module) |

## 📦 Build & Install

### How to build

This project uses Gradle (AGP 8.5.2 / Kotlin 2.0.20 / Compose BOM 2024.09.00). No separate Gradle install is needed — the Gradle Wrapper is committed at the repo root.

```bash
# 1) Clone
git clone https://github.com/Horizen5/Appslim.git
cd Appslim

# 2) Build the Release APK (output: app/build/outputs/apk/release/app-release.apk)
./gradlew assembleRelease

# Or build the Debug APK
./gradlew assembleDebug
```

> Note: `assembleRelease` signs with the bundled `keystore.jks`, which is **excluded from this repository**.
> To sign with your own key, place your keystore at `keystore.jks` in the repo root, or edit the path/password in `signingConfigs` inside `app/build.gradle.kts`.

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
2. Install `app/build/outputs/apk/release/app-release.apk` (or download a prebuilt APK from [Releases](../../releases)).
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
