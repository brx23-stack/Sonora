package com.sonora.player.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import com.sonora.player.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Colours for the Now Playing screen, derived from the current album artwork.
 *
 * Every theme is built so that text stays readable: the background layers are kept dark
 * (see [ArtworkThemes.build]) and the accent and secondary-text colours are pushed towards white
 * until they reach at least 4.5:1 contrast (WCAG AA) against the lightest area of that background.
 * Primary text is always white.
 */
internal data class ArtworkTheme(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val glowA: Color,
    val glowB: Color,
    val accent: Color,
    val accentAlt: Color,
    val mutedText: Color
) {
    companion object {
        /** Used when a track has no artwork, or the artwork has no usable colour. */
        val Fallback: ArtworkTheme = ArtworkThemes.build(
            h1 = 225f, s1 = 0.65f, v1 = 0.70f,
            h2 = 280f, s2 = 0.55f, v2 = 0.65f
        )
    }
}

internal object ArtworkThemes {
    private const val SAMPLE = 48
    private const val HUE_BINS = 36
    private const val GLOW_MAX_LUMINANCE = 0.16f
    private const val MIN_CONTRAST = 4.5f

    private val cache = LruCache<String, ArtworkTheme>(24)

    /** Theme for a song's artwork, cached per song. Call off the main thread. */
    fun themeFor(song: Song, image: ImageBitmap): ArtworkTheme {
        val key = song.id.toString()
        cache.get(key)?.let { return it }
        val theme = try {
            extract(image.asAndroidBitmap())
        } catch (e: Exception) {
            ArtworkTheme.Fallback
        }
        cache.put(key, theme)
        return theme
    }

    private fun extract(source: Bitmap): ArtworkTheme {
        // Copy to a software bitmap first: hardware bitmaps cannot be read pixel by pixel.
        val soft = source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        val small = Bitmap.createScaledBitmap(soft, SAMPLE, SAMPLE, true)
        val px = IntArray(SAMPLE * SAMPLE)
        small.getPixels(px, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)

        // Keep every reasonably colourful pixel; greys, near-black and near-white say little about the "mood".
        val hues = FloatArray(px.size)
        val sats = FloatArray(px.size)
        val vals = FloatArray(px.size)
        var n = 0
        val hsv = FloatArray(3)
        val binWeight = FloatArray(HUE_BINS)
        var colorful = 0f
        for (p in px) {
            if ((p ushr 24) < 128) continue
            android.graphics.Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.15f || hsv[2] < 0.18f) continue
            hues[n] = hsv[0]
            sats[n] = hsv[1]
            vals[n] = hsv[2]
            n++
            val w = hsv[1] * hsv[2]
            binWeight[((hsv[0] / (360f / HUE_BINS)).toInt()) % HUE_BINS] += w
            colorful += w
        }

        // Almost no colour (black and white or grey artwork): calm blue-grey theme.
        if (n == 0 || colorful / px.size < 0.04f) {
            return build(h1 = 225f, s1 = 0.16f, v1 = 0.55f, h2 = 240f, s2 = 0.10f, v2 = 0.45f)
        }

        // Smooth the hue histogram a little so neighbouring bins support each other.
        val smooth = FloatArray(HUE_BINS) { i ->
            binWeight[(i + HUE_BINS - 1) % HUE_BINS] * 0.5f + binWeight[i] +
                binWeight[(i + 1) % HUE_BINS] * 0.5f
        }
        var best = 0
        for (i in 1 until HUE_BINS) if (smooth[i] > smooth[best]) best = i

        var second = -1
        for (i in 0 until HUE_BINS) {
            val d = minOf((i - best + HUE_BINS) % HUE_BINS, (best - i + HUE_BINS) % HUE_BINS)
            if (d >= 4 && (second == -1 || smooth[i] > smooth[second])) second = i
        }
        if (second != -1 && smooth[second] < smooth[best] * 0.3f) second = -1

