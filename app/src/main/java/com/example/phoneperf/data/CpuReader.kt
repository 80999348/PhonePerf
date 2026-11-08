package com.example.phoneperf.data

import android.os.Build
import android.os.SystemClock
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * CPU 数据采集。
 *
 * 频率/温度来自 /sys 下的 cpufreq 与 thermal 节点；部分厂商 ROM
 * 封锁了这些路径，此时会优雅降级为 "N/A"，不会崩溃。
 */
object CpuReader {

    /** 检测到的一组核心（同频策略下的核心簇）。 */
    data class Cluster(
        val index: Int,
        val cpus: List<Int>,
        val freqPaths: List<String>,
        val maxKhz: Long,
        val minKhz: Long,
        val curKhz: Long
    ) {
        val curGhz: Double get() = curKhz / 1_000_000.0
        val maxGhz: Double get() = maxKhz / 1_000_000.0
        val fraction: Float get() =
            if (maxKhz <= 0) 0f else (curKhz.toFloat() / maxKhz).coerceIn(0f, 1f)
    }

    data class ThermalZone(val name: String, val tempC: Float)

    fun clusterDirs(): List<File> {
        val base = File("/sys/devices/system/cpu/cpufreq")
        return base.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.sortedBy { it.name } ?: emptyList()
    }

    private fun readLong(file: File): Long? = try {
        if (file.exists() && file.canRead()) file.readText().trim().toLongOrNull() else null
    } catch (e: Exception) {
        null
    }

