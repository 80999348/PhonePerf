package com.example.phoneperf.data

/** 一条性能指标：标题 + 数值 + 单位 + 可选进度(0f~1f，用于条形图)。 */
data class Metric(
    val label: String,
    val value: String,
    val unit: String = "",
    val fraction: Float? = null,
    val level: Level = Level.NORMAL
) {
    enum class Level { NORMAL, WARN, DANGER }

    val display: String get() = if (unit.isEmpty()) value else "$value $unit"
}

/** 一张卡片：分组标题 + 若干指标。 */
data class MetricGroup(
    val title: String,
    val icon: String,
    val metrics: List<Metric>,
    val subtitle: String? = null
)
