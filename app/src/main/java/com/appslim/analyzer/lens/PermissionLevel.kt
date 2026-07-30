package com.appslim.analyzer.lens

/** 三级权限模型（见 v0.5.0 规格 §4） */
enum class PermissionLevel(val label: String, val desc: String) {
    /** 普通模式：仅能看应用/Activity、启动、读自身 /proc */
    NORMAL("普通模式", "数据有限 · 仅本应用采样"),

    /** ADB 增强：无线调试/adb 下可执行 dumpsys */
    ADB("ADB 增强模式", "可读取 dumpsys cpuinfo / meminfo"),

    /** Root 完整：Magisk / KernelSU，可读任意 /proc/[pid]/stat */
    ROOT("Root 完整模式", "可读取任意进程 CPU / 内存 / IO / 线程");
}
