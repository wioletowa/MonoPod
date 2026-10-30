package io.github.whyoolw.monopod.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import io.github.whyoolw.monopod.Prefs
import io.github.whyoolw.monopod.SoundCheck

/** The sleep timer fades the music out over its last half minute. */
private const val SLEEP_FADE_MS = 30_000L

/** Volume factor with [leftMs] to go on the sleep timer: full, then a linear fade over the last [SLEEP_FADE_MS]. */
internal fun sleepFade(leftMs: Long): Float = (leftMs.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)

/** Equal-power crossfade: the two songs together stay as loud as one, with no dip halfway. */
internal fun fadeIn(t: Float): Float = kotlin.math.sin(t.coerceIn(0f, 1f) * Math.PI.toFloat() / 2)
internal fun fadeOut(t: Float): Float = kotlin.math.cos(t.coerceIn(0f, 1f) * Math.PI.toFloat() / 2)

/** The crossfade player gets the outgoing song ready this long before the fade starts. */
private const val ARM_AHEAD_MS = 3_000L

/**
 * Everything between the player and the speaker: Sound Check (ReplayGain), crossfade, the
 * equalizer and the sleep timer. Settings come from the shared preferences the menus write.
 *
 * Crossfade uses a second "tail" player: when a song nears its end, the tail takes over the
 * last seconds of it and fades out, while the main player (the one the media session and
 * the notification follow) jumps to the next song and fades in. Without crossfade ExoPlayer
 * plays the queue gaplessly on its own.
 */
