package com.rrdlab.batterywidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Launcher entry point. Gives the app a visible icon (OEM launchers hide apps without one and
 * keep never-launched apps in the "stopped" state), starts sampling, and offers one-tap setup.
 */
class MainActivity : Activity() {

    private lateinit var levelView: TextView
    private lateinit var dischargeView: TextView
    private lateinit var chargeView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, UPDATE_MS)
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update()
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
        chargeView = TextView(this).apply { textSize = 20f; setPadding(0, pad / 2, 0, pad) }
        root.addView(levelView)
        root.addView(dischargeView)
        root.addView(chargeView)
        root.addView(TextView(this).apply { setText(R.string.main_hint); textSize = 14f })
        root.addView(button(R.string.btn_add_widget) { pinWidget() })
        root.addView(button(R.string.btn_battery) { requestUnrestricted() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        unregisterReceiver(batteryReceiver)
        super.onStop()
    }

    /** Takes a sample, redraws widgets and shows the same two lines on this screen. */
    private fun update() {
        val l = BatteryWidgetProvider.refresh(this)
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
        const val UPDATE_MS = 30_000L
    }
}
