package com.rrdlab.batterywidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Launcher screen: big live numbers (power, voltage, current, temperature) plus the same two forecast
 * lines as the widget. Measurement runs for a minute at a time: 5 s step when the window opens,
 * 1 s step after "Refresh".
 */
class MainActivity : Activity() {

    private companion object {
        const val WINDOW_MS = 60_000L
        const val OPEN_STEP_MS = 5_000L
        const val FAST_STEP_MS = 1_000L
        const val MAX_READINGS = 12   // ~1 min at 5 s, ~12 s at 1 s: enough to average out load spikes

        val BG = Color.parseColor("#0F1115")
        val CARD = Color.parseColor("#1A1D24")
        val TEXT = Color.parseColor("#F2F4F8")
        val MUTED = Color.parseColor("#8A93A3")
        val CHARGE = Color.parseColor("#3DDC84")
        val DISCHARGE = Color.parseColor("#FF9F43")
    }

    private lateinit var stateView: TextView
    private lateinit var levelView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var powerLabel: TextView
    private lateinit var powerView: TextView
    private lateinit var voltageView: TextView
    private lateinit var currentLabel: TextView
    private lateinit var currentView: TextView
    private lateinit var tempView: TextView
    private lateinit var capView: TextView
    private lateinit var dischargeView: TextView
    private lateinit var chargeView: TextView
    private lateinit var statusView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val readings = ArrayDeque<Long>()   // instantaneous current, µA
    private var stepMs = OPEN_STEP_MS
    private var endAt = 0L
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            update()
            val over = SystemClock.elapsedRealtime() >= endAt
            if (over) running = false
            showStatus()
            if (!over) handler.postDelayed(this, stepMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BatteryWidgetProvider.schedule(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        // Header: big level + state
        val header = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        levelView = text(56f, TEXT, light = true)
        stateView = text(16f, MUTED).apply { setPadding(dp(12), 0, 0, dp(10)) }
        header.addView(levelView)
        header.addView(stateView)
        root.addView(header)
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressBackgroundTintList = ColorStateList.valueOf(CARD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
                .also { it.topMargin = dp(8) }
        }
        root.addView(levelBar)

        // Hero: power
        val hero = card()
        powerLabel = text(14f, MUTED)
        powerView = text(64f, TEXT, light = true)
        hero.addView(powerLabel)
        hero.addView(powerView)
        root.addView(hero, cardParams(top = 16))

        // Tiles
        val row1 = LinearLayout(this)
        voltageView = tile(row1, getString(R.string.label_voltage)).second
        val cur = tile(row1, "")
        currentLabel = cur.first
        currentView = cur.second
        root.addView(row1, cardParams(top = 12))
        val row2 = LinearLayout(this)
        tempView = tile(row2, getString(R.string.label_temp)).second
        capView = tile(row2, getString(R.string.label_capacity)).second
        root.addView(row2, cardParams(top = 12))

        // Forecast
        val fc = card()
        fc.addView(text(14f, MUTED).apply { setText(R.string.label_forecast) })
        dischargeView = text(17f, TEXT).apply { setPadding(0, dp(8), 0, 0) }
        chargeView = text(17f, TEXT).apply { setPadding(0, dp(6), 0, 0) }
        fc.addView(dischargeView)
        fc.addView(chargeView)
        root.addView(fc, cardParams(top = 12))

        // Controls
        statusView = text(13f, MUTED).apply { setPadding(0, dp(16), 0, 0) }
        root.addView(statusView)
        root.addView(button(R.string.btn_refresh, CHARGE, Color.BLACK) { start(FAST_STEP_MS) }, cardParams(top = 8))
        root.addView(button(R.string.btn_add_widget, CARD, TEXT) { pinWidget() }, cardParams(top = 8))
        root.addView(button(R.string.btn_battery, CARD, TEXT) { requestUnrestricted() }, cardParams(top = 8))
        root.addView(text(13f, MUTED).apply { setText(R.string.main_hint); setPadding(0, dp(16), 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    override fun onStart() {
        super.onStart()
        start(OPEN_STEP_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        running = false
        super.onStop()
    }

    /** Starts a [WINDOW_MS] run with the given step; restarts any run in progress. */
    private fun start(step: Long) {
        handler.removeCallbacks(ticker)
        readings.clear()
        stepMs = step
        endAt = SystemClock.elapsedRealtime() + WINDOW_MS
        running = true
        handler.post(ticker)
    }

    private fun showStatus() {
        val left = ((endAt - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
        statusView.text =
            if (running) getString(R.string.status_running, (stepMs / 1000).toInt(), left)
            else getString(R.string.status_paused)
    }

    /** One measurement: read current, redraw widgets, show everything here. */
    private fun update() {
        BatteryStore.read(this)?.currentUa?.takeIf { it != 0L }?.let {
            readings.addLast(it)
            while (readings.size > MAX_READINGS) readings.removeFirst()
        }
        val l = BatteryWidgetProvider.refresh(this, readings.toList())
        val accent = if (l.chargeLive) CHARGE else DISCHARGE
        val none = "—"

        levelView.text = l.level?.let { number("$it", "%") } ?: none
        levelBar.progress = (l.level ?: 0) * 10
        levelBar.progressTintList = ColorStateList.valueOf(accent)
        stateView.text = getString(
            when {
                l.full -> R.string.full
                l.chargeLive -> R.string.state_charging
                else -> R.string.state_discharging
            },
        )

        powerLabel.setText(if (l.chargeLive) R.string.label_power_in else R.string.label_power_out)
        // Sign: + energy flows into the battery, − out of it.
        powerView.text = l.watts?.let {
            number(String.format(Locale.getDefault(), "%s%.2f", if (l.chargeLive) "+" else "−", it), getString(R.string.unit_w))
        } ?: none
        powerView.setTextColor(accent)

        voltageView.text = l.volts?.let { number(String.format(Locale.getDefault(), "%.3f", it), getString(R.string.unit_v)) } ?: none
        currentLabel.setText(if (l.chargeLive) R.string.label_current_in else R.string.label_current_out)
        currentView.text = l.ma?.let { number(String.format(Locale.getDefault(), "%,d", Math.round(it)), getString(R.string.unit_ma)) } ?: none
        tempView.text = l.tempC?.let { number(String.format(Locale.getDefault(), "%.1f", it), getString(R.string.unit_c)) } ?: none
        capView.text = l.capMah?.let { number(String.format(Locale.getDefault(), "%,d", Math.round(it)), getString(R.string.unit_mah)) } ?: none

        dischargeView.text = l.discharge
        dischargeView.alpha = if (l.dischargeLive) 1f else 0.55f
        chargeView.text = l.charge
        chargeView.alpha = if (l.chargeLive) 1f else 0.55f
    }

    // ---- view helpers -------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(sizeSp: Float, color: Int, light: Boolean = false) = TextView(this).apply {
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
        fontFeatureSettings = "tnum"   // tabular digits: numbers don't jitter while updating
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(20).toFloat() }
    }

    private fun cardParams(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).also { it.topMargin = dp(top) }

    /** Adds a half-width tile to [row]; returns (label, value). */
    private fun tile(row: LinearLayout, label: String): Pair<TextView, TextView> {
        val c = card()
        val l = text(13f, MUTED).apply { text = label }
        val v = text(28f, TEXT, light = true).apply { setPadding(0, dp(4), 0, 0) }
        c.addView(l)
        c.addView(v)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (row.childCount > 0) lp.marginStart = dp(12)
        row.addView(c, lp)
        return l to v
    }

    /** Big number with a smaller muted unit. */
    private fun number(value: String, unit: String): CharSequence =
        SpannableStringBuilder(value).apply {
            val start = length
            append(" ").append(unit)
            setSpan(RelativeSizeSpan(0.45f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(MUTED), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun button(textRes: Int, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        setText(textRes)
        isAllCaps = false
        setTextColor(fg)
        textSize = 15f
        stateListAnimator = null
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(16).toFloat() }
        minimumHeight = dp(52)
        setOnClickListener { onClick() }
    }

    // ---- actions ------------------------------------------------------------------------------

    private fun pinWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, BatteryWidgetProvider::class.java), null, null)
        } else {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestUnrestricted() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.already_unrestricted, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
