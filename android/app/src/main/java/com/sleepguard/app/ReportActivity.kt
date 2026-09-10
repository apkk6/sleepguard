package com.sleepguard.app

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ReportActivity : AppCompatActivity() {

    private lateinit var store: EventStore
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = EventStore(this)

        val session = store.lastSession()
        if (session == null) {
            findViewById<TextView>(R.id.reportNoData).visibility = View.VISIBLE
            hideCards()
            return
        }

        val endMs = if (session.endMs == 0L) System.currentTimeMillis() else session.endMs
        val duration = (endMs - session.startMs).coerceAtLeast(0)
        val events = store.queryTonight(session.startMs)
        val snore = events.count { it.type == "SNORE" }
        val apnea = events.count { it.type == "APNEA" }

        // 副标题：入睡 ~ 结束
        val sdf = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
        val endStr = if (session.endMs == 0L) getString(R.string.report_ongoing) else sdf.format(Date(endMs))
        findViewById<TextView>(R.id.reportSubtitle).text =
            "${getString(R.string.report_session_from)}${sdf.format(Date(session.startMs))}  ~  $endStr"
        if (demo) findViewById<TextView>(R.id.reportSubtitle).text = getString(R.string.demo_subtitle)

        // 概览
        findViewById<TextView>(R.id.durationText).text = formatDuration(duration)
        findViewById<TextView>(R.id.snoreCount).text = snore.toString()
        findViewById<TextView>(R.id.apneaCount).text = apnea.toString()

        // 评分（参考，启发式）
        val hours = duration / 3_600_000.0
        var score = 100 - apnea * 6 - snore * 2
        if (hours < 4) score -= (((4 - hours) * 10).toInt())
        score = score.coerceIn(0, 100)
        findViewById<TextView>(R.id.scoreText).text = score.toString()
        findViewById<TextView>(R.id.scoreLabel).text = scoreLabel(score)
        val bar = findViewById<ProgressBar>(R.id.scoreBar)
        bar.progress = score

        // 鼾声占比
        val snoreDur = events.filter { it.type == "SNORE" }.sumOf { it.durationMs }
        val rate = if (duration > 0) (snoreDur * 100 / duration) else 0
        findViewById<TextView>(R.id.snoreRateText).text = "鼾声时长约占整夜监测的 ${rate}%"

        // 每小时分布
        buildHourly(events, session.startMs, endMs)

        // 整夜强度曲线
        buildCurve(events, session.startMs, endMs)

        // 明细
        val detailEmpty = findViewById<TextView>(R.id.detailEmpty)
        detailEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        val list = findViewById<RecyclerView>(R.id.reportEventList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ReportAdapter(events) { play(it) }
    }

    private fun hideCards() {
        findViewById<MaterialCardView>(R.id.cardSummary).visibility = View.GONE
        findViewById<MaterialCardView>(R.id.cardScore).visibility = View.GONE
        findViewById<MaterialCardView>(R.id.cardHourly).visibility = View.GONE
        findViewById<MaterialCardView>(R.id.cardCurve).visibility = View.GONE
        findViewById<MaterialCardView>(R.id.cardDetail).visibility = View.GONE
    }

    private fun buildHourly(events: List<SleepEvent>, startMs: Long, endMs: Long) {
        val container = findViewById<LinearLayout>(R.id.hourlyContainer)
        container.removeAllViews()
        val snoreByHour = HashMap<Int, Int>()
        val apneaByHour = HashMap<Int, Int>()
        for (ev in events) {
            val h = Calendar.getInstance().apply { timeInMillis = ev.startEpochMs }.get(Calendar.HOUR_OF_DAY)
            if (ev.type == "APNEA") apneaByHour[h] = (apneaByHour[h] ?: 0) + 1
            else snoreByHour[h] = (snoreByHour[h] ?: 0) + 1
        }
        val startH = Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.HOUR_OF_DAY)
        val span = (((endMs - startMs) / 3_600_000L) + 1).toInt().coerceIn(1, 18)
        val hours = ArrayList<Int>()
        var h = startH
        repeat(span) { hours.add(h); h = (h + 1) % 24 }

        val maxCount = hours.maxOfOrNull { (snoreByHour[it] ?: 0) + (apneaByHour[it] ?: 0) } ?: 0
        val density = resources.displayMetrics.density

        if (maxCount == 0) {
            val t = TextView(this)
            t.text = getString(R.string.no_events)
            t.setTextColor(0xFF888780.toInt())
            t.textSize = 13f
            container.addView(t)
            return
        }

        for (hour in hours) {
            val c = (snoreByHour[hour] ?: 0) + (apneaByHour[hour] ?: 0)
            if (c == 0) continue
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.setPadding(0, 4, 0, 4)

            val label = TextView(this)
            label.text = String.format(Locale.US, "%02d:00", hour)
            label.textSize = 12f
            label.setTextColor(0xFF7A8699.toInt())
            label.layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            row.addView(label)

            val bar = View(this)
            val color = if ((apneaByHour[hour] ?: 0) > 0) 0xFFE24B4A.toInt() else 0xFFEF9F27.toInt()
            bar.setBackgroundColor(color)
            val wDp = (6 + (c * 180 / maxCount)).coerceAtMost(200)
            bar.layoutParams = LinearLayout.LayoutParams(
                (wDp * density).toInt(), (12 * density).toInt()
            ).apply { marginEnd = (8 * density).toInt() }
            row.addView(bar)

            val cnt = TextView(this)
            cnt.text = "${c}次"
            cnt.textSize = 12f
            cnt.setTextColor(0xFF5F5E5A.toInt())
            row.addView(cnt)

            container.addView(row)
        }
    }

    private fun buildCurve(events: List<SleepEvent>, startMs: Long, endMs: Long) {
        val view = findViewById<SnoreCurveView>(R.id.snoreCurve)
        if (events.isEmpty()) { view.setData(emptyList()); return }
        val n = 48
        val span = (endMs - startMs).toFloat().coerceAtLeast(1f)
        val maxScore = events.maxOfOrNull { it.score }?.coerceAtLeast(0.1f) ?: 1f
        val buckets = FloatArray(n)
        for (ev in events) {
            val f = ((ev.startEpochMs - startMs).toFloat() / span).coerceIn(0f, 0.999f)
            val idx = (f * (n - 1)).toInt()
            val norm = (ev.score / maxScore).coerceIn(0f, 1f)
            if (norm > buckets[idx]) buckets[idx] = norm
        }
        val points = (0 until n).map { i -> Pair(i.toFloat() / (n - 1), buckets[i]) }
        view.setData(points)
    }

    private fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return "${h}小时${m}分"
    }

    private fun scoreLabel(s: Int): String = when {
        s >= 85 -> "优秀"
        s >= 70 -> "良好"
        s >= 55 -> "一般"
        else -> "较差"
    }

    private fun play(ev: SleepEvent) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(ev.wavPath); prepare(); start()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private class ReportAdapter(
        private val items: List<SleepEvent>,
        private val onPlay: (SleepEvent) -> Unit
    ) : RecyclerView.Adapter<ReportAdapter.VH>() {

        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.typeDot)
            val type: TextView = v.findViewById(R.id.typeText)
            val time: TextView = v.findViewById(R.id.timeText)
            val play: ImageButton = v.findViewById(R.id.playBtn)
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int) = VH(
            LayoutInflater.from(p.context).inflate(R.layout.item_event, p, false)
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
