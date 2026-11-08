package com.sonora.player.convert

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.File

/**
 * Converts any audio file FFmpeg can read (Opus, M4A/AAC, OGG, FLAC, WAV, WMA...) to MP3
 * using the LAME encoder, then saves the result in Music/Sonora.
 */
object Mp3Converter {
    private val MP3_SAMPLE_RATES = setOf(48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)

    /** Blocking. Call from a background thread. Returns a human-readable save location. */
    fun convert(
        context: Context,
        source: Uri,
        displayName: String,
        bitrateKbps: Int,
        onProgress: (Float) -> Unit
    ): String {
        val baseName = displayName
            .substringBeforeLast('.', displayName)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifEmpty { "audio" }
        val extension = displayName.substringAfterLast('.', "bin")
            .filter { it.isLetterOrDigit() }
            .take(6)
            .ifEmpty { "bin" }

        val stamp = System.nanoTime()
        val input = File(context.cacheDir, "convert_in_$stamp.$extension")
        val output = File(context.cacheDir, "convert_out_$stamp.mp3")
        try {
            // Copy to a real file first: FFmpeg needs a seekable file, and M4A files often
            // keep their index at the end.
            val stream = context.contentResolver.openInputStream(source)
                ?: error("Could not open the selected file")
            stream.use { ins -> input.outputStream().use { out -> ins.copyTo(out) } }

            transcode(input, output, bitrateKbps, onProgress)
            return saveToMusicFolder(context, output, "$baseName.mp3")
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun transcode(input: File, output: File, bitrateKbps: Int, onProgress: (Float) -> Unit) {
        val grabber = FFmpegFrameGrabber(input)
        var recorder: FFmpegFrameRecorder? = null
        try {
            grabber.start()
            if (grabber.audioChannels <= 0) error("No audio found in this file")

            val channels = if (grabber.audioChannels >= 2) 2 else 1
            val sampleRate = if (grabber.sampleRate in MP3_SAMPLE_RATES) grabber.sampleRate else 44100

            val rec = FFmpegFrameRecorder(output, channels)
            recorder = rec
            rec.format = "mp3"
            rec.audioCodec = avcodec.AV_CODEC_ID_MP3
            rec.sampleRate = sampleRate
            rec.audioBitrate = bitrateKbps * 1000
            rec.sampleFormat = avutil.AV_SAMPLE_FMT_FLTP

            // Carry over basic tags when the source has them.
            val tags = grabber.metadata
            for (key in listOf("title", "artist", "album")) {
                tags?.get(key)?.let { value -> rec.setMetadata(key, value) }
            }
            rec.start()

            val totalMicros = grabber.lengthInTime.coerceAtLeast(1L)
            while (true) {
                val frame = grabber.grabSamples() ?: break
                rec.record(frame)
                onProgress((frame.timestamp.toFloat() / totalMicros).coerceIn(0f, 1f))
            }
            rec.stop()
            grabber.stop()
        } finally {
            runCatching { recorder?.release() }
            runCatching { grabber.release() }
        }
    }

    private fun saveToMusicFolder(context: Context, file: File, fileName: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Sonora")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: error("Could not create the output file")
            try {
                val out = resolver.openOutputStream(uri) ?: error("Could not write the output file")
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
                val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return "Music/Sonora/$fileName"
        }

        // Android 8–9: save in the app's own music folder (no storage permission needed).
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Sonora")
        dir.mkdirs()
        var target = File(dir, fileName)
        var n = 1
        while (target.exists()) {
            target = File(dir, fileName.removeSuffix(".mp3") + " ($n).mp3")
            n++
        }
        file.copyTo(target)
        return target.absolutePath
    }
}
