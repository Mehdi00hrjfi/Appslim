# LSPosed Hook 入口必须保留，防止被混淆/移除（xposed_init 通过字符串名加载）
-keep class com.appslim.analyzer.hook.** { *; }
# Xposed 接口（compileOnly，保留名称以稳定 Hook）
-keep class de.robv.android.xposed.** { *; }
# Shizuku 通过反射调用 newProcess，需保留该类与方法
-keep class rikka.shizuku.Shizuku { *; }
# Room 数据库
-keepnames class * extends androidx.room.RoomDatabase
# 仅做 Tree-Shaking 移除未用图标/库，不改名，保证反射按字符串名调用安全
-dontobfuscate
