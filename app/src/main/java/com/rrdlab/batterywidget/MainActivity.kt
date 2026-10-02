package com.rrdlab.batterywidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Launcher entry point: visible icon, one-tap setup, and a live view of the same two lines as the widget.
 * Measurement runs only for a minute at a time: 5 s step when the window opens, 1 s step after "Refresh".
 */
class MainActivity : Activity() {

    private lateinit var levelView: TextView
    private lateinit var dischargeView: TextView
    private lateinit var chargeView: TextView
    private lateinit var statusView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val readings = ArrayDeque<Long>()   // instantaneous current, µA; window = last MAX_READINGS
    private var stepMs = OPEN_STEP_MS
    private var endAt = 0L
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            val left = endAt - SystemClock.elapsedRealtime()
            update()
            if (left <= 0) {
                running = false
                showStatus()
                return
            }
            showStatus()
            handler.postDelayed(this, stepMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BatteryWidgetProvider.schedule(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        levelView = TextView(this).apply { textSize = 28f }
        dischargeView = TextView(this).apply { textSize = 20f; setPadding(0, pad / 2, 0, 0) }
        chargeView = TextView(this).apply { textSize = 20f; setPadding(0, pad / 2, 0, pad / 2) }
        statusView = TextView(this).apply { textSize = 13f; alpha = 0.7f }
        root.addView(levelView)
        root.addView(dischargeView)
        root.addView(chargeView)
        root.addView(statusView)
        root.addView(button(R.string.btn_refresh) { start(FAST_STEP_MS) })
        root.addView(button(R.string.btn_add_widget) { pinWidget() })
        root.addView(button(R.string.btn_battery) { requestUnrestricted() })
        root.addView(TextView(this).apply {
            setText(R.string.main_hint); textSize = 14f; setPadding(0, pad, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
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

    /** Starts a [WINDOW_MS] measurement run with the given step; restarts any run in progress. */
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

    /** One measurement: read current, redraw widgets, show the same two lines here. */
    private fun update() {
        BatteryStore.read(this)?.currentUa?.takeIf { it != 0L }?.let {
            readings.addLast(it)
            while (readings.size > MAX_READINGS) readings.removeFirst()
        }
        val l = BatteryWidgetProvider.refresh(this, readings.toList())
        levelView.text = l.level?.let { getString(R.string.level, it) } ?: getString(R.string.no_data)
        dischargeView.text = l.discharge
        dischargeView.alpha = if (l.dischargeLive) 1f else 0.6f
        chargeView.text = l.charge
        chargeView.alpha = if (l.chargeLive) 1f else 0.6f
    }

    private fun button(textRes: Int, onClick: () -> Unit) = Button(this).apply {
        setText(textRes)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).also { it.topMargin = (12 * resources.displayMetrics.density).toInt() }
        setOnClickListener { onClick() }
    }

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

    private companion object {
        const val WINDOW_MS = 60_000L
        const val OPEN_STEP_MS = 5_000L
        const val FAST_STEP_MS = 1_000L
        const val MAX_READINGS = 12   // ~1 min at 5 s, ~12 s at 1 s: enough to average out load spikes
    }
}
