package com.tx24.spicyplayer.haptics

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi

/**
 * Plays vibrations straight on the phone's motor: the music haptics. Touch feedback on buttons goes through the view instead (Compose's
 * `LocalHapticFeedback`), which follows the system's touch-feedback setting.
 *
 * Music vibrations are marked as media, so Android's "Media vibration" setting mutes them.
 */
class HapticPlayer(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }?.takeIf { it.hasVibrator() }

    /** Music haptics need amplitude control (Android 8) at the least. */
    val canPlayMusic get() = vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    fun isSupported(primitive: Primitive): Boolean =
        vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(primitive.id)

    /**
     * One music pulse, [strength] 0..1, with [roomMs] before the next:
     * - kick: a low tick, with a thud's body behind it where there's room (~300 ms);
     * - snare: a click; a note: a light tick;
     * - beat: a low tick; downbeat: a click;
     * - accent: a click into a thud; drop: a slow swell into a thud.
     */
    fun play(pulse: MusicPulse, strength: Float, roomMs: Long = Long.MAX_VALUE) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return
        val effect = when (pulse) {
            MusicPulse.Kick -> (if (roomMs >= KICK_BODY_ROOM_MS) composed(s to Primitive.LowTick, s * 0.6f to Primitive.Thud) else null)
                ?: composed(s to Primitive.LowTick) ?: composed(s to Primitive.Tick)
                ?: VibrationEffect.createOneShot(16, amplitude(s * 0.7f))
            MusicPulse.Snare -> composed(s to Primitive.Click) ?: VibrationEffect.createOneShot(10, amplitude(s))
            MusicPulse.Note -> composed(s * 0.8f to Primitive.Tick) ?: VibrationEffect.createOneShot(6, amplitude(s * 0.5f))
            MusicPulse.Beat -> composed(s to Primitive.LowTick) ?: composed(s to Primitive.Tick)
                ?: VibrationEffect.createOneShot(12, amplitude(s * 0.6f))
            MusicPulse.Downbeat -> composed(s to Primitive.Click) ?: VibrationEffect.createOneShot(20, amplitude(s * 0.8f))
            MusicPulse.Accent -> composed(s to Primitive.Click, s to Primitive.Thud) ?: composed(s to Primitive.Click)
                ?: VibrationEffect.createOneShot(40, amplitude(s))
            MusicPulse.Drop -> composed(s * 0.5f to Primitive.SlowRise, s to Primitive.Thud)
                ?: VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 100, 100, 100, 100, 60),
                    intArrayOf(0, amplitude(s * 0.1f), amplitude(s * 0.2f), amplitude(s * 0.3f), amplitude(s * 0.4f), amplitude(s * 0.5f), amplitude(s)),
                    -1,
                )
        }
        vibrate(v, effect, media = true)
    }

    fun stop() {
        vibrator?.cancel()
    }

    /** [parts] played one after another (scale to building block), or null when the phone lacks any of them. */
    private fun composed(vararg parts: Pair<Float, Primitive>): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (parts.any { !isSupported(it.second) }) return null
        return VibrationEffect.startComposition().apply {
            parts.forEach { (scale, primitive) -> addPrimitive(primitive.id, scale.coerceIn(0f, 1f)) }
        }.compose()
    }

    private fun amplitude(strength: Float) = (strength * 255).toInt().coerceIn(1, 255)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun vibrate(v: Vibrator, effect: VibrationEffect, media: Boolean) {
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> v.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(if (media) VibrationAttributes.USAGE_MEDIA else VibrationAttributes.USAGE_TOUCH),
                )
                else -> @Suppress("DEPRECATION") v.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(if (media) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build(),
                )
            }
        }
    }

    // Plain int constants, inlined; each is only played where the phone says it has it.
    /** Android's vibration building blocks (`VibrationEffect.Composition`), . */
    @SuppressLint("InlinedApi")
    enum class Primitive(val id: Int) {
        Tick(VibrationEffect.Composition.PRIMITIVE_TICK),
        LowTick(VibrationEffect.Composition.PRIMITIVE_LOW_TICK),
        Click(VibrationEffect.Composition.PRIMITIVE_CLICK),
        Thud(VibrationEffect.Composition.PRIMITIVE_THUD),
        Spin(VibrationEffect.Composition.PRIMITIVE_SPIN),
        QuickRise(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE),
        SlowRise(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE),
        QuickFall(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL),
    }

    private companion object {
        /** A kick gets a thud's body only with this much quiet after it. */
        const val KICK_BODY_ROOM_MS = 280L
    }
}
