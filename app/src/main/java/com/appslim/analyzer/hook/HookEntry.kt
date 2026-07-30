package com.appslim.analyzer.hook

import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File

/**
 * LSPosed Hook 入口（报告 §9 Hook 系统 / §13.3 代码示例）
 *
 * Hook 点：
 *  - Activity：Instrumentation.execStartActivity()  —— 阻止页面跳转
 *  - Service ：startService() / bindService()        —— 阻止后台服务
 *  - Provider：ContentProvider.attachInfo()          —— 阻止 SDK 初始化
 *
 * 稳定性（报告 §14.2）：白名单机制 —— 核心组件永不阻断，防止闪退与登录失败。
 */
class HookEntry : IXposedHookLoadPackage {

    private lateinit var rules: Rules
    private lateinit var whitelist: Set<String>

    data class Rules(
        val blockActivity: Set<String>,
        val blockService: Set<String>,
        val blockProvider: Set<String>
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        rules = loadRules(lpparam.packageName) ?: return   // 无规则 = 不干预
        whitelist = loadWhitelist(lpparam.packageName)
        hookActivity(lpparam)
        hookService(lpparam)
        hookProvider(lpparam)
        XposedBridge.log("AppSlim: hooked ${lpparam.packageName}, " +
                "A=${rules.blockActivity.size} S=${rules.blockService.size} P=${rules.blockProvider.size}")
    }

    /* ---- Activity：阻止页面跳转（报告 §9.1） ---- */
    private fun hookActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation",
            lpparam.classLoader,
            "execStartActivity",
            android.content.Context::class.java,
            android.os.IBinder::class.java,
            android.os.IBinder::class.java,
            android.app.Activity::class.java,
            Intent::class.java,
            Int::class.javaPrimitiveType,
            android.os.Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args[4] as? Intent ?: return
                    val cls = intent.component?.className ?: return
                    if (shouldBlock(cls, rules.blockActivity)) {
                        XposedBridge.log("AppSlim: block Activity $cls")
                        param.result = null // 阻断跳转
                    }
                }
            }
        )
    }

    /* ---- Service：阻止后台服务拉起（报告 §9.2） ---- */
    private fun hookService(lpparam: XC_LoadPackage.LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                val cls = intent.component?.className ?: return
                if (shouldBlock(cls, rules.blockService)) {
                    XposedBridge.log("AppSlim: block Service $cls")
                    param.result = null
                }
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl", lpparam.classLoader,
                "startService", Intent::class.java, hook
            )
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl", lpparam.classLoader,
                "bindService", Intent::class.java,
                "android.content.ServiceConnection",
                Int::class.javaPrimitiveType, hook
            )
        }.onFailure { XposedBridge.log("AppSlim: service hook fallback - ${it.message}") }
    }

    /* ---- Provider：阻止 SDK 自动初始化（报告 §9.3） ---- */
    private fun hookProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.content.ContentProvider",
                lpparam.classLoader,
                "attachInfo",
                android.content.Context::class.java,
                "android.content.pm.ProviderInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cls = param.thisObject.javaClass.name
                        if (shouldBlock(cls, rules.blockProvider)) {
                            XposedBridge.log("AppSlim: block Provider $cls")
                            param.result = null // attachInfo 被跳过 = SDK 初始化失败
                        }
                    }
                }
            )
        }.onFailure { XposedBridge.log("AppSlim: provider hook failed - ${it.message}") }
    }

    /** 白名单优先（报告 §14.2），其次精确匹配 + 前缀匹配 */
    private fun shouldBlock(cls: String, blockSet: Set<String>): Boolean {
        if (cls in whitelist) return false
        if (cls in blockSet) return true
        return blockSet.any { cls.startsWith(it) }
    }

    private fun loadRules(pkg: String): Rules? = runCatching {
        val f = File("/data/misc/appslim/$pkg.json")
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        fun set(key: String) = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).map { a.getString(it) }.toSet()
        } ?: emptySet()
        Rules(set("blockActivity"), set("blockService"), set("blockProvider"))
    }.getOrNull()

    private fun loadWhitelist(pkg: String): Set<String> = runCatching {
        val f = File("/data/misc/appslim/$pkg.whitelist.json")
        if (!f.exists()) return@runCatching emptySet()
        val a = JSONObject(f.readText()).getJSONArray("core")
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())
}
