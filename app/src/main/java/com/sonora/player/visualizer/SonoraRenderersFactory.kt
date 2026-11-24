package com.sonora.player.visualizer

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor

/**
 * The stock [DefaultRenderersFactory] with one addition: decoded audio also flows through a
 * [TeeAudioProcessor] that feeds [AudioTap]. The audio itself is passed on unchanged, so playback,
 * the equalizer and the sleep timer behave exactly as before.
 */
class SonoraRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? = DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(AudioTap.sink)))
        .build()
}