        val binDeg = 360f / HUE_BINS
        val first = representative(best * binDeg + binDeg / 2f, n, hues, sats, vals)
        val other = if (second != -1) {
            representative(second * binDeg + binDeg / 2f, n, hues, sats, vals)
        } else {
            // Single-colour artwork: use a neighbouring hue for a gentle two-tone gradient.
            Triple((first.first + 35f) % 360f, first.second * 0.85f, first.third * 0.9f)
        }
        return build(first.first, first.second, first.third, other.first, other.second, other.third)
    }

    /** Weighted average hue/saturation/value of all pixels within 20 degrees of [centerHue]. */
    private fun representative(
        centerHue: Float,
        n: Int,
        hues: FloatArray,
        sats: FloatArray,
        vals: FloatArray
    ): Triple<Float, Float, Float> {
        var sumW = 0f
        var sumS = 0f
        var sumV = 0f
        var sumSin = 0f
        var sumCos = 0f
        for (i in 0 until n) {
            var d = abs(hues[i] - centerHue)
            if (d > 180f) d = 360f - d
            if (d > 20f) continue
            val w = sats[i] * vals[i]
            sumW += w
            sumS += sats[i] * w
            sumV += vals[i] * w
            val rad = hues[i] * (PI.toFloat() / 180f)
            sumSin += sin(rad) * w
            sumCos += cos(rad) * w
        }
        if (sumW <= 0f) return Triple(centerHue, 0.5f, 0.6f)
        var hue = atan2(sumSin, sumCos) * (180f / PI.toFloat())
        if (hue < 0f) hue += 360f
        return Triple(hue, sumS / sumW, sumV / sumW)
    }

    /**
     * Turns two artwork colours into a complete theme with guaranteed legibility:
     * background layers stay dark, glows are capped in brightness, and the accent colours and
     * secondary text are brightened until they reach [MIN_CONTRAST] against the lightest background.
     */
    fun build(h1: Float, s1: Float, v1: Float, h2: Float, s2: Float, v2: Float): ArtworkTheme {
        val top = hsv(h1, (s1 * 0.55f).coerceIn(0.12f, 0.55f), 0.22f)
        val bottom = hsv(h1, (s1 * 0.50f).coerceIn(0.10f, 0.50f), 0.07f)

        val glowA = capLuminance(hsv(h1, s1.coerceIn(0.30f, 0.95f), v1.coerceIn(0.40f, 0.85f)))
        val glowB = capLuminance(hsv(h2, s2.coerceIn(0.30f, 0.95f), v2.coerceIn(0.40f, 0.85f)))

        // The lightest thing text can end up on: the top colour with a glow blended in.
        val worstA = mix(top, glowA, 0.5f)
        val worstB = mix(top, glowB, 0.5f)
        val worst = if (worstA.luminance() >= worstB.luminance()) worstA else worstB

        val accent = ensureContrast(hsv(h1, s1.coerceIn(0.30f, 0.85f), max(v1, 0.80f)), worst)
        val accentAlt = ensureContrast(hsv(h2, s2.coerceIn(0.30f, 0.85f), max(v2, 0.80f)), worst)
        val muted = ensureContrast(mix(worst, Color.White, 0.72f), worst)

        return ArtworkTheme(
            backgroundTop = top,
            backgroundBottom = bottom,
            glowA = glowA,
            glowB = glowB,
            accent = accent,
            accentAlt = accentAlt,
            mutedText = muted
        )
    }

    private fun hsv(h: Float, s: Float, v: Float): Color {
        val hue = ((h % 360f) + 360f) % 360f
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
    }

    private fun mix(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f
    )

    /** WCAG contrast ratio between two opaque colours. */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun capLuminance(c: Color): Color {
        var x = c
        var i = 0
        while (x.luminance() > GLOW_MAX_LUMINANCE && i < 24) {
            x = mix(x, Color.Black, 0.08f)
            i++
        }
        return x
    }

    private fun ensureContrast(fg: Color, bg: Color): Color {
        var c = fg
        var i = 0
        while (contrast(c, bg) < MIN_CONTRAST && i < 24) {
            c = mix(c, Color.White, 0.10f)
            i++
        }
        return c
    }
}

/**
 * The theme for [song]'s artwork. Colours are extracted off the main thread and then animated, so a
 * track change fades smoothly from the old palette to the new one. Without artwork the
 * [ArtworkTheme.Fallback] palette is used.
 */
@Composable
internal fun rememberArtworkTheme(song: Song, bitmap: ImageBitmap?): ArtworkTheme {
    val target by produceState(initialValue = ArtworkTheme.Fallback, song.id, bitmap) {
        value = if (bitmap == null) {
            ArtworkTheme.Fallback
        } else {
            withContext(Dispatchers.Default) { ArtworkThemes.themeFor(song, bitmap) }
        }
    }
    return animatedTheme(target)
}

@Composable
private fun animatedTheme(target: ArtworkTheme): ArtworkTheme {
    val top by animateColorAsState(target.backgroundTop, tween(900), label = "themeTop")
    val bottom by animateColorAsState(target.backgroundBottom, tween(900), label = "themeBottom")
    val glowA by animateColorAsState(target.glowA, tween(900), label = "themeGlowA")
    val glowB by animateColorAsState(target.glowB, tween(900), label = "themeGlowB")
    val accent by animateColorAsState(target.accent, tween(900), label = "themeAccent")
    val accentAlt by animateColorAsState(target.accentAlt, tween(900), label = "themeAccentAlt")
    val muted by animateColorAsState(target.mutedText, tween(900), label = "themeMuted")
    return ArtworkTheme(top, bottom, glowA, glowB, accent, accentAlt, muted)
}
