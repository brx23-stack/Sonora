package com.sonora.player.playback

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Owns the system [Equalizer] and [BassBoost] attached to the player's audio session.
 * Settings are saved so they survive restarts and app upgrades.
 * Must be used on the main thread (the service's session callbacks already run there).
 */
class EqualizerController(context: Context, private val player: ExoPlayer) {
    private val prefs = context.getSharedPreferences("sonora_eq", Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var sessionId = 0

    private var on = prefs.getBoolean(KEY_ON, false)
    private var bass = prefs.getInt(KEY_BASS, 0)
    private var preset = prefs.getInt(KEY_PRESET, -1)

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            attach(audioSessionId)
        }
    }

    init {
        player.addListener(listener)
        attach(player.audioSessionId)
    }

    private fun attach(id: Int) {
        if (id == C.AUDIO_SESSION_ID_UNSET || id == 0) return
        if (id == sessionId && equalizer != null) return
        releaseEffects()
        sessionId = id
        equalizer = try { Equalizer(0, id) } catch (e: Exception) { null }
        bassBoost = try { BassBoost(0, id) } catch (e: Exception) { null }
        applySaved()
    }

    private fun applySaved() {
        val eq = equalizer
        if (eq != null) {
            try {
                val saved = prefs.getString(KEY_LEVELS, null)
                    ?.split(",")
                    ?.mapNotNull { it.toIntOrNull() }
                    .orEmpty()
                val bands = eq.numberOfBands.toInt()
                if (saved.size == bands) {
                    val range = eq.bandLevelRange
                    for (b in 0 until bands) {
                        val level = saved[b].coerceIn(range[0].toInt(), range[1].toInt())
                        eq.setBandLevel(b.toShort(), level.toShort())
                    }
                }
                eq.setEnabled(on)
            } catch (e: Exception) {
                // Effect not usable on this device; the UI will still show whatever state() reports.
            }
        }
        applyBass()
    }

    private fun applyBass() {
        val bb = bassBoost ?: return
        try {
            if (bb.strengthSupported) bb.setStrength(bass.toShort())
            bb.setEnabled(on && bass > 0)
        } catch (e: Exception) {
            // Bass boost not supported here.
        }
    }

    private fun saveLevels() {
        val eq = equalizer ?: return
        try {
            val bands = eq.numberOfBands.toInt()
            val levels = (0 until bands).joinToString(",") { eq.getBandLevel(it.toShort()).toString() }
            prefs.edit().putString(KEY_LEVELS, levels).putInt(KEY_PRESET, preset).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun setActive(active: Boolean) {
        on = active
        prefs.edit().putBoolean(KEY_ON, active).apply()
        try { equalizer?.setEnabled(active) } catch (e: Exception) { }
        applyBass()
    }

    fun setBand(band: Int, level: Int) {
        val eq = equalizer ?: return
        try {
            val range = eq.bandLevelRange
            eq.setBandLevel(band.toShort(), level.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            preset = -1
            saveLevels()
        } catch (e: Exception) { }
    }

    fun usePreset(index: Int) {
        val eq = equalizer ?: return
        try {
            eq.usePreset(index.toShort())
            preset = index
            saveLevels()
        } catch (e: Exception) { }
    }

    fun setBass(strength: Int) {
        bass = strength.coerceIn(0, 1000)
        prefs.edit().putInt(KEY_BASS, bass).apply()
        applyBass()
    }

    /** Flattens every band and turns bass boost off. */
    fun reset() {
        val eq = equalizer
        if (eq != null) {
            try {
                for (b in 0 until eq.numberOfBands.toInt()) eq.setBandLevel(b.toShort(), 0)
            } catch (e: Exception) { }
        }
        preset = -1
        saveLevels()
        setBass(0)
    }

    fun state(): Bundle = Bundle().apply {
        val eq = equalizer
        putBoolean(K_AVAILABLE, eq != null)
        putBoolean(K_ON, on)
        putInt(K_PRESET, preset)
        putBoolean(K_BASS_SUPPORTED, try { bassBoost?.strengthSupported == true } catch (e: Exception) { false })
        putInt(K_BASS, bass)
        if (eq != null) {
            try {
                val bands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                putInt(K_MIN, range[0].toInt())
                putInt(K_MAX, range[1].toInt())
                putIntArray(K_FREQS, IntArray(bands) { eq.getCenterFreq(it.toShort()) / 1000 })
                putIntArray(K_LEVELS, IntArray(bands) { eq.getBandLevel(it.toShort()).toInt() })
                val names = ArrayList<String>()
                for (i in 0 until eq.numberOfPresets.toInt()) names.add(eq.getPresetName(i.toShort()))
                putStringArrayList(K_PRESETS, names)
            } catch (e: Exception) {
                putBoolean(K_AVAILABLE, false)
            }
        }
    }

    private fun releaseEffects() {
        try { equalizer?.release() } catch (e: Exception) { }
        try { bassBoost?.release() } catch (e: Exception) { }
        equalizer = null
        bassBoost = null
        sessionId = 0
    }

    fun release() {
        player.removeListener(listener)
        releaseEffects()
    }

    companion object {
        private const val KEY_ON = "on"
        private const val KEY_BASS = "bass"
        private const val KEY_PRESET = "preset"
        private const val KEY_LEVELS = "levels"

        // Keys of the state Bundle sent to the UI.
        const val K_AVAILABLE = "available"
        const val K_ON = "on"
        const val K_PRESET = "preset"
        const val K_PRESETS = "presets"
        const val K_MIN = "min"
        const val K_MAX = "max"
        const val K_FREQS = "freqs"
        const val K_LEVELS = "levels"
        const val K_BASS_SUPPORTED = "bass_supported"
        const val K_BASS = "bass"
    }
}
