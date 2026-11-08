package com.example.phoneperf.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.RandomAccessFile

/** 内存 / 存储数据采集。 */
object MemoryReader {

    data class MemInfo(
        val totalBytes: Long,
        val availableBytes: Long,
        val thresholdBytes: Long,
        val lowMemory: Boolean
    ) {
        val usedBytes: Long get() = totalBytes - availableBytes
        val fraction: Float get() =
            if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    fun read(context: Context): MemInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return MemInfo(
            totalBytes = mi.totalMem,
            availableBytes = mi.availMem,
            thresholdBytes = mi.threshold,
            lowMemory = mi.lowMemory
        )
    }

    /** 解析 /proc/meminfo 的额外字段（Swap、Cached 等）。 */
    fun extraMemInfo(): Map<String, Long> {
        val text = SysFs.readText("/proc/meminfo") ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        text.lineSequence().forEach { line ->
            val parts = line.split(Regex(":\\s+"))
            if (parts.size == 2) {
                val kb = parts[1].removeSuffix("kB").trim().toLongOrNull()
                if (kb != null) result[parts[0]] = kb * 1024
            }
        }
        return result
    }

    fun swap(): Pair<Long, Long> {
        val text = SysFs.readText("/proc/swaps") ?: return 0L to 0L
        var used = 0L
        var total = 0L
        text.lineSequence().drop(1).forEach { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size >= 4) {
                total += (p[2].toLongOrNull() ?: 0L) * 1024
                used += (p[3].toLongOrNull() ?: 0L) * 1024
            }
        }
        return used to total
    }

    /** 本应用自身占用的 PSS（KB）。 */
    fun appPssKb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
            .firstOrNull()?.let { return it.totalPss.toLong() }
        return 0L
    }

    data class StorageInfo(
        val internalTotal: Long,
        val internalFree: Long,
        val externalTotal: Long,
        val externalFree: Long
    )

    fun storage(context: Context): StorageInfo {
        val internal = try {
            val stat = android.os.StatFs(context.filesDir.absolutePath)
            stat.blockCountLong * stat.blockSizeLong to stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L to 0L
        }
        val external = try {
            context.getExternalFilesDir(null)?.let { dir ->
                val stat = android.os.StatFs(dir.absolutePath)
                stat.blockCountLong * stat.blockSizeLong to stat.availableBlocksLong * stat.blockSizeLong
            } ?: (0L to 0L)
        } catch (e: Exception) {
            0L to 0L
        }
        return StorageInfo(internal.first, internal.second, external.first, external.second)
    }

    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
