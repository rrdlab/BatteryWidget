# Battery Rate widget (Android, 4×1, resizable)

Line 1 — discharge rate (%/h, mA) and remaining runtime.
Line 2 — charge rate (%/h, mA) and time to 100 %.
The inactive line shows the last measured rate (dimmed). Tap the widget to refresh.

## Method
- Samples: every 15 min (WorkManager) and on plug/unplug.
- Rate: least-squares slope of the fuel-gauge charge counter (µAh) over the last ≤90 min in the
  current state; falls back to battery % if the counter is unavailable. I[mA] = |slope|/1000,
  %/h = |slope|/C·100, C estimated as counter/fraction (EMA).
- Runtime = pct / rate; time to full = system `computeChargeTimeRemaining()` (API 28+, models CC‑CV
  taper), else (100 − pct)/rate (lower bound).
- Needs ≥2 samples spanning ≥4 min before the first estimate appears.

Open this folder in Android Studio (it creates the Gradle wrapper), run, then add the widget.
