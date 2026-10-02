package com.rrdlab.batterywidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Plug/unplug flips the regime, so take an immediate sample and redraw. */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BatteryWidgetProvider.refresh(context)
    }
}
