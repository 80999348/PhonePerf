# 手机性能监视 · PhonePerf

一个轻量的 Android 性能数据读取 App，把手机里分散在 `/proc`、`/sys` 和系统服务中的性能指标聚合成一张实时看板。

## 功能

| 分类 | 采集内容 | 数据来源 |
| --- | --- | --- |
| CPU | 综合使用率、各核心簇当前/最高频率、平均负载、运行时长、省电模式、芯片型号/架构 | `/proc/stat`、`/sys/devices/system/cpu/cpufreq/policy*`、`/proc/loadavg`、`/proc/cpuinfo` |
| 内存 | 使用率、已用/总计、可用、缓存、Swap、低内存告警、本应用 PSS | `ActivityManager.MemoryInfo`、`/proc/meminfo`、`/proc/swaps` |
| 存储 | 内部存储使用率/剩余、外部存储剩余 | `StatFs` |
| 温度 | 电池温度、系统热状态、各 thermal zone 温度（取最热 6 个） | `ACTION_BATTERY_CHANGED`、`PowerManager`、`/sys/class/thermal` |
| 电池 | 电量、充放电状态、供电来源、电压、电流、实时功率、健康度、电池技术 | `BatteryManager`、`/sys/class/power_supply` |
| 网络 | 网络类型、上下行带宽估算、运营商、蜂窝制式、计费/联网状态 | `ConnectivityManager`、`TelephonyManager` |
| 设备 | **操作系统（ROM 识别）**、型号、品牌、Android 版本、安全补丁、内核、分辨率、DPI、刷新率、屏幕尺寸 | `Build`、`DisplayMetrics`、`getprop` |

界面包含**三个环形仪表**（CPU / RAM / 最高温度）、**实时折线图**（最近 60 个采样点，每 2 秒刷新一次），以及可下拉刷新的详细指标卡片。

## 技术栈

- Kotlin 1.9.24 + AndroidX + Material 3（**不使用 Compose**，构建更快、依赖更少）
- `minSdk 24` / `targetSdk 34` / `compileSdk 34`
- 图表全部**自绘**（`GaugeView`、`LineChartView`），无第三方图表库
- 架构：`View` ↔ `ViewModel(LiveData)` ↔ `PerfRepository` ↔ 各 `XxxReader`
- 所有 IO 在 `Dispatchers.IO` 执行，主线程零阻塞；页面 `onStop()` 自动停止轮询

## 目录结构

```
app/src/main/java/com/example/phoneperf/
├── data/
│   ├── SysFs.kt            # 安全读取 /sys、/proc 节点
│   ├── Metric.kt           # 数据模型
│   ├── CpuReader.kt        # CPU 频率/温度/负载/SoC
│   ├── MemoryReader.kt     # 内存/存储
│   ├── BatteryReader.kt    # 电池/热状态
│   ├── DeviceReader.kt     # 设备/操作系统识别/屏幕/网络
│   ├── SystemProps.kt      # getprop 系统属性读取（带缓存）
│   └── PerfRepository.kt   # 聚合为卡片
└── ui/
    ├── MainActivity.kt
    ├── PerfViewModel.kt    # 轮询 + 历史曲线
    ├── PerfAdapter.kt      # 多布局列表
    ├── GaugeView.kt        # 自绘环形仪表
    └── LineChartView.kt    # 自绘折线图
```

## 构建

需要 **JDK 17** 与 Android SDK（platform 34、build-tools 34.0.0）。

```bash
# 1. 确认 local.properties 中的 sdk.dir 指向本机 SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 2. 编译 Debug APK
./gradlew assembleDebug

# 产物：
#   app/build/outputs/apk/debug/app-debug.apk      （Debug，约 5.6 MB）
#   app/build/outputs/apk/release/app-release.apk  （Release，R8 压缩后约 1.8 MB）

# 3. 打 Release 包（已配置本地自签名，无需额外操作）
./gradlew assembleRelease

# 4. 安装到已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 5. 运行单元测试
./gradlew test
```

也可以直接用 Android Studio 打开本目录（Gradle JDK 选 17）后点 Run。

## 权限说明

- `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE`：读取网络类型与带宽估算
- `READ_PHONE_STATE`：读取运营商名称与蜂窝制式（**运行时申请**；拒绝不影响其他指标，仅这两项显示为空）
- 本应用**不申请**存储、位置、后台定位等敏感权限，也不上传任何数据

## 已知限制

- 部分 ROM（尤其定制系统）会封锁 `/sys/devices/system/cpu/cpufreq` 与 `/sys/class/thermal`，此时对应指标显示 `N/A`，不会崩溃。
- 电流、剩余电量依赖厂商实现，部分机型返回 `Int.MIN_VALUE` 或 `0`，已做过滤。
- 使用率与频率为瞬时采样值，2 秒一次，适合看趋势而非精确基准测试。

## 安装包与签名

项目根目录已提供两个可直接安装的 APK：

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `PhonePerf-debug.apk` | ~5.6 MB | Debug 包，便于调试，含调试信息 |
| `PhonePerf-release.apk` | ~1.8 MB | Release 包，R8 代码压缩 + 资源裁剪，日常使用推荐 |

```bash
adb install -r PhonePerf-release.apk
```

Release 包使用项目内 `keystore/phoneperf-release.jks` 自签名（别名 `phoneperf`，口令 `phoneperf123`）。
**该密钥仅用于本地测试，请勿用于应用商店上架**；正式发版请替换为自己的 keystore，并通过
`~/.gradle/gradle.properties` 或环境变量管理口令，不要写进仓库。

### 操作系统识别

「设备信息」中的**操作系统**会识别厂商定制系统，而非只显示 Android 版本：

| 系统 | 判定属性 | 示例显示 |
| --- | --- | --- |
| HarmonyOS | `hw_sc.build.os.version` | HarmonyOS 4.0.0 |
| 小米 HyperOS | `ro.mi.os.version.name` | 小米 HyperOS 2.0.1 |
| MIUI | `ro.miui.ui.version.name` | MIUI 14.0.5 |
| ColorOS / OxygenOS / realme UI | `ro.build.version.oplusrom` | 按品牌区分名称 |
| OriginOS（vivo/iQOO） | `ro.vivo.os.version` | OriginOS 4 |
| MagicOS（荣耀） | `ro.build.version.magic` | MagicOS 8.0 |
| One UI（三星） | `ro.build.version.oneui` | One UI 6.0 |
| EMUI（华为） | `ro.build.version.emui` | EMUI 12 |
| Flyme（魅族） | `ro.flyme.version` | Flyme 10 |
| 未识别 / 原生 | 以上均无 | Android 14（AOSP） |

属性通过一次 `getprop` 读取并**进程内缓存**，只在启动时执行一次，不影响 2 秒轮询性能；
`android.os.SystemProperties` 属隐藏 API，targetSdk 28+ 反射会被拦截，因此未采用。
