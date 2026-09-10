package com.sleepguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlin.math.roundToInt

/**
 * 纯手机告警：检测到呼吸暂停 / 鼾声（含轻度）时，手机循环震动提醒（完全静音，
 * 无铃声、无提示音、无系统振动），直至用户点「我醒了」取消。不依赖手环或外部服务。
 *
 * 震动强度可由用户调节：setStrength(percent) 把 1~100 映射到振幅 40~255；
 * 100 = 满振幅（Android 硬件上限），不封顶。
 */
object WearAlert {

    private const val TAG = "WearAlert"
    private const val CHANNEL_ALERT = "sleep_alert"
    private const val REQ_ID = 9001

    // 震动节奏（毫秒）：[起振延迟, 震动时长, 停顿时长]，repeatIndex=0 表示无限循环
    private val VIB_LIGHT = longArrayOf(0L, 800L, 600L)    // 轻度鼾声：节奏舒缓
    private val VIB_STRONG = longArrayOf(0L, 1500L, 350L)  // 重度鼾声 / 呼吸暂停：长促

    // 用户可调振幅（40~255），默认 255 = 满振幅（不封顶）
    @Volatile var userAmplitude: Int = 255
        private set

    /** percent: 1~100，100 = 满振幅（Android 硬件上限，不封顶） */
    fun setStrength(percent: Int) {
        val p = percent.coerceIn(1, 100)
        userAmplitude = ((p / 100f) * 255f).roundToInt().coerceIn(40, 255)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ringing = false
    private var vibrator: Vibrator? = null

    /** 创建静音震动渠道（仅初始化一次即可） */
    fun confirmChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ALERT, ctx.getString(R.string.alert_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            ch.setSound(null, null)    // 渠道静音
            ch.enableVibration(false)   // 震动由我们手动控制，避免系统重复震动
            ch.setBypassDnd(true)       // 尽量在勿扰模式下也能提醒
            nm.createNotificationChannel(ch)
        }
    }

    fun isAlerting() = ringing

    /**
     * 测试震动（4 下短促，按当前强度），用于验证手机马达是否正常。
     * 返回诊断信息：空字符串 = 已成功发起震动调用；否则为失败/注意事项。
     */
    fun testVibrate(ctx: Context): String {
        val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vib.hasVibrator()) return "此设备没有振动马达"
        val amp = userAmplitude
        val pat = longArrayOf(0L, 300L, 120L, 300L, 120L, 300L, 120L, 300L)
        val amps = intArrayOf(0, amp, 0, amp, 0, amp, 0, amp)
        var errMsg: String? = null
        val ok = try {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    vib.vibrate(VibrationEffect.createWaveform(pat, amps, -1))
                } catch (e: Throwable) {
                    vib.vibrate(VibrationEffect.createWaveform(pat, -1)) // 回退：默认振幅
                }
            } else {
                @Suppress("DEPRECATION") vib.vibrate(pat, -1)
            }
            true
        } catch (e: Throwable) { errMsg = e.message; false }
        if (!ok) {
            return "震动被系统拦截：${errMsg ?: "未知"}（请到 设置→应用→守眠→权限 中确认已开启「振动」）"
        }
        // 勿扰(DND)检测：若开了勿扰，部分机型会静默屏蔽振动
        if (Build.VERSION.SDK_INT >= 23) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                return "已触发震动，但手机处于「勿扰/静音」模式，系统可能已屏蔽——请关闭勿扰，或把守眠加入勿扰例外"
            }
        }
        return ""
    }

    /**
     * 开始循环震动提醒（幂等，重复调用无效）。
     * @param intensity "LIGHT" | "HEAVY" | "APNEA"，决定节奏；振幅统一用用户设置（不封顶）。
     */
    fun startAlert(ctx: Context, title: String, text: String, intensity: String = "HEAVY") {
        if (ringing) return
        ringing = true
        val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator = vib
        val pattern = if (intensity == "LIGHT") VIB_LIGHT else VIB_STRONG
        val amp = userAmplitude
        val amps = intArrayOf(0, amp, 0)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, amps, 0))
                } catch (e: Throwable) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 回退默认振幅
                }
            } else {
                @Suppress("DEPRECATION") vib.vibrate(pattern, 0)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "vibrate failed", e)
        }
        postSilentNotification(ctx, title, text)
        Log.i(TAG, "silent vibration alert started ($intensity, amp=$amp)")
    }

    /** 用户醒来 → 停止震动与通知 */
    fun stopAlert(ctx: Context) {
        ringing = false
        handler.removeCallbacksAndMessages(null)
        try { vibrator?.cancel() } catch (_: Throwable) {}
        vibrator = null
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(REQ_ID)
        Log.i(TAG, "alert stopped by user")
    }

    /** 静音通知，带「我醒了」按钮（无铃声、无系统振动、无提示音） */
    private fun postSilentNotification(ctx: Context, title: String, text: String) {
        val confirm = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, AlertActionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(ctx, CHANNEL_ALERT) else android.app.Notification.Builder(ctx)
        b.setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(android.app.Notification.PRIORITY_MAX)
            .setSound(null)              // 关键：无声音
            .setVibrate(null)            // 不让系统额外震动
            .setAutoCancel(true)
            .addAction(android.app.Notification.Action.Builder(
                null, ctx.getString(R.string.alert_confirm), confirm).build())
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(REQ_ID, b.build())
    }
}
