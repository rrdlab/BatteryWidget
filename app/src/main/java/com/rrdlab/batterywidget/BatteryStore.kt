package com.rrdlab.batterywidget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Persists samples and last known rates; reads the current battery state. */
object BatteryStore {
    private const val PREFS = "battery"
    private const val KEY_SAMPLES = "samples"
    private const val KEY_CAP = "cap_uah"
    private const val KEEP_MS = 6 * 3_600_000L
    private const val MAX_SAMPLES = 200
    private const val MIN_GAP_MS = 60_000L

    data class Snapshot(val sample: Sample, val full: Boolean, val systemChargeMs: Long?, val currentUa: Long)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(c: Context, now: Long = System.currentTimeMillis()): Snapshot? {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || full
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val uah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).coerceAtLeast(0)
        val sys = if (charging && android.os.Build.VERSION.SDK_INT >= 28) {
            bm.computeChargeTimeRemaining().takeIf { it > 0 }
        } else null
        // Long.MIN_VALUE = property unsupported
        val ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Long.MIN_VALUE } ?: 0L
        return Snapshot(Sample(now, uah, level * 100f / scale, charging), full, sys, ua)
    }

    @Synchronized
    fun record(c: Context, s: Sample) {
        val list = load(c).toMutableList()
        val last = list.lastOrNull()
        if (last != null && last.charging == s.charging && s.t - last.t < MIN_GAP_MS) return
        list += s
        val pruned = list.filter { it.t >= s.t - KEEP_MS }.takeLast(MAX_SAMPLES)
        prefs(c).edit().putString(KEY_SAMPLES, pruned.joinToString(";") {
            "${it.t},${it.uah},${it.pct},${if (it.charging) 1 else 0}"
        }).apply()
        updateCapacity(c, s)
    }

    @Synchronized
    fun load(c: Context): List<Sample> =
        prefs(c).getString(KEY_SAMPLES, "").orEmpty().split(';').mapNotNull {
            val p = it.split(',')
            if (p.size != 4) null
            else runCatching { Sample(p[0].toLong(), p[1].toLong(), p[2].toFloat(), p[3] == "1") }.getOrNull()
        }

    /** Capacity C = counter / fraction. Rounded percent gives ≤0.5/pct relative error, so only use pct ≥ 30; EMA smooths it. */
    fun capacityUah(c: Context): Double? = prefs(c).getFloat(KEY_CAP, 0f).takeIf { it > 0 }?.toDouble()

    private fun updateCapacity(c: Context, s: Sample) {
        if (s.uah <= 0 || s.pct < 30f) return
        val est = s.uah / (s.pct / 100.0)
        val old = capacityUah(c)
        val new = if (old == null) est else 0.9 * old + 0.1 * est
        prefs(c).edit().putFloat(KEY_CAP, new.toFloat()).apply()
    }

    fun saveRate(c: Context, charging: Boolean, r: Rate) {
        prefs(c).edit()
            .putFloat("pct_$charging", r.pctPerHour.toFloat())
            .putFloat("ma_$charging", (r.mA ?: -1.0).toFloat())
            .apply()
    }

    fun lastRate(c: Context, charging: Boolean): Rate? {
        val p = prefs(c)
        val pct = p.getFloat("pct_$charging", 0f).toDouble()
        if (pct <= 0) return null
        return Rate(pct, p.getFloat("ma_$charging", -1f).takeIf { it > 0 }?.toDouble())
    }
}
