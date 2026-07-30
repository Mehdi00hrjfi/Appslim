package com.appslim.analyzer.lens.launcher

import com.appslim.analyzer.lens.model.ProfileResult
import com.appslim.analyzer.lens.model.ProfileState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 检测会话的进程级单例状态（v0.5.5）
 *
 * 检测工作由前台 [ProfileService] 执行；UI（Compose）/ ViewModel 通过本单例观察进度与结果，
 * 不必与服务绑定。服务每次启动前调用 [reset]，结束后写入 [result]。
 */
object ProfileSession {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _progress = MutableStateFlow(0)          // 0..100
    val progress: StateFlow<Int> = _progress

    private val _tick = MutableStateFlow(0)              // 当前采样点
    val tick: StateFlow<Int> = _tick

    private val _totalTicks = MutableStateFlow(20)       // 总采样点（10s @ 500ms）
    val totalTicks: StateFlow<Int> = _totalTicks

    private val _state = MutableStateFlow<ProfileState>(ProfileState.Idle)
    val state: StateFlow<ProfileState> = _state

    private val _result = MutableStateFlow<ProfileResult?>(null)
    val result: StateFlow<ProfileResult?> = _result

    fun reset() {
        _running.value = false
        _progress.value = 0
        _tick.value = 0
        _state.value = ProfileState.Idle
        _result.value = null
    }

    fun setRunning(v: Boolean) { _running.value = v }
    fun setProgress(p: Int, tick: Int, total: Int) {
        _progress.value = p
        _tick.value = tick
        _totalTicks.value = total
    }
    fun setState(s: ProfileState) { _state.value = s }
    fun setResult(r: ProfileResult) { _result.value = r }
}
