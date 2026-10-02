package com.rrdlab.batterywidget

/** One battery measurement. [uah] is the fuel-gauge charge counter (µAh), 0 if unavailable. */
data class Sample(val t: Long, val uah: Long, val pct: Float, val charging: Boolean)

/** Rate magnitudes (always > 0). [mA] is null when the charge counter is unavailable. */
data class Rate(val pctPerHour: Double, val mA: Double?)

object Estimator {
    private const val WINDOW_MS = 90 * 60_000L   // averaging window: smooths screen-on/off load spikes
    private const val MIN_SPAN_MS = 4 * 60_000L  // shorter spans are dominated by quantization noise
    private const val MIN_RATE_PCT_H = 0.05      // below this the slope is indistinguishable from noise
    private const val MS_PER_HOUR = 3_600_000.0

    /**
     * Least-squares slope of the stored quantity over the trailing run of samples that share the
     * current charging state. Charge counter (µAh, fine resolution) is preferred; otherwise
     * battery percent (1 % resolution) is used.
     *
     * With slope s [µAh/h]: I = |s|/1000 mA, rate = |s|/C·100 %/h, where C is capacity in µAh.
     */
    fun rate(samples: List<Sample>, now: Long, capacityUah: Double?): Rate? {
        val cur = samples.lastOrNull() ?: return null
        val win = samples.filter { it.t >= now - WINDOW_MS }.takeLastWhile { it.charging == cur.charging }
        if (win.size < 2 || win.last().t - win.first().t < MIN_SPAN_MS) return null

        val useUah = capacityUah != null && capacityUah > 0 && win.all { it.uah > 0 }
        val t0 = win.first().t
        val xs = win.map { (it.t - t0) / MS_PER_HOUR }                       // hours
        val ys = win.map { if (useUah) it.uah.toDouble() else it.pct.toDouble() }
        val slope = olsSlope(xs, ys) ?: return null                          // units per hour

        // Physical sign check: discharging ⇒ negative slope, charging ⇒ positive.
        val mag = if (cur.charging) slope else -slope
        val pctPerHour = if (useUah) mag / capacityUah!! * 100.0 else mag
        if (pctPerHour < MIN_RATE_PCT_H) return null
        return Rate(pctPerHour, if (useUah) mag / 1000.0 else null)
    }

    /**
     * Rate from instantaneous current readings (µA, mean of |I| over the given readings; averaging
     * is the low-pass filter against load spikes). Sign conventions differ between vendors, so only
     * the magnitude is used and the regime comes from the charging state. Some firmware reports mA
     * instead of µA: a typical phone draws >= 10 mA = 10 000 µA, so values below that are scaled.
     * Needs the capacity C [µAh] to convert to %/h = I/C·100.
     */
    /** Mean |I| in µA over the readings (see [instantRate] for unit handling); null if none usable. */
    fun meanMagnitudeUa(readingsUa: List<Long>): Double? {
        val mags = readingsUa.map { Math.abs(it).toDouble() }.filter { it > 0 }
            .map { if (it < 10_000) it * 1000 else it }
        return if (mags.isEmpty()) null else mags.average()
    }

    fun instantRate(readingsUa: List<Long>, capacityUah: Double?): Rate? {
        if (capacityUah == null || capacityUah <= 0) return null
        val ua = meanMagnitudeUa(readingsUa) ?: return null
        val pct = ua / capacityUah * 100.0
        return if (pct < MIN_RATE_PCT_H) null else Rate(pct, ua / 1000.0)
    }

    private fun olsSlope(x: List<Double>, y: List<Double>): Double? {
        val n = x.size
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (x[i] - mx) * (y[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
        }
        return if (sxx > 0) sxy / sxx else null
    }

    /** Hours until empty at constant [r]; linear extrapolation. */
    fun hoursToEmpty(pct: Float, r: Rate) = pct / r.pctPerHour

    /** Hours until 100 % at constant [r]. CC-CV taper near full makes this a lower bound. */
    fun hoursToFull(pct: Float, r: Rate) = (100f - pct) / r.pctPerHour
}
