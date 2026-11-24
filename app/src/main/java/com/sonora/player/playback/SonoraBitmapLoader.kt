package com.sonora.player.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.sonora.player.R
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Loads album artwork for the media notification, the lock screen and Bluetooth displays.
 *
 * The stock loader only reads the artwork URI as an image file, which does not work for embedded
 * cover art on Android 10+. This loader asks MediaStore for the song's thumbnail instead (the same
 * call the Now Playing screen uses) and falls back to a simple placeholder so the notification
 * never ends up without a picture.
 */
class SonoraBitmapLoader(private val context: Context) : BitmapLoader {
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val cache = LruCache<String, Bitmap>(4)

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit(Callable<Bitmap> {
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IllegalArgumentException("Could not decode artwork")
        })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val key = uri.toString()
        cache.get(key)?.let { return Futures.immediateFuture(it) }
        return executor.submit(Callable<Bitmap> {
            val bitmap = load(uri)
            cache.put(key, bitmap)
            bitmap
        })
    }

    fun release() {
        executor.shutdown()
    }

    private fun load(uri: Uri): Bitmap {
        val loaded: Bitmap? = try {
            if (Build.VERSION.SDK_INT >= 29) {
                // The queue items carry the song's own MediaStore URI as their artwork URI.
                if (uri.authority == MediaStore.AUTHORITY) {
                    context.contentResolver.loadThumbnail(uri, Size(SIZE, SIZE), null)
                } else {
                    null
                }
            } else {
                // Android 9 and older: the classic album-art URI is a readable image.
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        }
        return loaded ?: placeholder()
    }

    private fun placeholder(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF282828.toInt())
        ContextCompat.getDrawable(context, R.drawable.ic_stat_sonora)?.let { icon ->
            val pad = SIZE / 4
            icon.setBounds(pad, pad, SIZE - pad, SIZE - pad)
            icon.setTint(0xFF1ED760.toInt())
            icon.draw(canvas)
        }
        return bitmap
    }

    private companion object {
        const val SIZE = 512
    }
}
