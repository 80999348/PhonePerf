package com.example.phoneperf.ui

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.phoneperf.R
import com.example.phoneperf.data.Metric
import com.example.phoneperf.data.MetricGroup
import com.example.phoneperf.data.Snapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 多布局列表：顶部总览(仪表盘+曲线) / 分组卡片。 */
class PerfAdapter(private val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_OVERVIEW = 0
        const val TYPE_GROUP = 1
        const val TYPE_HEADER = 2
        const val TYPE_FOOTER = 3
    }

    private var snapshot: Snapshot? = null
    private var history: PerfViewModel.History = PerfViewModel.History()

    fun submit(snapshot: Snapshot?, history: PerfViewModel.History) {
        this.snapshot = snapshot
        this.history = history
        notifyDataSetChanged()
    }

    private fun isHeader(position: Int) = position == 1

    override fun getItemViewType(position: Int): Int = when {
        position == 0 -> TYPE_OVERVIEW
        isHeader(position) -> TYPE_HEADER
        position == itemCount - 1 -> TYPE_FOOTER
        else -> TYPE_GROUP
    }

    override fun getItemCount(): Int = (snapshot?.groups?.size ?: 0) + 3

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_OVERVIEW -> OverviewVH(inflater.inflate(R.layout.item_overview, parent, false))
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_section_header, parent, false))
            TYPE_FOOTER -> FooterVH(inflater.inflate(R.layout.item_footer, parent, false))
            else -> GroupVH(inflater.inflate(R.layout.item_group, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val s = snapshot ?: return
        when (holder) {
            is OverviewVH -> holder.bind(s, history)
            is HeaderVH -> holder.bind(s)
            is FooterVH -> Unit // 纯静态版权页脚，无需绑定
            is GroupVH -> holder.bind(s.groups.getOrNull(position - 2))
        }
    }

    inner class OverviewVH(view: View) : RecyclerView.ViewHolder(view) {
        private val deviceName: TextView = view.findViewById(R.id.deviceName)
        private val deviceSub: TextView = view.findViewById(R.id.deviceSub)
        private val cpuGauge: GaugeView = view.findViewById(R.id.cpuGauge)
        private val memGauge: GaugeView = view.findViewById(R.id.memGauge)
        private val tempGauge: GaugeView = view.findViewById(R.id.tempGauge)
        private val chart: LineChartView = view.findViewById(R.id.chart)
        private val updateTime: TextView = view.findViewById(R.id.updateTime)

        fun bind(s: Snapshot, h: PerfViewModel.History) {
            deviceName.text = s.deviceName
            deviceSub.text = "${android.os.Build.VERSION.RELEASE} · ${s.cpuClusters.size} 个频率簇 · " +
                "${s.freqsGhz.size} 核"

            cpuGauge.label = "CPU"
            if (s.cpuUsage < 0) {
                cpuGauge.caption = "N/A"
                cpuGauge.label = ""
                cpuGauge.value = 0f
            } else {
                cpuGauge.caption = ""
                cpuGauge.value = s.cpuUsage
            }

            memGauge.label = "RAM"
            memGauge.value = s.mem.fraction

            val hottest = s.thermal.maxOfOrNull { it.tempC } ?: s.battery.temperatureC
            // 温度仪表以 20~100°C 映射到 0~1
            tempGauge.label = "°C"
            tempGauge.caption = "${hottest.toInt()}"
            tempGauge.value = ((hottest - 20f) / 80f).coerceIn(0f, 1f)

            chart.series = listOf(
                LineChartView.Series(h.cpu.map { it * 100f }, ContextCompat.getColor(context, R.color.accent), fill = true),
                LineChartView.Series(h.temp.map { it }, ContextCompat.getColor(context, R.color.warn))
            )
            chart.maxValue = 100f
            chart.unitLabel = "CPU % / 温度 °C"

            updateTime.text = "更新于 " +
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(s.timestamp))
        }
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.headerTitle)
        fun bind(@Suppress("UNUSED_PARAMETER") s: Snapshot) {
            title.text = "详细指标"
        }
    }

    inner class FooterVH(view: View) : RecyclerView.ViewHolder(view)

    inner class GroupVH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: TextView = view.findViewById(R.id.groupIcon)
        private val title: TextView = view.findViewById(R.id.groupTitle)
        private val subtitle: TextView = view.findViewById(R.id.groupSubtitle)
        private val container: LinearLayout = view.findViewById(R.id.metricsContainer)

        fun bind(group: MetricGroup?) {
            if (group == null) return
            icon.text = group.icon
            title.text = group.title
            if (group.subtitle.isNullOrEmpty()) {
                subtitle.visibility = View.GONE
            } else {
                subtitle.visibility = View.VISIBLE
                subtitle.text = group.subtitle
            }

            // 复用已有的行视图，避免每次刷新都重建
            while (container.childCount > group.metrics.size) {
                container.removeViewAt(container.childCount - 1)
            }
            val inflater = LayoutInflater.from(context)
            group.metrics.forEachIndexed { i, metric ->
                val row = if (i < container.childCount) container.getChildAt(i) else {
                    val v = inflater.inflate(R.layout.item_metric_row, container, false)
                    container.addView(v)
                    v
                }
                bindRow(row, metric)
            }
        }

        private fun bindRow(row: View, metric: Metric) {
            val label: TextView = row.findViewById(R.id.metricLabel)
            val value: TextView = row.findViewById(R.id.metricValue)
            val unit: TextView = row.findViewById(R.id.metricUnit)
            val bar: View = row.findViewById(R.id.metricBar)
            val barTrack: View = row.findViewById(R.id.metricBarTrack)

            label.text = metric.label
            value.text = metric.value
            unit.text = metric.unit

            val color = when (metric.level) {
                Metric.Level.DANGER -> ContextCompat.getColor(context, R.color.danger)
                Metric.Level.WARN -> ContextCompat.getColor(context, R.color.warn)
                Metric.Level.NORMAL -> ContextCompat.getColor(context, R.color.text_primary)
            }
            value.setTextColor(color)
            value.setTypeface(null, if (metric.level == Metric.Level.NORMAL) Typeface.NORMAL else Typeface.BOLD)

            val f = metric.fraction
            if (f == null) {
                barTrack.visibility = View.GONE
                bar.visibility = View.GONE
            } else {
                barTrack.visibility = View.VISIBLE
                bar.visibility = View.VISIBLE
                bar.post {
                    val lp = bar.layoutParams
                    lp.width = (barTrack.width * f.coerceIn(0f, 1f)).toInt()
                    bar.layoutParams = lp
                }
                bar.setBackgroundColor(
                    when (metric.level) {
                        Metric.Level.DANGER -> ContextCompat.getColor(context, R.color.danger)
                        Metric.Level.WARN -> ContextCompat.getColor(context, R.color.warn)
                        Metric.Level.NORMAL -> ContextCompat.getColor(context, R.color.accent)
                    }
                )
            }
        }
    }
}
