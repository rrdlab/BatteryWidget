package com.rrdlab.batterywidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Locale
import java.util.concurrent.TimeUnit

class BatteryWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) = schedule(context)

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("battery_sampler")
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refresh(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.rrdlab.batterywidget.REFRESH"
        private const val DIM = 0x99FFFFFF.toInt()

        /** Periodic sampler; KEEP makes repeated calls (widget added, app opened) idempotent. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "battery_sampler",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        /** Takes a sample and redraws every widget instance. */
        fun refresh(context: Context, instant: List<Long> = emptyList()): Lines {
            val snap = BatteryStore.read(context)
            if (snap != null) BatteryStore.record(context, snap.sample)
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, BatteryWidgetProvider::class.java))
            val l = lines(context, snap, instant)
            if (ids.isNotEmpty()) {
                val views = render(context, l)
                ids.forEach { mgr.updateAppWidget(it, views) }
            }
            return l
        }

        /** Texts for both lines; shared by the widget and the in-app screen. */
        data class Lines(
            val level: Int?, val discharge: String, val charge: String,
            val dischargeLive: Boolean, val chargeLive: Boolean,
            val volts: Double? = null, val ma: Double? = null, val watts: Double? = null,
            val tempC: Double? = null, val capMah: Double? = null, val full: Boolean = false,
        )

        fun lines(c: Context, snap: BatteryStore.Snapshot?, instantUa: List<Long> = emptyList()): Lines {
            if (snap == null) {
                val n = c.getString(R.string.no_data)
                return Lines(null, n, n, false, false)
            }
            val cur = snap.sample
            val cap = BatteryStore.capacityUah(c)
            // Fast mode (app open) uses averaged instantaneous current; otherwise the slow counter regression.
            val live = Estimator.instantRate(instantUa, cap) ?: Estimator.rate(BatteryStore.load(c), cur.t, cap)
            if (live != null) BatteryStore.saveRate(c, cur.charging, live)
            val dis = if (!cur.charging) live else null
            val chg = if (cur.charging) live else null

            // Line 1: discharge rate + remaining runtime. Active => live value, else last measured (dimmed).
            val disRate = dis ?: BatteryStore.lastRate(c, false)
            val line1 = line(
                c, c.getString(R.string.discharge_prefix), disRate,
                disRate?.let { c.getString(R.string.left, fmt(c, Estimator.hoursToEmpty(cur.pct, it))) },
                if (!cur.charging) c.getString(R.string.measuring) else c.getString(R.string.no_data),
            )

            // Line 2: charge rate + time to 100 %. Prefer the system estimate (it models CC-CV taper).
            val chgRate = chg ?: BatteryStore.lastRate(c, true)
            val toFull = when {
                snap.full -> c.getString(R.string.full)
                cur.charging && snap.systemChargeMs != null ->
                    c.getString(R.string.to_full, fmt(c, snap.systemChargeMs / 3_600_000.0))
                chgRate != null -> c.getString(R.string.to_full, fmt(c, Estimator.hoursToFull(cur.pct, chgRate)))
                else -> null
            }
            val line2 = line(
                c, c.getString(R.string.charge_prefix), chgRate, toFull,
                if (cur.charging) c.getString(R.string.measuring) else c.getString(R.string.no_data),
            )
            
            // P = U·I: terminal voltage times mean current magnitude (direction comes from the charging state).
            val volts = snap.voltageMv.takeIf { it > 0 }?.let { it / 1000.0 }
            val ua = Estimator.meanMagnitudeUa(instantUa.ifEmpty { listOf(snap.currentUa) })
            val watts = if (ua != null && volts != null) ua * 1e-6 * volts else null
            return Lines(
                Math.round(cur.pct), line1, line2, !cur.charging, cur.charging,
                volts, ua?.div(1000.0), watts,
                snap.tempDeci.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 },
                cap?.div(1000.0), snap.full,
            )
        }

        private fun render(c: Context, l: Lines): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_battery)
            val tap = PendingIntent.getBroadcast(
                c, 0,
                Intent(c, BatteryWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.root, tap)
            v.setTextViewText(R.id.line_discharge, l.discharge)
            v.setTextColor(R.id.line_discharge, if (l.dischargeLive) Color.WHITE else DIM)
            v.setTextViewText(R.id.line_charge, l.charge)
            v.setTextColor(R.id.line_charge, if (l.chargeLive) Color.WHITE else DIM)
            return v
        }

        private fun line(c: Context, prefix: String, r: Rate?, forecast: String?, placeholder: String): String {
            if (r == null) return "$prefix: ${forecast ?: placeholder}"
            val parts = mutableListOf(c.getString(R.string.rate_pct, String.format(Locale.getDefault(), "%.1f", r.pctPerHour)))
            r.mA?.let { parts += c.getString(R.string.rate_ma, Math.round(it).toInt()) }
            forecast?.let { parts += it }
            return "$prefix: ${parts.joinToString(" · ")}"
        }

        private fun fmt(c: Context, hours: Double): String {
            val totalMin = Math.round(hours * 60).toInt().coerceAtLeast(0)
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) c.getString(R.string.hours_minutes, h, m) else c.getString(R.string.minutes, m)
        }
    }
}

class SampleWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        BatteryWidgetProvider.refresh(applicationContext)
        return Result.success()
    }
}
