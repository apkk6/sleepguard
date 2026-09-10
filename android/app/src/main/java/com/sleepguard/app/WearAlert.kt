package com.sleepguard.app

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
import com.huawei.hmf.tasks.OnFailureListener
import com.huawei.hmf.tasks.OnSuccessListener
import com.huawei.wearengine.HiWear
import com.huawei.wearengine.auth.AuthCallback
import com.huawei.wearengine.auth.AuthClient
import com.huawei.wearengine.auth.Permission
import com.huawei.wearengine.device.Device
import com.huawei.wearengine.device.DeviceClient
import com.huawei.wearengine.notify.Action
import com.huawei.wearengine.notify.Notification
import com.huawei.wearengine.notify.NotificationConstants
import com.huawei.wearengine.notify.NotificationTemplate
import com.huawei.wearengine.notify.NotifyClient

/**
 * 手环告警：检测到事件后经 Wear Engine 向华为手环发送模板通知（亮屏+振动，跟随手环系统设置）。
 * 未确认 → 每 15s 重发，12 轮后休息 2min 再开新周期，直至用户点「我醒了」（手环按钮或手机通知均可）。
 * Wear Engine 不可用（未装运动健康/未授权/未注册）时降级为手机本地铃声+循环震动。
 *
 * 华为硬限制（务必知晓）：
 *  - 第三方 App 无法直接控制手环“持续震动”，只能发通知让手环按系统设置振动；
 *  - 手环上能显示“本App名称+图标”、并能点按钮，靠的是模板通知（无需手环侧装App）；
 *  - 真正生效需：① 手机装华为运动健康并连上手环 ② 在华为开发者联盟注册本App、填调试签名指纹、
 *    申请 Wear Engine「消息通知」权限 ③ 首次运行在运动健康里点“授权”。三步未齐则自动降级。
 */
object WearAlert {

    private const val TAG = "WearAlert"
    private const val CHANNEL_ALERT = "sleep_alert"
    private const val REQ_ID = 9001
    private const val RESEND_MS = 15_000L
    private const val ROUNDS = 12
    private const val PKG = "com.sleepguard.app"

    private val handler = Handler(Looper.getMainLooper())
    private var rounds = 0
    private var ringing = false
    private var player: MediaPlayer? = null
    private var targetDevice: Device? = null

