package com.rrdlab.batterywidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import android.widget.FrameLayout
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

        val TOP = Color.parseColor("#1A1250")
        val MID = Color.parseColor("#0F3C63")
        val BOTTOM = Color.parseColor("#08585F")
        val TEXT = Color.parseColor("#F4F7FB")
        val MUTED = Color.parseColor("#B8C4D8")
        val TRACK = Color.parseColor("#26FFFFFF")
        val CHARGE = Color.parseColor("#5CF2B0")
        val DISCHARGE = Color.parseColor("#FFB35C")
    }

    private lateinit var barFill: GradientDrawable
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
        window.statusBarColor = TOP
        window.navigationBarColor = BOTTOM

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
        val track = GradientDrawable().apply { setColor(TRACK); cornerRadius = dp(4).toFloat() }
        barFill = GradientDrawable().apply { setColor(CHARGE); cornerRadius = dp(4).toFloat() }
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = LayerDrawable(arrayOf(track, ClipDrawable(barFill, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
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
        root.addView(button(R.string.btn_refresh, CHARGE, Color.parseColor("#052A3A")) { start(FAST_STEP_MS) }, cardParams(top = 8))
        root.addView(button(R.string.btn_add_widget, 0, TEXT) { pinWidget() }, cardParams(top = 8))
        root.addView(button(R.string.btn_battery, 0, TEXT) { requestUnrestricted() }, cardParams(top = 8))
        root.addView(text(13f, MUTED).apply { setText(R.string.main_hint); setPadding(0, dp(16), 0, 0) })

        val page = FrameLayout(this).apply { background = pageBackground() }
        page.addView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        setContentView(page)
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
        barFill.setColor(accent)
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
        background = glass(24)
    }

    /** Frosted-glass look: light gradient fill + hairline edge. */
    private fun glass(radiusDp: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x33FFFFFF, 0x0DFFFFFF),
    ).apply {
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), 0x40FFFFFF)
    }

    /** Deep gradient with a violet glow top-left and a mint glow bottom-right. */
    private fun pageBackground(): LayerDrawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(TOP, MID, BOTTOM))
        fun glow(cx: Float, cy: Float, color: Int) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(380).toFloat()
            setGradientCenter(cx, cy)
            colors = intArrayOf((0x99 shl 24) or (color and 0xFFFFFF), color and 0xFFFFFF)
        }
        return LayerDrawable(arrayOf(base, glow(0.1f, 0.05f, 0x9B7BFF), glow(0.95f, 0.95f, 0x2CF2C0)))
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
        background = if (bg == CHARGE) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF5CF2B0.toInt(), 0xFF27C9C2.toInt()))
                .apply { cornerRadius = dp(18).toFloat() }
        } else glass(18)
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
