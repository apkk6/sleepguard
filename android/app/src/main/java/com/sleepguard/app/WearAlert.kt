package com.sleepguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * 手环告警：检测到事件后经 Wear Engine 向华为手环10 发送模板通知（亮屏+振动）。
 * 未确认 → 每 15s 重发，12 轮（3min）后休息 2min 再开新周期，直至用户点「我醒了」。
 * Wear Engine 不可用（未装运动健康/权限未开通）时降级为手机本地铃声+循环震动。
 */
object WearAlert {

    private const val TAG = "WearAlert"
    private const val CHANNEL_ALERT = "sleep_alert"
    private const val REQ_ID = 9001
    private const val RESEND_MS = 15_000L
    private const val ROUNDS = 12

    private val handler = Handler(Looper.getMainLooper())
    private var rounds = 0
    private var ringing = false
    private var player: MediaPlayer? = null

    fun confirmChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ALERT, ctx.getString(R.string.alert_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
    }

    fun isAlerting() = ringing

    /** 触发告警循环（幂等） */
    fun startAlert(ctx: Context, title: String, text: String) {
        if (ringing) return
        ringing = true; rounds = 0
        handler.post(object : Runnable {
            override fun run() {
                if (!ringing) return
                fireOnce(ctx, title, text)
                rounds++
                if (rounds % ROUNDS == 0) handler.postDelayed(this, 120_000) // 周期间休息
                else handler.postDelayed(this, RESEND_MS)
            }
        })
    }

    /** 用户确认醒来 → 停止循环 */
    fun stopAlert(ctx: Context) {
        ringing = false
        handler.removeCallbacksAndMessages(null)
        player?.stop(); player?.release(); player = null
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(REQ_ID)
    }

    private fun fireOnce(ctx: Context, title: String, text: String) {
        var wearOk = false
        try { wearOk = sendWearNotification(ctx, title, text) } catch (e: Throwable) {
            Log.w(TAG, "Wear Engine unavailable, fallback to local alarm: ${e.message}")
        }
        if (!wearOk) localAlarm(ctx)
        postPhoneNotification(ctx, title, text)
    }

    /** Wear Engine 模板通知。返回是否成功。SDK 类不存在时抛异常走降级。 */
    private fun sendWearNotification(ctx: Context, title: String, text: String): Boolean {
        val wearEngine = Class.forName("com.huawei.hms.wearengine.WearEngine")
        val deviceClient = wearEngine.getMethod("getDeviceClient", Context::class.java)
            .invoke(null, ctx)
        val devices = deviceClient.javaClass.getMethod("getConnectedDevices")
            .invoke(deviceClient) as List<*>
        if (devices.isEmpty()) return false
        val notifyClient = wearEngine.getMethod("getNotifyClient", Context::class.java)
            .invoke(null, ctx)
        val notifyCls = Class.forName("com.huawei.hms.wearengine.Notification")
        val builder = notifyCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        try {
            notifyCls.getMethod("setTitle", String::class.java).invoke(builder, title)
            notifyCls.getMethod("setText", String::class.java).invoke(builder, text)
        } catch (ignored: Throwable) { /* 方法名以实际 SDK 版本为准 */ }
        for (d in devices) {
            notifyClient.javaClass.getMethod("notify", d!!.javaClass, notifyCls)
                .invoke(notifyClient, d, builder)
        }
        return true
    }

    /** 本地兜底：铃声循环 + 强震动 */
    private fun localAlarm(ctx: Context) {
        if (player == null) {
            player = MediaPlayer().apply {
                setDataSource(
                    ctx,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                )
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM).build()
                )
                isLooping = true
                prepare(); start()
            }
        }
        val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800), -1))
        }
    }

    /** 手机侧高优先级通知，带「我醒了」按钮 */
    private fun postPhoneNotification(ctx: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val confirm = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, AlertActionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(ctx, CHANNEL_ALERT) else Notification.Builder(ctx)
        b.setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(Notification.PRIORITY_MAX)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.alert_confirm), confirm).build())
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(REQ_ID, b.build())
    }
}
