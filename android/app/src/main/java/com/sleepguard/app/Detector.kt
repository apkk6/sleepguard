package com.sleepguard.app

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.PI

/**
 * 鼾声 / 呼吸暂停检测引擎（规则版 v1，参数与 docs/DESIGN.md 第 2 节及鸿蒙端完全一致）。
 * 每帧 0.5s（8000 采样 @16kHz）计算特征；外部以 1.5s 窗（3 帧）驱动事件判定。
 * 检测到 assets/snore_v1.tflite 时可切换模型推理（接口不变）。
 */
class Detector(sensitivity: Int) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 8000                       // 0.5s
        const val WINDOW_FRAMES = 3                  // 1.5s
        const val APNEA_SILENCE_MS = 10_000L         // 呼吸音中断阈值
        const val SNORE_MIN_FRAMES = 3               // ≥1.5s 连续判定
        const val SNORE_ALERT_COUNT = 6              // 10min 内 ≥6 次重度鼾声才告警
        const val SNORE_ALERT_WINDOW_MS = 10 * 60_000L
    }

    var sensitivity: Int = sensitivity.coerceIn(0, 100)
        set(v) { field = v.coerceIn(0, 100); refreshThresholds() }

    private var thrRms = 0f
    private var thrSilence = 0f
    private fun refreshThresholds() {
        // 灵敏度高 → 阈值低
        thrRms = 200f + (2000f - 200f) * (1f - sensitivity / 100f)
        thrSilence = thrRms * 0.25f
    }

    init { refreshThresholds() }

    /** 每帧特征 */
    data class FrameFeat(val rms: Float, val lowRatio: Float, val centroid: Float, val isSnore: Boolean)

    data class DetectionResult(val type: String?, val score: Float) // "SNORE"|"APNEA"|null

    // 运行时状态
    private var recentSnoreFrames = 0
    private var silenceMs = 0L
    private var lastSnoreEventMs = 0L
    private var inSnoringEpisode = false
    private val snoreTimestamps = ArrayDeque<Long>()

    fun analyzeFrame(samples: ShortArray, nowMs: Long): FrameFeat {
        require(samples.size == FRAME)
        var sumSq = 0.0; var zc = 0
        val n = samples.size
        // 简化频谱：Goertzel 提取 8 个频带能量，足够判 lowRatio / centroid
        val bands = floatArrayOf(80f, 150f, 250f, 400f, 700f, 1200f, 2400f, 4800f)
        val bandE = FloatArray(bands.size)
        for (b in bands.indices) {
            val w = 2.0 * PI * bands[b] / SAMPLE_RATE
            val coeff = 2.0 * cos(w)
            var sPrev = 0.0; var s2Prev = 0.0; var e = 0.0
            for (i in 0 until n step 4) { // 4 抽 1 加速
                val x = samples[i] / 32768.0
                val s = x + coeff * sPrev - s2Prev
                s2Prev = sPrev; sPrev = s
                e += x * x
            }
            bandE[b] = (sPrev * sPrev + s2Prev * s2Prev - coeff * sPrev * s2Prev).toFloat() + e.toFloat()
        }
        for (i in samples.indices) {
            val x = samples[i].toDouble(); sumSq += x * x
            if (i > 0 && (samples[i] >= 0) != (samples[i - 1] >= 0)) zc++
        }
        val rms = kotlin.math.sqrt(sumSq / n).toFloat()
        val totalE = bandE.sum().coerceAtLeast(1e-6f)
        var lowE = 0f; var centroidNum = 0f
        for (b in bands.indices) {
            if (bands[b] <= 300f) lowE += bandE[b]
            centroidNum += bandE[b] * bands[b]
        }
        val lowRatio = lowE / totalE
        val centroid = centroidNum / totalE
        val isSnore = rms > thrRms && lowRatio > 0.55f && centroid < 900f
        return FrameFeat(rms, lowRatio, centroid, isSnore)
    }

    /** 每 0.5s 调一次；返回发生的事件（一帧最多报一个） */
    fun onFrame(f: FrameFeat, nowMs: Long): DetectionResult {
        if (f.isSnore) recentSnoreFrames++ else recentSnoreFrames = if (recentSnoreFrames > 0) recentSnoreFrames - 1 else 0

        if (recentSnoreFrames >= SNORE_MIN_FRAMES) {
            if (nowMs - lastSnoreEventMs > 3000) {          // 去抖：3s 内算同一事件
                lastSnoreEventMs = nowMs
                inSnoringEpisode = true
                snoreTimestamps.addLast(nowMs)
                while (snoreTimestamps.size > 40) snoreTimestamps.removeFirst()
                return DetectionResult("SNORE", (f.rms / thrRms).coerceAtMost(3f))
            }
            inSnoringEpisode = true
            silenceMs = 0L
            return DetectionResult(null, 0f)
        }

        // 打鼾期内的静默累计 → 呼吸暂停
        if (inSnoringEpisode) {
            if (f.rms < thrSilence) {
                silenceMs += 500
                if (silenceMs >= APNEA_SILENCE_MS) {
                    inSnoringEpisode = false
                    silenceMs = 0L
                    return DetectionResult("APNEA", 1f + silenceMs / 10_000f)
                }
            } else {
                // 突发高能量恢复（喘息）
                if (silenceMs >= APNEA_SILENCE_MS / 2 && f.rms > thrRms * 2f) {
                    inSnoringEpisode = false; silenceMs = 0L
                    return DetectionResult("APNEA", 2.2f)
                }
                silenceMs = 0L
            }
        } else {
            // 离开打鼾状态超过 3min 无鼾声 → 复位 episode
            if (nowMs - lastSnoreEventMs > 3 * 60_000L) inSnoringEpisode = false
        }
        return DetectionResult(null, 0f)
    }

    /** 是否达到「重度连续鼾声」告警条件 */
    fun shouldAlertSnore(): Boolean {
        val cut = System.currentTimeMillis() - SNORE_ALERT_WINDOW_MS
        return snoreTimestamps.count { it >= cut } >= SNORE_ALERT_COUNT
    }

    fun reset() {
        recentSnoreFrames = 0; silenceMs = 0; lastSnoreEventMs = 0
        inSnoringEpisode = false; snoreTimestamps.clear()
    }
}
