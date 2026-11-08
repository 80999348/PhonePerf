package com.example.phoneperf.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale

/** 设备 / 系统 / 网络信息。 */
object DeviceReader {

    /** 操作系统（含厂商 ROM）识别结果。 */
    data class OsInfo(val name: String, val version: String, val detail: String) {
        /** 例："小米 HyperOS 2.0.1"；无法识别 ROM 时退化为 "Android 14"。 */
        val display: String get() = if (version.isBlank()) name else "$name $version"
    }

    /**
     * 识别操作系统 / 厂商 ROM。
     *
     * 安卓生态里各家都在 AOSP 之上做了定制系统，仅显示 "Android 14" 会丢失关键信息，
     * 因此按机型属性逐一匹配：鸿蒙 → HyperOS → MIUI → ColorOS 系 → OriginOS → 荣耀
     * → 三星 → 魅族 → 中兴/联想 → 摩托罗拉 → AOSP 兜底。
     */
    fun osInfo(): OsInfo {
        val androidVer = Build.VERSION.RELEASE.ifBlank { "未知" }

        // 华为鸿蒙（仍可运行安卓应用）
        SystemProps.firstOf("hw_sc.build.os.version", "ro.build.version.harmony")?.let { v ->
            return OsInfo("HarmonyOS", v, "鸿蒙系统（兼容 Android $androidVer）")
        }
        // 小米 澎湃 OS / MIUI
        SystemProps.firstOf("ro.mi.os.version.name", "ro.mi.os.version.incremental")?.let { v ->
            return OsInfo("小米 HyperOS", v.removePrefix("OS").trim(), "基于 Android $androidVer 的澎湃系统")
        }
        SystemProps.get("ro.miui.ui.version.name")?.let { v ->
            val ver = v.removePrefix("V").trim()
            return OsInfo("MIUI", ver, "小米定制系统，基于 Android $androidVer")
        }
        // OPPO / 一加 / realme 共用 ColorOS 底层
        SystemProps.firstOf("ro.build.version.oplusrom", "ro.build.version.oplusrom.display")?.let { v ->
            val brand = Build.BRAND.lowercase(Locale.US)
            val (name, note) = when {
                brand.contains("oneplus") -> "OxygenOS" to "一加定制系统"
                brand.contains("realme") -> "realme UI" to "真我定制系统"
                else -> "ColorOS" to "OPPO 定制系统"
            }
            return OsInfo(name, v.removePrefix("V").trim(), "$note，基于 Android $androidVer")
        }
        // vivo / iQOO
        SystemProps.firstOf("ro.vivo.os.version", "ro.iqoo.os.version")?.let { v ->
            val name = SystemProps.get("ro.vivo.os.name") ?: "OriginOS"
            return OsInfo(name, v, "vivo 定制系统，基于 Android $androidVer")
        }
        // 荣耀 MagicOS
        SystemProps.firstOf("ro.build.version.magic", "ro.build.version.magic_version")?.let { v ->
            return OsInfo("MagicOS", v, "荣耀定制系统，基于 Android $androidVer")
        }
        // 三星 One UI
        SystemProps.get("ro.build.version.oneui")?.let { raw ->
            val ver = raw.toIntOrNull()?.let { "${it / 10000}.${(it % 10000) / 100}" } ?: raw
            return OsInfo("One UI", ver, "三星定制系统，基于 Android $androidVer")
        }
        // 华为 EMUI（鸿蒙之前）
        SystemProps.get("ro.build.version.emui")?.let { v ->
            return OsInfo("EMUI", v, "华为定制系统，基于 Android $androidVer")
        }
        // 魅族 Flyme
        SystemProps.firstOf("ro.flyme.version", "ro.build.version.flyme")?.let { v ->
            return OsInfo("Flyme", v, "魅族定制系统，基于 Android $androidVer")
        }
        // 中兴 MyOS / 联想 ZUI
        SystemProps.get("ro.build.version.miui")?.let { v ->
            return OsInfo("MyOS", v, "中兴定制系统，基于 Android $androidVer")
        }
        SystemProps.get("ro.com.zui.version")?.let { v ->
            return OsInfo("ZUI", v, "联想定制系统，基于 Android $androidVer")
        }
        // 摩托罗拉
        SystemProps.get("ro.mot.build.version.sdk_int")?.let {
            return OsInfo("My UX", "", "摩托罗拉定制系统，基于 Android $androidVer")
        }
        // 兜底：AOSP 原生 / 未识别 ROM
        val aosp = SystemProps.get("ro.build.characteristics") == "nosdcard"
        return OsInfo("Android", androidVer, if (aosp) "原生 Android 系统" else "AOSP，未识别到厂商 ROM")
    }

    @SuppressLint("HardwareIds")
    fun buildInfo(): Map<String, String> = buildMap {
        put("设备型号", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("品牌", Build.BRAND)
        put("设备代号", Build.DEVICE)
        put("Android 版本", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        put("安全补丁", Build.VERSION.SECURITY_PATCH.ifEmpty { "N/A" })
        put("系统指纹", Build.FINGERPRINT.take(48) + if (Build.FINGERPRINT.length > 48) "…" else "")
        put("内核版本", System.getProperty("os.version") ?: "N/A")
        put("Java VM", System.getProperty("java.vm.version") ?: "N/A")
    }

    @Suppress("DEPRECATION")
    fun screenInfo(context: Context): Map<String, String> = buildMap {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        val display = wm.defaultDisplay
        display.getRealMetrics(dm)
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi
        put("分辨率", "$width × $height")
        put("像素密度", "$density dpi")
        put("刷新率", try {
            String.format(Locale.US, "%.0f Hz", display.refreshRate)
        } catch (e: Exception) {
            "N/A"
        })
        put("屏幕尺寸", "约 ${String.format(Locale.US, "%.1f", diagonalInch(dm))} 英寸")
    }

    private fun diagonalInch(dm: DisplayMetrics): Double {
        val w = dm.widthPixels / dm.xdpi
        val h = dm.heightPixels / dm.ydpi
        return Math.sqrt((w * w + h * h).toDouble())
    }

    @SuppressLint("MissingPermission")
    fun networkInfo(context: Context): Map<String, String> = buildMap {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }

        put("网络类型", when {
            caps == null -> "未连接"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他"
        })

        val linkDown = caps?.linkDownstreamBandwidthKbps ?: 0
        val linkUp = caps?.linkUpstreamBandwidthKbps ?: 0
        if (linkDown > 0) {
            put("下行带宽(估算)", formatMbps(linkDown))
            put("上行带宽(估算)", formatMbps(linkUp))
        }
        put("是否计费网络", if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) "否" else "是")
        put("是否已联网", if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) "是" else "否")

        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val operator = tm.networkOperatorName
            if (!operator.isNullOrBlank()) put("运营商", operator)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val type = when (tm.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_HSDPA,
                    TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                    TelephonyManager.NETWORK_TYPE_UNKNOWN -> "未知"
                    else -> "其他"
                }
                put("蜂窝制式", type)
            }
        } catch (e: SecurityException) {
            // 未授权时忽略
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun formatMbps(kbps: Int): String =
        if (kbps >= 1000) String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
        else "$kbps Kbps"

    fun storageStat(path: String): Pair<Long, Long>? = try {
        val stat = StatFs(path)
        stat.blockCountLong * stat.blockSizeLong to stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) {
        null
    }
}

/** 字节数 → 人类可读（GiB/MiB）。 */
fun Long.toHumanBytes(): String {
    if (this <= 0) return "0 B"
    val kb = this / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.0f MB", mb)
        kb >= 1 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$this B"
    }
}
