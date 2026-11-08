package com.example.phoneperf.data

/**
 * 系统属性读取。
 *
 * android.os.SystemProperties 是隐藏 API，targetSdk 28+ 反射会被拦截；
 * 这里改为执行一次 `getprop`（无需 root，所有机型可用），
 * 解析全部属性后缓存，避免每次刷新都起进程。
 */
object SystemProps {

    /** [key] -> value，惰性加载一次。 */
    private val all: Map<String, String> by lazy { loadAll() }

    fun get(key: String): String? = all[key]?.takeIf { it.isNotBlank() }

    /** 从一组候选 key 中取第一个非空值。 */
    fun firstOf(vararg keys: String): String? {
        for (k in keys) get(k)?.let { return it }
        return null
    }

    private fun loadAll(): Map<String, String> = try {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        val map = HashMap<String, String>(256)
        // 输出格式：[ro.product.model]: [Xiaomi 14]
        val regex = Regex("^\\[(.+?)]:\\s*\\[(.*)]$")
        text.lineSequence().forEach { line ->
            val m = regex.find(line.trim()) ?: return@forEach
            map[m.groupValues[1]] = m.groupValues[2]
        }
        map
    } catch (e: Exception) {
        emptyMap()
    }
}
