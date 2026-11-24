package com.sonora.player.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sonora.player.visualizer.AudioTap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class VisualizerStyle(val label: String) {
    Bars("Bars"),
    Wave("Wave"),
    Circle("Circle")
}

private const val VIZ_PREFS = "sonora_visualizer"
private const val KEY_ENABLED = "enabled"
private const val KEY_STYLE = "style"

/** About 30 frames per second is plenty for smooth motion and keeps battery use low. */
private const val FRAME_INTERVAL_NS = 30_000_000L

/**
 * Card on the Now Playing screen that shows the music as spectrum bars, a waveform or a circular
 * visualizer. The user can turn it off and pick the style; both choices are remembered.
 *
 * Analysis and drawing only run while the card is on screen, the app is in the foreground and the
 * visualizer is switched on. When playback pauses the display eases to rest and the animation stops.
 */
@Composable
internal fun VisualizerCard(
    isPlaying: Boolean,
    theme: ArtworkTheme,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(VIZ_PREFS, Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(KEY_ENABLED, true)) }
    var style by remember {
        mutableStateOf(
            VisualizerStyle.entries.getOrElse(prefs.getInt(KEY_STYLE, 0)) { VisualizerStyle.Bars }
        )
    }

    var visible by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { foreground = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { foreground = false }
    val running = enabled && visible && foreground

    // The audio tap only analyses while someone can actually see the result.
    DisposableEffect(running) {
        AudioTap.setActive(running)
        onDispose { AudioTap.setActive(false) }
    }

    val bands = remember { FloatArray(AudioTap.BAND_COUNT) }
    val peaks = remember { FloatArray(AudioTap.BAND_COUNT) }
    val wave = remember { FloatArray(AudioTap.WAVE_POINTS) }
    val rawBands = remember { FloatArray(AudioTap.BAND_COUNT) }
    val rawWave = remember { FloatArray(AudioTap.WAVE_POINTS) }
    val path = remember { Path() }
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(running, isPlaying) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (now - last < FRAME_INTERVAL_NS) continue
            last = now

            val live = isPlaying && AudioTap.read(rawBands, rawWave)
            var energy = 0f
            for (i in bands.indices) {
                val target = if (live) rawBands[i] else 0f
                val cur = bands[i]
                // Fast attack, slow release: bars jump up with the beat and fall back gently.
                val next = if (target > cur) cur + (target - cur) * 0.55f else cur + (target - cur) * 0.14f
                bands[i] = next
                peaks[i] = max(next, peaks[i] - 0.02f)
                energy += next + peaks[i]
            }
            for (i in wave.indices) {
                val target = if (live) rawWave[i] else 0f
                wave[i] += (target - wave[i]) * 0.5f
            }
            tick++

            // Paused and fully faded out: stop animating until playback resumes.
            if (!isPlaying && energy < 0.02f) break
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { c ->
                val b = c.boundsInRoot()
                val rootHeight = c.findRootCoordinates().size.height
                visible = b.bottom > 0f && b.top < rootHeight
            }
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Visualizer",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean(KEY_ENABLED, it).apply()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = theme.backgroundBottom,
                    checkedTrackColor = theme.accent
                ),
                modifier = Modifier.semantics { contentDescription = "Visualizer on or off" }
            )
        }

        if (enabled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (option in VisualizerStyle.entries) {
                    FilterChip(
                        selected = style == option,
                        onClick = {
                            style = option
                            prefs.edit().putInt(KEY_STYLE, option.ordinal).apply()
                        },
                        label = { Text(option.label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .semantics { contentDescription = "Audio visualizer, ${style.label} style" }
            ) {
                // Reading the tick here redraws only this canvas on every new frame (no recomposition).
                if (tick >= 0L) {
                    when (style) {
                        VisualizerStyle.Bars -> drawBars(bands, peaks, theme.accent, theme.accentAlt)
                        VisualizerStyle.Wave -> drawWave(wave, path, theme.accent, theme.accentAlt)
                        VisualizerStyle.Circle -> drawRadial(bands, theme.accent, theme.accentAlt)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Text(
                "Off. Turn it on to see the music move.",
                color = theme.mutedText,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
        }
    }
}

private fun DrawScope.drawBars(bands: FloatArray, peaks: FloatArray, accent: Color, accentAlt: Color) {
    val n = bands.size
    val gap = 2.dp.toPx()
    val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
    val minHeight = 3.dp.toPx()
    val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
    val brush = Brush.verticalGradient(listOf(accentAlt, accent), startY = 0f, endY = size.height)
    for (i in 0 until n) {
        val h = max(minHeight, bands[i] * size.height)
        val x = i * (barWidth + gap)
        drawRoundRect(
            brush = brush,
            topLeft = Offset(x, size.height - h),
            size = Size(barWidth, h),
            cornerRadius = radius
        )
        // Small "peak hold" cap that falls slowly above each bar.
        val peakHeight = peaks[i] * size.height
        if (peakHeight > h + 6.dp.toPx()) {
            drawRoundRect(
                color = accentAlt.copy(alpha = 0.7f),
                topLeft = Offset(x, size.height - peakHeight - minHeight),
                size = Size(barWidth, minHeight * 0.8f),
                cornerRadius = radius
            )
        }
    }
}

private fun DrawScope.drawWave(wave: FloatArray, path: Path, accent: Color, accentAlt: Color) {
    val n = wave.size
    val midY = size.height / 2f
    val amplitude = size.height * 0.42f
    val stepX = size.width / (n - 1)
    path.reset()
    path.moveTo(0f, midY - wave[0] * amplitude)
    for (i in 1 until n) {
        path.lineTo(i * stepX, midY - wave[i] * amplitude)
    }
    val brush = Brush.horizontalGradient(listOf(accentAlt, accent, accentAlt), startX = 0f, endX = size.width)
    // Soft wide stroke underneath for a glow, then the crisp line on top.
    drawPath(
        path = path,
        brush = brush,
        alpha = 0.22f,
        style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(
        path = path,
        brush = brush,
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawRadial(bands: FloatArray, accent: Color, accentAlt: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxRadius = min(size.width, size.height) / 2f
    var bass = 0f
    for (i in 0 until 4) bass += bands[i]
    bass /= 4f
    val strokeWidth = 3.dp.toPx()
    // The ring swells with the bass.
    val inner = maxRadius * (0.36f + 0.06f * bass)
    val maxLength = max(strokeWidth, maxRadius - inner - 2.dp.toPx())
    val count = 72
    val half = count / 2
    val brush = Brush.sweepGradient(listOf(accent, accentAlt, accent), center = Offset(cx, cy))
    for (k in 0 until count) {
        // Mirror left and right so the shape stays symmetrical.
        val mirrored = if (k < half) k else count - 1 - k
        val bandIndex = (mirrored * (bands.size - 1)) / (half - 1)
        val length = max(strokeWidth, bands[bandIndex] * maxLength)
        val angle = (2.0 * PI * k / count - PI / 2.0).toFloat()
        val ca = cos(angle)
        val sa = sin(angle)
        drawLine(
            brush = brush,
            start = Offset(cx + inner * ca, cy + inner * sa),
            end = Offset(cx + (inner + length) * ca, cy + (inner + length) * sa),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
    drawCircle(
        color = accent.copy(alpha = 0.35f),
        radius = inner - strokeWidth,
        center = Offset(cx, cy),
        style = Stroke(width = 2.dp.toPx())
    )
}
