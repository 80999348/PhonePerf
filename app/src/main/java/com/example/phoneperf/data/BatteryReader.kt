package com.example.phoneperf.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** 电池与散热数据采集。 */
object BatteryReader {

    data class BatteryInfo(
        val level: Int,             // 0~100
        val status: String,         // 充电中 / 放电中 / 已充满 / 未充电
        val plugged: String,        // 电源类型
        val temperatureC: Float,    // 电池温度
        val voltageMv: Int,
        val health: String,
        val technology: String,
        val currentMa: Int?,        // 正=充电，负=放电
        val chargeCounterUah: Int?, // 剩余电量
        val capacityDesign: Int?    // 设计容量（部分机型提供）
    ) {
        val powerWatts: Float? get() = currentMa?.let { (it / 1000f) * (voltageMv / 1000f) }
    }

    fun read(context: Context): BatteryInfo {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1

        val statusInt = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val status = when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }

        val pluggedInt = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plugged = when (pluggedInt) {
            BatteryManager.BATTERY_PLUGGED_AC -> "交流充电器"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
            0 -> "未连接电源"
            else -> "其他"
        }

        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val healthInt = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val health = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
            BatteryManager.BATTERY_HEALTH_COLD -> "温度过低"
            else -> "未知"
        }
        val tech = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "N/A"

        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } else null

        val counter = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        // 设计容量：部分厂商在 sysfs 暴露
        val design = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design"
        ).mapNotNull { SysFs.readLong(it) }.firstOrNull()?.let { (it / 1000).toInt() }

        return BatteryInfo(
            level = pct.coerceIn(0, 100).let { if (pct < 0) 0 else it },
            status = status,
            plugged = plugged,
            temperatureC = tempRaw / 10f,
            voltageMv = voltage,
            health = health,
            technology = tech,
            currentMa = current?.takeIf { it != Int.MIN_VALUE && it != 0 }?.let { it / 1000 },
            chargeCounterUah = counter?.takeIf { it > 0 },
            capacityDesign = design
        )
    }

    /** 从 PowerManager 获取省电状态与热状态。 */
    data class PowerState(val powerSave: Boolean, val thermalStatus: Int?)

    fun powerState(context: Context): PowerState {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                pm.currentThermalStatus
            } catch (e: Exception) {
                null
            }
        } else null
        return PowerState(pm.isPowerSaveMode, thermal)
    }

    fun thermalText(status: Int?): String = when (status) {
        null -> "N/A"
        PowerManager.THERMAL_STATUS_NONE -> "正常"
        PowerManager.THERMAL_STATUS_LIGHT -> "轻度升温"
        PowerManager.THERMAL_STATUS_MODERATE -> "中度升温"
        PowerManager.THERMAL_STATUS_SEVERE -> "严重升温"
        PowerManager.THERMAL_STATUS_CRITICAL -> "临界"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "即将关机"
        else -> "未知($status)"
    }
}