    /** 手环状态，供主界面显示：未连接 / 已连接：xxx / 已授权 / 授权失败... */
    var bandStatus: String = "未连接"
        private set
    var bandAuthorized = false
        private set

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
        ensureDevice(ctx) // 异步解析手环，首轮可能走本地兜底，后续轮次生效
        handler.post(object : Runnable {
            override fun run() {
                if (!ringing) return
                fireOnce(ctx, title, text)
                rounds++
                if (rounds % ROUNDS == 0) handler.postDelayed(this, 120_000)
                else handler.postDelayed(this, RESEND_MS)
            }
        })
    }

    /** 用户确认醒来 → 停止循环（手环按钮或手机通知触发都会走到这） */
    fun stopAlert(ctx: Context) {
        ringing = false
        handler.removeCallbacksAndMessages(null)
        player?.stop(); player?.release(); player = null
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(REQ_ID)
        Log.i(TAG, "alert stopped by user")
    }

    /** 解析已连接手环（Wear Engine 要求先授权，故连接动作里含授权） */
    fun connect(ctx: Context, onResult: (String) -> Unit) {
        try {
            val auth: AuthClient = HiWear.getAuthClient(ctx)
            auth.requestPermission(object : AuthCallback {
                override fun onOk(permissions: Array<Permission>) {
                    bandAuthorized = true
                    queryDevice(ctx, onResult)
                }
                override fun onCancel() {
                    bandAuthorized = false
                    bandStatus = "已取消授权"
                    onResult(bandStatus)
                }
            }, Permission.DEVICE_MANAGER, Permission.NOTIFY)
        } catch (e: Throwable) {
            Log.w(TAG, "auth failed: ${e.message}")
            bandStatus = "授权失败：${e.message}"
            onResult(bandStatus)
        }
    }

    /** 查询已连接手环并更新状态文本 */
    fun queryDevice(ctx: Context, onResult: ((String) -> Unit)? = null) {
        try {
            val dc: DeviceClient = HiWear.getDeviceClient(ctx)
            dc.getConnectedDevices().addOnSuccessListener(OnSuccessListener { list ->
                val d = list.firstOrNull { it.isConnected }
                targetDevice = d
                bandStatus = if (d != null) "已连接：${d.name}" else "手环未连接（请在华为运动健康中连接）"
                onResult?.invoke(bandStatus)
            }).addOnFailureListener(OnFailureListener {
                targetDevice = null
                bandStatus = "查询失败：${it.message}"
                onResult?.invoke(bandStatus)
            })
        } catch (e: Throwable) {
            Log.w(TAG, "query device failed: ${e.message}")
            targetDevice = null
            bandStatus = "未连接"
            onResult?.invoke(bandStatus)
        }
    }

    private fun ensureDevice(ctx: Context) {
        try {
            val dc: DeviceClient = HiWear.getDeviceClient(ctx)
            dc.getConnectedDevices().addOnSuccessListener(OnSuccessListener { list ->
                targetDevice = list.firstOrNull { it.isConnected }
                if (targetDevice != null) bandStatus = "已连接：${targetDevice!!.name}"
            }).addOnFailureListener(OnFailureListener { targetDevice = null })
        } catch (_: Throwable) { /* 降级 */ }
    }

    private fun fireOnce(ctx: Context, title: String, text: String) {
        val dev = targetDevice
        if (dev != null && bandAuthorized) {
            try {
                val nc: NotifyClient = HiWear.getNotifyClient(ctx)
                val n = Notification.Builder()
                    .setTemplateId(NotificationTemplate.NOTIFICATION_TEMPLATE_ONE_BUTTON)
                    .setPackageName(PKG)
                    .setTitle(title)
                    .setText(text)
                    .setButtonContents(HashMap<Int, String>().apply {
                        put(NotificationConstants.BUTTON_ONE_CONTENT_KEY, ctx.getString(R.string.alert_confirm))
                    })
                    .setAction(object : Action() {
                        override fun onResult(notification: Notification?, feedback: Int) {
                            // feedback: 0=HOME/灭屏 1=删除 2=点击第1个按钮 3/4=其余按钮
                            if (feedback == 2) stopAlert(ctx)
                        }
                        override fun onError(notification: Notification?, errorCode: Int, errorMsg: String?) {}
                    })
                    .build()
                nc.notify(dev, n).addOnSuccessListener(OnSuccessListener<Void> {
                    Log.i(TAG, "wear notification sent")
                }).addOnFailureListener(OnFailureListener {
                    Log.w(TAG, "wear notify failed, local fallback: ${it.message}")
                    localAlarm(ctx)
                })
                return
            } catch (e: Throwable) {
                Log.w(TAG, "wear api error, local fallback: ${e.message}")
            }
        }
        localAlarm(ctx)
        postPhoneNotification(ctx, title, text)
    }

    /** 本地兜底：铃声循环 + 强震动 */
    private fun localAlarm(ctx: Context) {
        if (player == null) {
            try {
                player = MediaPlayer().apply {
                    setDataSource(
                        ctx,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    )
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                    )
                    isLooping = true
                    prepare(); start()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ringtone failed: ${e.message}")
            }
        } else {
            try { if (!player!!.isPlaying) player!!.start() } catch (_: Throwable) {}
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
            android.app.Notification.Builder(ctx, CHANNEL_ALERT) else android.app.Notification.Builder(ctx)
        b.setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(android.app.Notification.PRIORITY_MAX)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(android.app.Notification.Action.Builder(null, ctx.getString(R.string.alert_confirm), confirm).build())
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(REQ_ID, b.build())
    }
}
