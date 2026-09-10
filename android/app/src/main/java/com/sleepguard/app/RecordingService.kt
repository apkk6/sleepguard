package com.sleepguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 整夜录音前台服务：AudioRecord 16kHz 单声道 PCM。
 * 预缓冲 5s 环形缓冲；命中事件 → 截前 5s + 后 8s 存 WAV → 入库 → 触发告警。
 * 录音期间持 PARTIAL_WAKE_LOCK，避免熄屏后 CPU 休眠导致检测/震动停止（荣耀等机型关键）。
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.sleepguard.app.START"
        const val ACTION_STOP = "com.sleepguard.app.STOP"
        const val CH_REC = "sleep_record"
        private const val PRE_SECONDS = 5
        private const val POST_SECONDS = 8
        private const val NOTIF_ID = 1001
        private const val ALERT_AUTO_STOP_MS = 90_000L  // 鼾声停止 90s 后自动停止震动
        @Volatile var instance: RecordingService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioRecord: AudioRecord? = null
    @Volatile private var running = false
    private lateinit var store: EventStore
    private var detector: Detector? = null
    private var wearAlertOn = true
    private var sessionId: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastAlertMs = 0L

    private val preBuffer = ArrayDeque<ShortArray>()          // 0.5s 块
    private val postPendQueue = ArrayDeque<ShortArray?>()     // 事件后补录（null 为标记）
    private var capturingPost = false
    private var postFramesLeft = 0
    private var pendingEvent: PendingEvent? = null

    private class PendingEvent(val type: String, val startMs: Long, val score: Float) {
        val blocks = mutableListOf<ShortArray>()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = EventStore(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_REC, getString(R.string.rec_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> {
                instance = this
                wearAlertOn = intent?.getBooleanExtra("wearAlert", true) ?: true
                val sens = intent?.getIntExtra("sensitivity", 55) ?: 55
                // 应用用户设置的震动强度（1~100，100=满振幅不封顶）
                val vs = intent?.getIntExtra("vibStrength", 100) ?: 100
                WearAlert.setStrength(vs)
                startForeground(NOTIF_ID, buildNotification())
                if (!running) startRecording(sens)
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CH_REC)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun startRecording(sensitivity: Int) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        // 保持 CPU 唤醒，避免屏幕关闭后系统休眠导致检测/震动停止（荣耀等机型尤为关键）
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepguard:rec")
            wakeLock?.acquire(12L * 60 * 60 * 1000) // 最长 12 小时
        } catch (e: Throwable) {
            Log.w("RecordingService", "wakeLock acquire failed: ${e.message}")
        }
        val minBuf = AudioRecord.getMinBufferSize(
            Detector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, Detector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            if (minBuf > Detector.FRAME * 2 * 4) minBuf else Detector.FRAME * 2 * 4
        )
        detector = Detector(sensitivity).also { it.reset() }
        preBuffer.clear(); postPendQueue.clear(); capturingPost = false
        store.lastSession()?.let { if (it.endMs == 0L) store.endSession(it.id, System.currentTimeMillis()) }
        sessionId = store.insertSession(System.currentTimeMillis())
        running = true
        audioRecord?.startRecording()
        scope.launch { loop() }
    }

    private suspend fun loop() {
        val frame = ShortArray(Detector.FRAME)
        while (running) {
            val read = audioRecord?.read(frame, 0, frame.size) ?: break
            if (read < frame.size) continue
            processFrame(frame.copyOf())
        }
    }

    private fun processFrame(block: ShortArray) {
        val d = detector ?: return
        val now = System.currentTimeMillis()

        // 鼾声停止一段时间后，自动停止震动（避免整夜空震把人吵醒后还在响）
        if (WearAlert.isAlerting() && lastAlertMs > 0 && now - lastAlertMs > ALERT_AUTO_STOP_MS) {
            WearAlert.stopAlert(this)
        }

        val feat = d.analyzeFrame(block, now)
        val res = d.onFrame(feat, now)
        preBuffer.addLast(block)
        while (preBuffer.size > PRE_SECONDS * 2) preBuffer.removeFirst() // 5s

        // 事件后补录状态机
        if (capturingPost) {
            pendingEvent?.blocks?.add(block)
            postFramesLeft--
            if (postFramesLeft <= 0) { finalizeEvent(); capturingPost = false }
            return
        }

        val eventType = when {
            res.type == "APNEA" -> "APNEA"
            // 轻度/重度鼾声都记录并触发震动（score>1.0 即超过阈值，含轻度）
            res.type == "SNORE" && res.score > 1.0f -> "SNORE"
            else -> null
        }
        if (eventType != null) {
            val pe = PendingEvent(eventType, now, res.score)
            pe.blocks.addAll(preBuffer)
            pendingEvent = pe
            capturingPost = true
            postFramesLeft = POST_SECONDS * 2
            if (wearAlertOn) maybeAlert(eventType, res.score)
        }
    }

    private fun maybeAlert(type: String, score: Float) {
        val intensity = when {
            type == "APNEA" -> "APNEA"
            score <= 1.6f -> "LIGHT"   // 轻度鼾声
            else -> "HEAVY"            // 重度鼾声
        }
        val title = getString(R.string.alert_title)
        val text = when {
            type == "APNEA" -> "检测到呼吸暂停，手机正在震动提醒（静音）"
            type == "SNORE" && score <= 1.6f -> "检测到轻度鼾声，手机正在震动提醒（静音）"
            else -> "检测到重度连续鼾声，手机正在震动提醒（静音）"
        }
        lastAlertMs = System.currentTimeMillis()
        WearAlert.startAlert(this, title, text, intensity)
    }

    private fun finalizeEvent() {
        val pe = pendingEvent ?: return
        scope.launch {
            val dir = File(
                getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir, "clips"
            ).apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(pe.startMs))
            val f = File(dir, "${pe.type}_$ts.wav")
            val all = pe.blocks.flatMap { it.asIterable() }
            val pcm = WavUtil.shortsToBytes(ShortArray(all.size) { all[it] })
            WavUtil.writeWav(f, pcm, Detector.SAMPLE_RATE)
            store.insert(pe.type, pe.startMs, (pe.blocks.size * 500L), f.absolutePath, pe.score)
            pendingEvent = null
        }
    }

    override fun onDestroy() {
        running = false
        instance = null
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        sessionId?.let { store.endSession(it, System.currentTimeMillis()) }
        sessionId = null
        audioRecord?.run { try { stop(); release() } catch (_: Exception) {} }
        audioRecord = null
        WearAlert.stopAlert(this)
        scope.cancel()
        super.onDestroy()
    }

    fun dayStartMs(): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (get(Calendar.HOUR_OF_DAY) >= 18) add(Calendar.DAY_OF_YEAR, 0)
            else add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
}
