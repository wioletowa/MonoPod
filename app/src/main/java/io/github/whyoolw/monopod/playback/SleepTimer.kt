package io.github.whyoolw.monopod.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import io.github.whyoolw.monopod.Monopod
import io.github.whyoolw.monopod.Prefs
import kotlin.concurrent.thread

/**
 * The sleep timer outside the local player. [SoundController] fades and pauses the local
 * player itself, but only while it plays; Spotify plays in the Spotify app, so an alarm stops
 * it when the time comes, even with Monopod in the background.
 */
class SleepTimer(private val context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
        schedule()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        if (key == Prefs.SLEEP_UNTIL) schedule()
    }

    private fun schedule() {
        val until = prefs.getLong(Prefs.SLEEP_UNTIL, 0L)
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, SleepReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.cancel(intent)
        if (until <= 0L) return
        // Exact: an inexact alarm may come up to three quarters of the timer late (USE_EXACT_ALARM
        // grants it; without it, as when the user revoked it, fall back to inexact)
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, intent)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, intent)
    }
}

/** The sleep timer ran out: Spotify pauses, and clearing the timer pauses the local player too. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val until = prefs.getLong(Prefs.SLEEP_UNTIL, 0L)
        // Changed or switched off since the alarm was set
        if (until <= 0L || until > System.currentTimeMillis() + 1000) return
        Log.i("SleepTimer", "sleep timer: time is up")
        prefs.edit { remove(Prefs.SLEEP_UNTIL) }
        pauseSpotify(context, goAsync())
    }

    companion object {
        /** Pauses Spotify, but only when Monopod was the one playing it: not someone's desktop on the same account. */
        fun pauseSpotify(context: Context, pending: PendingResult? = null) {
            val spotify = (context.applicationContext as Monopod).spotify
            val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            if (!spotify.active && !prefs.getBoolean(Prefs.SPOTIFY_ACTIVE, false)) return run { pending?.finish() }
            spotify.pause()
            if (!spotify.signedIn) return run { pending?.finish() }
            // App Remote may have dropped while in the background; the Web API reaches Spotify anyway.
            // The broadcast stays open until it is done, so Android keeps the process for it
            thread {
                try {
                    runCatching { spotify.api.pausePlayback() }.onFailure { Log.i("SleepTimer", "spotify pause: $it") }
                } finally {
                    pending?.finish()
                }
            }
        }
    }
}
