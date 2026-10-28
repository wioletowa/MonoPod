package io.github.whyoolw.monopod.ui.wheel

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.whyoolw.monopod.R

enum class HapticMode { Auto, Primitive, Pulse }

/** The "tick" of the click wheel: a short vibration and/or the click sound. */
class WheelFeedback(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val touchUsage = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
    private val mediaUsage = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA)
    /** Vibrate on the media channel: some phones damp or mute app touch feedback. */
    var mediaChannel = false
    private val touch get() = if (mediaChannel) mediaUsage else touchUsage

    /** What this phone's vibration motor can do; shown in developer settings. */
    val supportsPrimitives = vibrator.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK,
    )
    val supportsAmplitude = vibrator.hasAmplitudeControl()

    /** Vibration strength, 0..1. */
    var hapticStrength = 0.6f
        set(v) { if (v != field) { field = v; rebuild() } }
    var hapticMode = HapticMode.Auto
        set(v) { if (v != field) { field = v; rebuild() } }
    /** Length of one wheel click's pulse, ms (a button press is a bit longer). */
    var pulseMs = 12
        set(v) { if (v != field) { field = v; rebuild() } }
    /** Click sound volume, 0..1. */
    var soundVolume = 0.6f

    /** The mode actually used: Auto prefers a pulse when the motor can vary its strength. */
    val effectiveMode: HapticMode
        get() = when (hapticMode) {
            HapticMode.Auto -> if (supportsAmplitude || !supportsPrimitives) HapticMode.Pulse else HapticMode.Primitive
            HapticMode.Primitive -> if (supportsPrimitives) HapticMode.Primitive else HapticMode.Pulse
            else -> HapticMode.Pulse
        }

    private var tickEffect = effect(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, pulseMs)
    private var pressEffect = effect(VibrationEffect.Composition.PRIMITIVE_CLICK, pulseMs * 2)

    private fun rebuild() {
        tickEffect = effect(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, pulseMs)
        pressEffect = effect(VibrationEffect.Composition.PRIMITIVE_CLICK, pulseMs * 2)
    }

    /**
     * Primitives are the nicest, but many phones ignore their strength. A pulse with an
     * explicit amplitude is felt the same everywhere; without amplitude control the pulse gets
     * longer instead, which also reads as stronger.
     */
    private fun effect(primitive: Int, ms: Int): VibrationEffect = when (effectiveMode) {
        HapticMode.Primitive -> VibrationEffect.startComposition().addPrimitive(primitive, hapticStrength).compose()
        else -> if (supportsAmplitude) {
            VibrationEffect.createOneShot(ms.toLong(), (hapticStrength * 255).toInt().coerceIn(1, 255))
        } else {
            VibrationEffect.createOneShot((ms * (0.4f + hapticStrength)).toLong().coerceAtLeast(2), VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val clickSound = pool.load(context, R.raw.click, 1)

    var soundEnabled = true
    var hapticsEnabled = true

    /** One wheel step. */
    fun tick() {
        if (hapticsEnabled) vibrator.vibrate(tickEffect, touch)
        if (soundEnabled) pool.play(clickSound, soundVolume, soundVolume, 1, 0, 1f)
    }

    /** A button press: firmer vibration, same click. */
    fun press() {
        if (hapticsEnabled) vibrator.vibrate(pressEffect, touch)
        if (soundEnabled) pool.play(clickSound, (soundVolume * 1.3f).coerceAtMost(1f), (soundVolume * 1.3f).coerceAtMost(1f), 1, 0, 0.8f)
    }

    fun release() = pool.release()
}

@Composable
fun rememberWheelFeedback(): WheelFeedback {
    val context = LocalContext.current
    val feedback = remember { WheelFeedback(context.applicationContext) }
    DisposableEffect(feedback) { onDispose { feedback.release() } }
    return feedback
}