    fun readClusters(): List<Cluster> = clusterDirs().mapIndexedNotNull { i, dir ->
        try {
            val cpuList: List<Int> = listOf("affected_cpus", "related_cpus")
                .asSequence()
                .map { File(dir, it) }
                .firstOrNull { it.exists() && it.canRead() }
                ?.readText()
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.mapNotNull { it.toIntOrNull() }
                ?: emptyList()

            val freqFiles: List<File> = dir.listFiles()
                ?.filter { it.name == "scaling_cur_freq" || it.name == "cpuinfo_cur_freq" }
                ?.sortedBy { it.name }
                ?: emptyList()

            val maxKhz = readLong(File(dir, "cpuinfo_max_freq"))
                ?: readLong(File(dir, "scaling_max_freq")) ?: 0L
            val minKhz = readLong(File(dir, "cpuinfo_min_freq"))
                ?: readLong(File(dir, "scaling_min_freq")) ?: 0L
            val curKhz = (readLong(File(dir, "scaling_cur_freq"))
                ?: readLong(File(dir, "cpuinfo_cur_freq")) ?: 0L)

            Cluster(
                index = i,
                cpus = cpuList,
                freqPaths = freqFiles.map { it.absolutePath },
                maxKhz = maxKhz,
                minKhz = minKhz,
                curKhz = curKhz
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 单个核心当前频率（GHz），用于实时曲线。 */
    fun currentFreqsGhz(): List<Double> {
        val result = mutableListOf<Double>()
        for (cpu in 0 until Runtime.getRuntime().availableProcessors()) {
            val dir = File("/sys/devices/system/cpu/cpu$cpu/cpufreq")
            val khz = readLong(File(dir, "scaling_cur_freq")) ?: readLong(File(dir, "cpuinfo_cur_freq"))
            result.add(if (khz != null) khz / 1_000_000.0 else 0.0)
        }
        return result
    }

    fun maxFreqGhz(): Double {
        val clusters = readClusters()
        val maxKhz = clusters.maxOfOrNull { it.maxKhz } ?: 0L
        return if (maxKhz > 0) maxKhz / 1_000_000.0 else 0.0
    }

    /** 读取所有可用的温度传感器（thermal zone）。 */
    fun readThermalZones(): List<ThermalZone> {
        val dir = File("/sys/class/thermal")
        val files = dir.listFiles()?.filter {
            it.name.startsWith("thermal_zone") && it.isDirectory
        }?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 999 } ?: return emptyList()

        return files.mapNotNull { zone ->
            try {
                val tempRaw = File(zone, "temp").takeIf { it.canRead() }?.readText()?.trim()
                    ?.toLongOrNull() ?: return@mapNotNull null
                // 部分平台以毫摄氏度为单位
                val celsius = if (tempRaw > 1000) tempRaw / 1000f else tempRaw.toFloat()
                if (celsius <= 0f || celsius > 150f) return@mapNotNull null
                val name = File(zone, "type").takeIf { it.canRead() }?.readText()?.trim()
                    ?: zone.name
                ThermalZone(name, celsius)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * CPU 综合使用率采样器（无 root 可用）。
     *
     * 数据源按优先级：
     * 1. /proc/stat —— 传统方式；Android 9+ 对 targetSdk>=28 的应用已封禁，多数现代机型读不到；
     * 2. cpufreq 的 time_in_state —— 每个频率域核心的累计运行时间，
     *    两次采集的运行时间差 / 墙钟时间差 / 核心数 ≈ 真实使用率；
     * 3. 都不可用时返回 -1f，由界面显示 N/A（例如模拟器）。
     *
     * [sample] 需要跨轮询复用同一个实例（间隔 1~10 秒最准）。
     */
    class CpuLoadSampler {
        private var lastIdle = -1L
        private var lastTotal = -1L

        // time_in_state 累计值缓存（每 policy 一项）
        private var lastTis = -1L
        private var lastTisWallMs = -1L
        private var lastTisCores = 0

        /** 返回 0f~1f；无法测量时返回 -1f。 */
        fun sample(): Float {
            sampleProcStat()?.let { return it }
            return sampleTimeInState()
        }

        /** 数据源 1：/proc/stat（新系统上通常被 SELinux 拦截）。 */
        private fun sampleProcStat(): Float? {
            val line = SysFs.readText("/proc/stat")
                ?.lineSequence()?.firstOrNull { it.startsWith("cpu ") } ?: return null
            val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size < 4) return null
            val idle = parts[3] + (parts.getOrNull(4) ?: 0L) // idle + iowait
            val total = parts.sum()
            if (lastTotal < 0) { lastTotal = total; lastIdle = idle; return null }
            val dTotal = total - lastTotal
            val dIdle = idle - lastIdle
            lastTotal = total
            lastIdle = idle
            if (dTotal <= 0) return null
            return ((dTotal - dIdle).toFloat() / dTotal).coerceIn(0f, 1f)
        }

        /** 数据源 2：cpufreq time_in_state。 */
        private fun sampleTimeInState(): Float {
            val now = android.os.SystemClock.elapsedRealtime()
            var busyCs = 0L   // 累计运行时间（单位约 10ms）
            var cores = 0
            for (dir in clusterDirs()) {
                val tis = SysFs.readText(dir.absolutePath + "/stats/time_in_state")
                    ?: SysFs.readText(dir.absolutePath + "/../cpu0/cpufreq/stats/time_in_state")
                    ?: continue
                tis.lineSequence().forEach { l ->
                    val t = l.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
                    if (t != null) busyCs += t
                }
                val n = SysFs.readText(dir.absolutePath + "/affected_cpus")
                    ?.trim()?.split(Regex("\\s+"))?.count { it.toIntOrNull() != null } ?: 1
                cores += n.coerceAtLeast(1)
            }
            if (cores == 0 || busyCs <= 0) return -1f

            if (lastTis < 0) {
                lastTis = busyCs; lastTisWallMs = now; lastTisCores = cores
                return -1f
            }
            val wallCs = (now - lastTisWallMs) / 10f
            val dBusy = busyCs - lastTis
            lastTis = busyCs; lastTisWallMs = now; lastTisCores = cores
            if (wallCs <= 0 || dBusy < 0) return -1f
            val usage = (dBusy.toFloat() / (wallCs * cores))
            // 单位异常（部分机型 time 单位不是 10ms）时结果会离谱，判为不可用
            if (usage > 1.5f) return -1f
            return usage.coerceIn(0f, 1f)
        }
    }

    /** 设备芯片信息。 */
    fun socInfo(): Map<String, String> {
        val info = SysFs.parseCpuInfo()
        val hardware = info["Hardware"] ?: info["model name"] ?: Build.HARDWARE
        return buildMap {
            put("芯片型号", SoCName.pretty(Build.HARDWARE, hardware))
            put("架构", SoCName.abis())
            put("核心数", "${Runtime.getRuntime().availableProcessors()} 核")
            put("最高频率", maxFreqGhz().let { if (it > 0) String.format("%.2f GHz", it) else "N/A" })
            info["Features"]?.let { put("指令集", it.take(96) + if (it.length > 96) "…" else "") }
        }
    }

    fun uptimeText(): String {
        val ms = SystemClock.elapsedRealtime()
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }

    fun loadAverage(): Triple<Double, Double, Double> {
        val text = SysFs.readText("/proc/loadavg") ?: return Triple(0.0, 0.0, 0.0)
        val p = text.split(Regex("\\s+"))
        return Triple(
            p.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
            p.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
            p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        )
    }

    fun percent(f: Float): Int = (f * 100).roundToInt()
}

/** 常见 SoC 代号美化。 */
object SoCName {
    private val map = mapOf(
        "qcom" to "高通骁龙",
        "mt" to "联发科天玑",
        "exynos" to "三星 Exynos",
        "kirin" to "华为麒麟",
        "tensor" to "Google Tensor",
        "ums" to "紫光展锐",
        "rk3" to "瑞芯微"
    )

    fun pretty(hardware: String, modelName: String): String {
        val hit = map.entries.firstOrNull { hardware.startsWith(it.key, ignoreCase = true) }
        return if (hit != null) "${hit.value} ($modelName)" else modelName
    }

    fun abis(): String = Build.SUPPORTED_ABIS.joinToString(" / ")
}