class SoundController(context: Context, private val main: ExoPlayer) :
    Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val eq = EqController()
    private val tail = ExoPlayer.Builder(context)
        .setMediaSourceFactory(musicSourceFactory(context))
        // The main player owns audio focus; the tail only plays out a few seconds
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build()

    private var crossfadeMs = 0L
    private var soundCheck = SoundCheck.Off
    private var mainGain = ReplayGain.NONE
    private var tailVolume = 1f

    private var armedIndex = C.INDEX_UNSET
    /** When the outgoing song started fading out (0 = no crossfade running). */
    private var fadeStart = 0L
    /**
     * When the incoming song really started playing. An online song can take seconds to load:
     * its fade-in waits for it, or it would come in already at full volume.
     */
    private var fadeInStart = 0L
    /**
     * True while the crossfade moves the main player to the next song. That is a seek to the
     * player, but the song before did play to its end (My Wave is told it finished).
     */
    var handingOver = false
        private set
    /** The sleep timer last seen, to tell "ran out" from "switched off". */
    private var sleepUntil = 0L

    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            step()
            // Volume, crossfade and the sleep timer only matter while music plays
            ticking = main.isPlaying || fadeStart != 0L
            if (ticking) handler.postDelayed(this, 100)
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.post(tick)
    }

    init {
        main.addListener(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        // Give the player its own audio session up front: until it has one, the equalizer
        // would land on the global output mix (session 0) and colour every app's sound
        main.audioSessionId = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
        attachSession(main.audioSessionId)
        readSettings()
        startTicking()
    }

    fun release() {
        handler.removeCallbacks(tick)
        ticking = false
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        main.removeListener(this)
        eq.release()
        tail.release()
    }

    private fun attachSession(id: Int) {
        Log.i(TAG, "audio session $id")
        // Same session for both players, so the equalizer shapes the crossfade tail too
        tail.audioSessionId = id
        eq.attach(id)
    }

    private fun readSettings() {
        crossfadeMs = prefs.getInt(Prefs.CROSSFADE_SECONDS, 0) * 1000L
        soundCheck = prefs.getString(Prefs.SOUND_CHECK, null)
            ?.let { n -> SoundCheck.entries.firstOrNull { it.name == n } } ?: SoundCheck.Off
        eq.apply(
            EqPreset.gainsFor(
                prefs.getString(Prefs.EQ_PRESET, null),
                EqPreset.parseCustom(prefs.getString(Prefs.EQ_CUSTOM, null)),
                EqPreset.parseUser(prefs.getString(Prefs.EQ_USER, null)),
            ),
        )
        val sleep = prefs.getLong(Prefs.SLEEP_UNTIL, 0L)
        // The alarm cleared a timer that ran out (while Spotify played, say): pause here as well
        if (sleep == 0L && sleepUntil > 0L && sleepUntil <= System.currentTimeMillis() + 1000) main.pause()
        sleepUntil = sleep
        main.pauseAtEndOfMediaItems = sleep == Prefs.SLEEP_END_OF_SONG
        if (sleep > 0) Log.i(TAG, "sleep timer: pause in ${(sleep - System.currentTimeMillis()) / 1000}s")
        if (crossfadeMs == 0L) disarm()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        if (key in WATCHED) readSettings()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) = attachSession(audioSessionId)

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) startTicking()
    }

    override fun onTracksChanged(tracks: Tracks) {
        mainGain = ReplayGain.from(tracks)
        Log.i(TAG, "replaygain $mainGain -> volume ${mainGain.volume(soundCheck)} ($soundCheck)")
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) {
            finishFade()
            disarm()
            // "End of song" fired: the timer is done
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                prefs.getLong(Prefs.SLEEP_UNTIL, 0L) == Prefs.SLEEP_END_OF_SONG
            ) {
                prefs.edit { remove(Prefs.SLEEP_UNTIL) }
            }
        }
    }

    private fun step() {
        val sleepFactor = sleepFactor()
        if (fadeStart != 0L) {
            val now = SystemClock.uptimeMillis()
            if (fadeInStart == 0L && main.isPlaying) fadeInStart = now
            val out = (now - fadeStart).toFloat() / crossfadeMs
            val `in` = if (fadeInStart == 0L) 0f else (now - fadeInStart).toFloat() / crossfadeMs
            main.volume = mainGain.volume(soundCheck) * fadeIn(`in`) * sleepFactor
            tail.volume = tailVolume * fadeOut(out) * sleepFactor
            if (out >= 1f && tail.isPlaying) tail.stop()
            // The next song never came (it failed to load, or the queue ended): stop waiting for it
            val stuck = fadeInStart == 0L && out >= 1f &&
                (main.playbackState == Player.STATE_IDLE || main.playbackState == Player.STATE_ENDED || out >= GIVE_UP)
            if (`in` >= 1f || stuck) finishFade()
            return
        }
        main.volume = mainGain.volume(soundCheck) * sleepFactor
        crossfadeStep()
    }

    /** 1 normally, falling to 0 over the sleep timer's last seconds; pauses when it runs out. */
    private fun sleepFactor(): Float {
        val until = prefs.getLong(Prefs.SLEEP_UNTIL, 0L)
        if (until <= 0L) return 1f
        val left = until - System.currentTimeMillis()
        if (left > 0) return sleepFade(left)
        Log.i(TAG, "sleep timer: pausing")
        main.pause()
        prefs.edit { remove(Prefs.SLEEP_UNTIL) }
        return 1f
    }

    private fun crossfadeStep() {
        val usable = crossfadeMs > 0 && main.isPlaying && main.hasNextMediaItem() &&
            main.repeatMode != Player.REPEAT_MODE_ONE && !main.pauseAtEndOfMediaItems
        val duration = main.duration
        if (!usable || duration == C.TIME_UNSET || duration < crossfadeMs * 2) {
            disarm(); return
        }
        val position = main.currentPosition
        val fadeAt = duration - crossfadeMs
        if (armedIndex != C.INDEX_UNSET && armedIndex != main.currentMediaItemIndex) disarm()
        if (armedIndex == C.INDEX_UNSET && position in (fadeAt - ARM_AHEAD_MS) until fadeAt) {
            // Load the outgoing song's tail ahead of time so the hand-over has no gap
            tail.setMediaItem(main.currentMediaItem ?: return)
            tail.seekTo(fadeAt)
            tail.playWhenReady = false
            tail.prepare()
            armedIndex = main.currentMediaItemIndex
        }
        if (armedIndex != C.INDEX_UNSET && position >= fadeAt) startFade(position)
    }

    private fun startFade(position: Long) {
        // A stream the tail cannot seek in would start from its beginning: plain gapless then
        if (!tail.isCurrentMediaItemSeekable || tail.playbackState != Player.STATE_READY) {
            Log.i(TAG, "crossfade: tail not ready, skipped")
            disarm(); return
        }
        if (kotlin.math.abs(tail.currentPosition - position) > 300) tail.seekTo(position)
        tailVolume = mainGain.volume(soundCheck)
        tail.volume = tailVolume * sleepFactor()
        tail.play()
        main.volume = 0f
        handingOver = true
        try {
            main.seekToNextMediaItem()
        } finally {
            handingOver = false
        }
        armedIndex = C.INDEX_UNSET
        fadeStart = SystemClock.uptimeMillis()
        fadeInStart = 0L
        Log.i(TAG, "crossfade: ${crossfadeMs}ms from $position")
    }

    private fun finishFade() {
        if (fadeStart == 0L) return
        fadeStart = 0L
        fadeInStart = 0L
        tail.stop()
        tail.clearMediaItems()
        main.volume = mainGain.volume(soundCheck)
        Log.i(TAG, "crossfade: done")
    }

    private fun disarm() {
        if (armedIndex == C.INDEX_UNSET) return
        armedIndex = C.INDEX_UNSET
        if (fadeStart == 0L) {
            tail.stop()
            tail.clearMediaItems()
        }
    }

    private companion object {
        const val TAG = "SoundController"
        /** Crossfade lengths to wait for a slow next song before giving up on fading it in. */
        const val GIVE_UP = 4f
        val WATCHED = setOf(Prefs.CROSSFADE_SECONDS, Prefs.SOUND_CHECK, Prefs.EQ_PRESET, Prefs.EQ_CUSTOM, Prefs.EQ_USER, Prefs.SLEEP_UNTIL)
    }
}
