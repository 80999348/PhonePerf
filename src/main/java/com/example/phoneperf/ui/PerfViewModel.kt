package com.example.phoneperf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.phoneperf.data.PerfRepository
import com.example.phoneperf.data.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 界面状态。 */
sealed class UiState {
    object Loading : UiState()
    data class Ready(val snapshot: Snapshot) : UiState()
    data class Error(val message: String) : UiState()
}

class PerfViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableLiveData<UiState>(UiState.Loading)
    val state: LiveData<UiState> = _state

    private val _refreshing = MutableLiveData(false)
    val refreshing: LiveData<Boolean> = _refreshing

    /** 实时曲线：最近 60 个采样点。 */
    private val _history = MutableLiveData<History>()
    val history: LiveData<History> = _history

    data class History(
        val cpu: List<Float> = emptyList(),
        val mem: List<Float> = emptyList(),
        val temp: List<Float> = emptyList()
    ) {
        companion object {
            const val MAX_POINTS = 60
        }
    }

    private var pollingJob: Job? = null
    private var historyAcc = History()

    init {
        // 启动即预热 CPU 采样器，让首次刷新就有真实的综合使用率
        viewModelScope.launch { runCatching { PerfRepository.primeCpuSampler() } }
    }

    /** 首次加载 + 一次性全量采集。 */
    fun refresh(manual: Boolean = false) {
        if (manual) _refreshing.value = true
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    PerfRepository.collect(getApplication())
                }
            }
            result.onSuccess { snapshot ->
                _state.value = UiState.Ready(snapshot)
                appendHistory(snapshot)
            }.onFailure { e ->
                _state.value = UiState.Error(e.message ?: "采集失败：${e.javaClass.simpleName}")
            }
            if (manual) _refreshing.value = false
        }
    }

    /** 开始轮询（页面可见时调用），默认 2 秒一次。 */
    fun startPolling(intervalMs: Long = 2000L) {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val result = runCatching {
                    withContext(Dispatchers.IO) { PerfRepository.collect(getApplication()) }
                }
                result.onSuccess { snapshot ->
                    _state.value = UiState.Ready(snapshot)
                    appendHistory(snapshot)
                }
                // 刷新失败（例如传感器暂时不可读）时保留旧数据，不打断界面
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun appendHistory(snapshot: Snapshot) {
        val temp = snapshot.thermal.maxOfOrNull { it.tempC } ?: snapshot.battery.temperatureC
        // -1 表示本机无法测量 CPU 使用率，跳过该采样点，保持曲线连续
        val cpuVal = snapshot.cpuUsage
        val cpu = if (cpuVal < 0) historyAcc.cpu
                  else (historyAcc.cpu + cpuVal).takeLast(History.MAX_POINTS)
        val mem = (historyAcc.mem + snapshot.mem.fraction).takeLast(History.MAX_POINTS)
        val t = (historyAcc.temp + temp).takeLast(History.MAX_POINTS)
        historyAcc = History(cpu, mem, t)
        _history.value = historyAcc
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
