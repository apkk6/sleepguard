package com.sleepguard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var store: EventStore
    private lateinit var adapter: EventAdapter
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = EventStore(this)
        WearAlert.confirmChannel(this)

        adapter = EventAdapter { ev -> play(ev) }
        findViewById<RecyclerView>(R.id.eventList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        val toggle = findViewById<MaterialButton>(R.id.toggleBtn)
        val stopAlert = findViewById<View>(R.id.stopAlertBtn)
        toggle.setOnClickListener {
            if (RecordingServiceStarted()) stopRecording() else startRecording()
        }
        stopAlert.setOnClickListener { WearAlert.stopAlert(this); refreshUi() }

        findViewById<MaterialButton>(R.id.viewReportBtn).setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }

        // 演示与测试
        findViewById<MaterialButton>(R.id.demoReportBtn).setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java).putExtra("demo", true))
        }
        findViewById<MaterialButton>(R.id.testVibrateBtn).setOnClickListener {
            WearAlert.testVibrate(this)
            Toast.makeText(this, R.string.vibrate_tested, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.soundDemoBtn).setOnClickListener { playDemoSound() }

        val bar = findViewById<SeekBar>(R.id.sensitivityBar)
        bar.max = 100
        bar.progress = getSharedPreferences("cfg", MODE_PRIVATE).getInt("sens", 55)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("sens", s!!.progress).apply()
            }
        })

        findViewById<Switch>(R.id.wearSwitch).setOnCheckedChangeListener { _, _ -> }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun RecordingServiceStarted(): Boolean =
        RecordingService.instance != null

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) { requestPermissionsIfNeeded(); return }
        val i = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra("sensitivity", findViewById<SeekBar>(R.id.sensitivityBar).progress)
            .putExtra("wearAlert", findViewById<Switch>(R.id.wearSwitch).isChecked)
        ContextCompat.startForegroundService(this, i)
        refreshUi()
    }

    private fun stopRecording() {
        WearAlert.stopAlert(this)
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        refreshUi()
    }

    private fun refreshUi() {
        val started = RecordingServiceStarted()
        findViewById<MaterialButton>(R.id.toggleBtn).text =
            getString(if (started) R.string.stop_monitor else R.string.start_sleep)
        findViewById<TextView>(R.id.statusText).text =
            if (started) getString(R.string.recording) else getString(R.string.idle)
        findViewById<View>(R.id.stopAlertBtn).visibility =
            if (WearAlert.isAlerting()) View.VISIBLE else View.GONE
        CoroutineScope(Dispatchers.IO).launch {
            val dayStart = dayStart()
            val events = store.queryTonight(dayStart)
            val (sn, ap) = store.countsSince(dayStart)
            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.statsText).text = "鼾声 $sn 次 · 呼吸暂停 $ap 次"
                findViewById<TextView>(R.id.emptyText).visibility =
                    if (events.isEmpty()) View.VISIBLE else View.GONE
                adapter.submit(events)
            }
        }
    }

    private fun dayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (get(Calendar.HOUR_OF_DAY) < 18) add(Calendar.DAY_OF_YEAR, -1)
    }.timeInMillis

    private fun play(ev: SleepEvent) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(ev.wavPath); prepare(); start()
        }
    }

    /** 声音示范：仅手动试听，告警本身保持静音震动 */
    private fun playDemoSound() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_SIGNAL, 500)
        } catch (e: Throwable) {
            Log.w("MainActivity", "demo sound failed: ${e.message}")
        }
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    private class EventAdapter(
        private val onPlay: (SleepEvent) -> Unit
    ) : RecyclerView.Adapter<EventAdapter.VH>() {

        private val items = mutableListOf<SleepEvent>()
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun submit(list: List<SleepEvent>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.typeDot)
            val type: TextView = v.findViewById(R.id.typeText)
            val time: TextView = v.findViewById(R.id.timeText)
            val play: ImageButton = v.findViewById(R.id.playBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val ev = items[pos]
            val isApnea = ev.type == "APNEA"
            h.dot.setBackgroundColor(if (isApnea) 0xFFE24B4A.toInt() else 0xFFEF9F27.toInt())
            h.type.text = h.itemView.context.getString(
                if (isApnea) R.string.apnea else R.string.snore
            ) + " · 强度 ${"%.1f".format(ev.score)}"
            h.time.text = fmt.format(Date(ev.startEpochMs))
            h.play.setOnClickListener { onPlay(ev) }
        }
    }
}
