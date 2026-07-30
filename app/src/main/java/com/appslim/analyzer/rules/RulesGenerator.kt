package com.appslim.analyzer.rules

import com.appslim.analyzer.data.model.BlockRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 自动生成模块（报告 §10）
 *
 * 分析结果 → rules.json → AppSlim_Module.zip
 *   ├── lsposed/   （Hook 模块 APK 占位 + xposed_init 说明）
 *   ├── magisk/    （module.prop / service.sh）
 *   └── config/rules.json
 */
object RulesGenerator {

    /** 生成 rules.json（与报告 §10 示例格式一致） */
    fun toJson(rules: BlockRules): String = JSONObject().apply {
        put("package", rules.targetPackage)
        put("version", 3)
        put("generated", System.currentTimeMillis())
        put("blockActivity", JSONArray(rules.blockActivity))
        put("blockService", JSONArray(rules.blockService))
        put("blockProvider", JSONArray(rules.blockProvider))
        put("blockReceiver", JSONArray(rules.blockReceiver))
    }.toString(2)

    fun parse(json: String): BlockRules {
        val o = JSONObject(json)
        fun arr(key: String) = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).map { a.getString(it) }
        } ?: emptyList()
        return BlockRules(
            targetPackage = o.getString("package"),
            blockActivity = arr("blockActivity"),
            blockService = arr("blockService"),
            blockProvider = arr("blockProvider"),
            blockReceiver = arr("blockReceiver")
        )
    }

    /**
     * 打包 AppSlim_Module.zip（报告 §10 输出物）
     * @param outZip 输出文件，如 File(context.getExternalFilesDir(null), "AppSlim_Module.zip")
     */
    fun packModule(rules: BlockRules, outZip: File): File {
        return packModules(mapOf(rules.targetPackage to
            (rules.blockActivity + rules.blockService + rules.blockProvider + rules.blockReceiver)), outZip)
    }

    /**
     * 打包多应用规则（总览页一键生成）
     * @param byPackage 包名 → 阻断类名列表
     */
    fun packModules(byPackage: Map<String, List<String>>, outZip: File): File {
        val rulesList = byPackage.map { (pkg, classes) ->
            BlockRules(
                targetPackage = pkg,
                blockActivity = emptyList(),
                blockService = classes,
                blockProvider = emptyList(),
                blockReceiver = emptyList()
            )
        }
        val rulesJson = toJsonMulti(rulesList)
        val total = byPackage.values.sumOf { it.size }
        val moduleProp = """
            id=appslim
            name=AppSlim Slim Rules - ${byPackage.size} apps
            version=v1.0
            versionCode=1
            author=AppSlim Analyzer
            description=Auto-generated slim rules. Blocks $total components across ${byPackage.size} packages.
        """.trimIndent()
        val serviceSh = """
            #!/system/bin/sh
            # AppSlim: 开机注入阻断规则（Magisk service.d）
            MODDIR=${'$'}{0%/*}
            mkdir -p /data/misc/appslim
            cp ${'$'}MODDIR/config/rules.json /data/misc/appslim/appslim_rules.json
        """.trimIndent()

        ZipOutputStream(BufferedOutputStream(outZip.outputStream())).use { z ->
            fun put(path: String, content: String) {
                z.putNextEntry(ZipEntry(path))
                z.write(content.toByteArray())
                z.closeEntry()
            }
            put("config/rules.json", rulesJson)
            put("magisk/module.prop", moduleProp)
            put("magisk/service.sh", serviceSh)
            put("lsposed/README.txt",
                "安装主应用 AppSlim Analyzer 并在 LSPosed 中启用对应应用作用域\n" +
                "规则文件已内置于 config/rules.json，共 $total 条阻断规则")
        }
        return outZip
    }

    /** 多应用 rules.json：顶层为 JSON 数组，每个元素对应一个应用的规则 */
    fun toJsonMulti(rulesList: List<BlockRules>): String = JSONArray().apply {
        rulesList.forEach { put(JSONObject(toJson(it))) }
    }.toString(2)
}
