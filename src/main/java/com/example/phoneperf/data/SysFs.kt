package com.example.phoneperf.data

import java.io.File

/**
 * 通用工具：安全读取系统节点文件、格式化数值。
 *
 * Android 上大量性能数据（CPU 频率、温度、内存、电池）都以纯文本形式
 * 暴露在 /sys 与 /proc 下，直接读取比调用受限的隐藏 API 更通用。
 */
object SysFs {

    private const val TAG = "SysFs"

    /** 读取单行文本，失败返回 null。 */
    fun readText(path: String): String? = try {
        val f = File(path)
        if (f.exists() && f.canRead()) f.readText().trim() else null
    } catch (e: Exception) {
        null
    }

    /** 读取整数，支持形如 "1800000\n" 的内容。 */
    fun readLong(path: String): Long? = readText(path)?.trim()?.toLongOrNull()

    /** 读取整数，遇到 "N/A"、错误码等返回 null。 */
    fun readLongCsv(path: String): List<Long>? =
        readText(path)?.split(',')?.mapNotNull { it.trim().toLongOrNull() }

    /** 遍历目录，返回可读的文件绝对路径列表（已排序）。 */
    fun listFiles(dir: String, prefix: String = ""): List<String> = try {
        File(dir).listFiles()
            ?.filter { it.isFile && it.canRead() && it.name.startsWith(prefix) }
            ?.sortedBy { it.name }
            ?.map { it.absolutePath }
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** 从 /proc/cpuinfo 中解析硬件信息。 */
    fun parseCpuInfo(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val text = readText("/proc/cpuinfo") ?: return map
        // 只取第一颗核心的硬件字段，避免多核重复
        text.lineSequence()
            .map { it.trim() }
            .filter { it.contains(':') }
            .forEach { line ->
                val key = line.substringBefore(':').trim()
                val value = line.substringAfter(':').trim()
                if (value.isNotEmpty() && key !in map && key != "processor") {
                    map[key] = value
                }
            }
        return map
    }
}
