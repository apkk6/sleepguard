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

/**
 * 纯手机告警：检测到呼吸暂停 / 重度连续鼾声时，手机循环震动提醒（完全静音，
 * 无铃声、无提示音、无系统振动），直至用户点「我醒了」（通知按钮或 App 内按钮）取消。
 * 不依赖任何手环或外部服务，无需任何授权。
 */
object WearAlert {

    private const val TAG = "WearAlert"
    private const val CHANNEL_ALERT = "sleep_alert"
    private const val REQ_ID = 9001

    // 震动节奏（毫秒）：0 延迟 → 1200 震动 → 600 停顿 → 循环
    private val VIB_PATTERN = longArrayOf(0L, 1200L, 600L)

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
            ch.setSound(null, null)   // 渠道静音
            ch.enableVibration(false)  // 震动由我们手动控制，避免系统重复震动
            ch.setBypassDnd(true)      // 尽量在勿扰模式下也能提醒
            nm.createNotificationChannel(ch)
        }
    }

    fun isAlerting() = ringing

    /**
     * 测试震动：连续 3 下短震，用于验证手机马达是否正常。
     * 返回诊断信息：空字符串表示已成功发起震动调用；否则为失败原因。
     */
    fun testVibrate(ctx: Context): String {
        return try {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (!vib.hasVibrator()) {
                return "此设备没有振动马达"
            }
            // 三连短震（毫秒）：延迟0→震150→停90→震150→停90→震150
            val pat = longArrayOf(0L, 150L, 90L, 150L, 90L, 150L)
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(pat, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pat, -1)
            }
            // 勿扰(DND)检测：若开了勿扰，部分机型会静默屏蔽振动
            if (Build.VERSION.SDK_INT >= 23) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                    return "已触发震动，但手机处于「勿扰/静音」模式，系统可能已屏蔽——请关闭勿扰或把本应用加入勿扰例外"
                }
            }
            ""
        } catch (e: Throwable) {
            "震动被系统拦截：${e.message}（多半是系统未授予本应用「振动」权限，请到 设置→应用→守眠→权限 中开启）"
        }
    }

    /** 开始循环震动提醒（幂等，重复调用无效） */
    fun startAlert(ctx: Context, title: String, text: String) {
        if (ringing) return
        ringing = true
        val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator = vib
        // 循环震动（repeatIndex = 0 表示无限循环整个节奏序列）。
        // 使用显式振幅数组，部分机型默认振幅过弱导致感觉不到。
        if (Build.VERSION.SDK_INT >= 26) {
            val amps = intArrayOf(0, 255, 0) // 对应：延迟0 / 震动全幅 / 停顿
            vib.vibrate(VibrationEffect.createWaveform(VIB_PATTERN, amps, 0))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(VIB_PATTERN, 0)
        }
        // 静音通知：仅用于展示状态 + 「我醒了」按钮，无任何声音
        postSilentNotification(ctx, title, text)
        Log.i(TAG, "silent vibration alert started")
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
