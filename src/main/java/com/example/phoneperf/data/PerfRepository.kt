package com.example.phoneperf.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/** 一次完整采集的结果快照。 */
data class Snapshot(
    val timestamp: Long,
    val groups: List<MetricGroup>,
    val cpuUsage: Float,
    val cpuClusters: List<CpuReader.Cluster>,
    val freqsGhz: List<Double>,
    val maxFreqGhz: Double,
    val battery: BatteryReader.BatteryInfo,
    val mem: MemoryReader.MemInfo,
    val thermal: List<CpuReader.ThermalZone>,
    val deviceName: String
)

/**
 * 聚合各类读取器，把原始数据组织成可直接渲染的卡片。
 * 所有 IO 都应在后台线程执行（见 [com.example.phoneperf.ui.PerfViewModel]）。
 */
object PerfRepository {

    /** 跨轮询复用的采样器：使用率 = 两次 collect 间隔内(约2秒)的真实非空闲占比。 */
    private val cpuSampler = CpuReader.CpuLoadSampler()

    /**
     * 预热采样器：应用启动时先做一次短窗口采样，
     * 使第一次界面刷新就能拿到有意义的综合使用率。
     */
    suspend fun primeCpuSampler() {
        withContext(Dispatchers.IO) {
            runCatching {
                cpuSampler.sample()
                delay(150)
                cpuSampler.sample()
            }
        }
    }

    fun collect(context: Context): Snapshot {
        val start = System.currentTimeMillis()

        val mem = MemoryReader.read(context)
        val battery = BatteryReader.read(context)
        val power = BatteryReader.powerState(context)
        val clusters = CpuReader.readClusters()
        val freqs = CpuReader.currentFreqsGhz()
        val maxFreq = CpuReader.maxFreqGhz()
        val thermal = CpuReader.readThermalZones()
        val cpuUsage = cpuSampler.sample()
        val memExtra = MemoryReader.extraMemInfo()
        val swap = MemoryReader.swap()
        val storage = MemoryReader.storage(context)

        val groups = listOf(
            cpuGroup(cpuUsage, clusters, power),
            memoryGroup(mem, memExtra, swap, context),
            storageGroup(storage),
            thermalGroup(thermal, battery, power),
            batteryGroup(battery),
            networkGroup(context),
            deviceGroup(context)
        )

        android.util.Log.d("PerfRepository", "collect finished in ${System.currentTimeMillis() - start}ms")

        return Snapshot(
            timestamp = start,
            groups = groups,
            cpuUsage = cpuUsage,
            cpuClusters = clusters,
            freqsGhz = freqs,
            maxFreqGhz = maxFreq,
            battery = battery,
            mem = mem,
            thermal = thermal,
            deviceName = MemoryReader.deviceModel()
        )
    }

    private fun cpuGroup(
        usage: Float,
        clusters: List<CpuReader.Cluster>,
        power: BatteryReader.PowerState
    ): MetricGroup {
        val metrics = mutableListOf<Metric>()
        metrics += if (usage < 0) {
            // 系统未开放任何 CPU 使用率数据源（如模拟器）
            Metric("综合使用率", "N/A", "", fraction = null)
        } else {
            Metric(
                "综合使用率", "${(usage * 100).toInt()}", "%",
                fraction = usage, level = levelFor(usage)
            )
        }
        clusters.forEachIndexed { i, c ->
            metrics += Metric(
                "核心簇 ${i + 1} (CPU${c.cpus.joinToString(",")})",
                if (c.maxKhz > 0) String.format(Locale.US, "%.2f", c.curGhz) else "N/A",
                if (c.maxKhz > 0) "GHz / 上限 ${String.format(Locale.US, "%.2f", c.maxGhz)}" else "",
                fraction = if (c.maxKhz > 0) c.fraction else null
            )
        }
        val la = CpuReader.loadAverage()
        metrics += Metric("平均负载 (1/5/15 分钟)",
            String.format(Locale.US, "%.2f / %.2f / %.2f", la.first, la.second, la.third))
        metrics += Metric("运行时长", CpuReader.uptimeText())
        metrics += Metric("省电模式", if (power.powerSave) "已开启" else "关闭")

        val soc = CpuReader.socInfo()
        return MetricGroup(
            title = "处理器 CPU",
            icon = "CPU",
            subtitle = soc["芯片型号"],
            metrics = metrics
        )
    }

    private fun memoryGroup(
        mem: MemoryReader.MemInfo,
        extra: Map<String, Long>,
        swap: Pair<Long, Long>,
        context: Context
    ): MetricGroup {
        val metrics = mutableListOf<Metric>()
        metrics += Metric(
            "内存使用率", "${(mem.fraction * 100).toInt()}", "%",
            fraction = mem.fraction, level = levelFor(mem.fraction)
        )
        metrics += Metric("已用 / 总计", "${mem.usedBytes.toHumanBytes()} / ${mem.totalBytes.toHumanBytes()}")
        metrics += Metric("可用", mem.availableBytes.toHumanBytes())
        extra["Cached"]?.let { metrics += Metric("缓存", it.toHumanBytes()) }
        extra["Buffers"]?.let { metrics += Metric("缓冲区", it.toHumanBytes()) }
        if (swap.second > 0) {
            metrics += Metric("交换分区 (Swap)", "${swap.first.toHumanBytes()} / ${swap.second.toHumanBytes()}",
                fraction = (swap.first.toFloat() / swap.second).coerceIn(0f, 1f))
        }
        metrics += Metric("低内存告警", if (mem.lowMemory) "是" else "否",
            level = if (mem.lowMemory) Metric.Level.DANGER else Metric.Level.NORMAL)
        metrics += Metric("本应用占用", "${MemoryReader.appPssKb(context)} MB (PSS)")

        return MetricGroup("内存 RAM", "RAM", metrics = metrics)
    }

