package io.github.whyoolw.monopod.ui.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Phone battery as the status bar shows it: charge 0..1 and whether it is plugged in. */
data class BatteryState(val level: Float, val charging: Boolean)

private fun Intent?.toBattery(): BatteryState {
    if (this == null) return BatteryState(1f, false)
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return BatteryState(
        level = if (level >= 0 && scale > 0) level.toFloat() / scale else 1f,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

/** Follows the sticky battery broadcast while on screen. */
@Composable
fun rememberBattery(): BatteryState {
    val context = LocalContext.current
    val filter = remember { IntentFilter(Intent.ACTION_BATTERY_CHANGED) }
    var state by remember { mutableStateOf(context.registerReceiver(null, filter).toBattery()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                state = intent.toBattery()
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}