    private fun storageGroup(storage: MemoryReader.StorageInfo): MetricGroup {
        val total = storage.internalTotal
        val used = total - storage.internalFree
        val fraction = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
        val metrics = mutableListOf(
            Metric("内部存储使用率", "${(fraction * 100).toInt()}", "%",
                fraction = fraction, level = levelFor(fraction)),
            Metric("已用 / 总计", "${used.toHumanBytes()} / ${total.toHumanBytes()}"),
            Metric("剩余空间", storage.internalFree.toHumanBytes())
        )
        if (storage.externalTotal > 0) {
            metrics += Metric("外部存储剩余", storage.externalFree.toHumanBytes())
        }
        return MetricGroup("存储空间", "DISK", metrics = metrics)
    }

    private fun thermalGroup(
        zones: List<CpuReader.ThermalZone>,
        battery: BatteryReader.BatteryInfo,
        power: BatteryReader.PowerState
    ): MetricGroup {
        val metrics = mutableListOf<Metric>()
        val batteryTemp = battery.temperatureC
        metrics += Metric(
            "电池温度", String.format(Locale.US, "%.1f", batteryTemp), "°C",
            fraction = ((batteryTemp - 20f) / 50f).coerceIn(0f, 1f),
            level = when {
                batteryTemp >= 45f -> Metric.Level.DANGER
                batteryTemp >= 40f -> Metric.Level.WARN
                else -> Metric.Level.NORMAL
            }
        )
        metrics += Metric("系统热状态", BatteryReader.thermalText(power.thermalStatus))

        // 取温度最高且名字有意义的几个传感器
        zones.filter { zone -> zone.tempC in 1f..120f }
            .sortedByDescending { it.tempC }
            .take(6)
            .forEach { z ->
                metrics += Metric(
                    shortenZoneName(z.name),
                    String.format(Locale.US, "%.1f", z.tempC), "°C",
                    fraction = ((z.tempC - 20f) / 70f).coerceIn(0f, 1f),
                    level = when {
                        z.tempC >= 75f -> Metric.Level.DANGER
                        z.tempC >= 60f -> Metric.Level.WARN
                        else -> Metric.Level.NORMAL
                    }
                )
            }
        return MetricGroup("温度 散热", "TEMP", metrics = metrics,
            subtitle = if (zones.isEmpty()) "该机型未开放温度传感器" else "共 ${zones.size} 个传感器")
    }

    private fun shortenZoneName(name: String): String {
        val n = name.trim()
        return when {
            n.length <= 22 -> n
            else -> n.take(21) + "…"
        }
    }

    private fun batteryGroup(b: BatteryReader.BatteryInfo): MetricGroup {
        val metrics = mutableListOf<Metric>()
        metrics += Metric(
            "电量", "${b.level}", "%",
            fraction = b.level / 100f,
            level = when {
                b.level <= 15 -> Metric.Level.DANGER
                b.level <= 30 -> Metric.Level.WARN
                else -> Metric.Level.NORMAL
            }
        )
        metrics += Metric("状态", b.status)
        metrics += Metric("供电来源", b.plugged)
        metrics += Metric("电压", String.format(Locale.US, "%.2f", b.voltageMv / 1000f), "V")
        b.currentMa?.let { ma ->
            metrics += Metric(
                if (b.status == "充电中") "充电电流" else "放电电流",
                "${kotlin.math.abs(ma)}", "mA"
            )
            metrics += Metric("实时功率", String.format(Locale.US, "%.2f", kotlin.math.abs(b.powerWatts ?: 0f)), "W")
        }
        b.chargeCounterUah?.let { metrics += Metric("剩余电量", "${it / 1000} mAh") }
        metrics += Metric("电池健康", b.health,
            level = if (b.health == "良好") Metric.Level.NORMAL else Metric.Level.WARN)
        metrics += Metric("电池技术", b.technology)

        return MetricGroup("电池", "BATTERY", metrics = metrics)
    }

    private fun networkGroup(context: Context): MetricGroup {
        val info = DeviceReader.networkInfo(context)
        return MetricGroup(
            "网络", "NET",
            metrics = info.map { (k, v) -> Metric(k, v) }
        )
    }

    private fun deviceGroup(context: Context): MetricGroup {
        val metrics = mutableListOf<Metric>()
        val os = DeviceReader.osInfo()
        metrics += Metric("操作系统", os.display)
        metrics += Metric("系统说明", os.detail)
        DeviceReader.buildInfo().forEach { (k, v) -> metrics += Metric(k, v) }
        DeviceReader.screenInfo(context).forEach { (k, v) -> metrics += Metric(k, v) }
        return MetricGroup("设备信息", "PHONE", metrics = metrics,
            subtitle = os.name)
    }

    private fun levelFor(f: Float): Metric.Level = when {
        f >= 0.9f -> Metric.Level.DANGER
        f >= 0.75f -> Metric.Level.WARN
        else -> Metric.Level.NORMAL
    }
}
