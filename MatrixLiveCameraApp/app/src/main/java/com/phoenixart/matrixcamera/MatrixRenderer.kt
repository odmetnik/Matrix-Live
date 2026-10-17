package com.phoenixart.matrixcamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Immutable frame containing camera luminance in upright display orientation. */
data class LumaFrame(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * Original frame pixels are NEVER drawn. Only characters generated from sampled
 * luminance and edge gradients appear on the Canvas.
 */
class MatrixRenderer {
    private val alphabet = " .,:;irsXA253hMHGS#9B&@".toCharArray()
    // Ordered spatial dithering: neighbouring pixels with similar luminance
    // receive slightly different glyphs, avoiding long monochrome text bands.
    private val bayer4 = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        isSubpixelText = false
    }
    var density: Int = 75
    var speed: Int = 55
    var contrast: Int = 70
    var glow: Boolean = true
    var edges: Boolean = true
    var fps: Int = 30

    @Volatile private var frame: LumaFrame? = null
    private val born = android.os.SystemClock.uptimeMillis()

    fun setFrame(data: LumaFrame) { frame = data }
    fun snapshot(): LumaFrame? = frame?.let { LumaFrame(it.bytes.copyOf(), it.width, it.height) }

    fun render(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.BLACK)
        if (width <= 0 || height <= 0) return

        // Bounded glyph count is important on high-resolution mobile screens.
        val columns = (30 + density.coerceIn(0, 100) * .75f).toInt().coerceIn(24, 110)
        val cellW = width.toFloat() / columns
        val cellH = cellW / 0.56f
        val rows = ceil(height / cellH).toInt().coerceAtLeast(1)
        paint.textSize = cellH * .94f

        val source = frame
        val hasFrame = source != null && source.width > 2 && source.height > 2
        val scale = if (hasFrame) {
            max(width.toFloat() / source!!.width, height.toFloat() / source.height)
        } else 1f
        val offsetX = if (hasFrame) (width - source!!.width * scale) * .5f else 0f
        val offsetY = if (hasFrame) (height - source!!.height * scale) * .5f else 0f
        val time = (android.os.SystemClock.uptimeMillis() - born) / 1000f
        val travel = (3f + speed.coerceIn(0, 100) * .18f) * time
        val variation = (time * 9f).toInt()

        // Soft contrast maps 0..255 onto 0..255 without clipping broad
        // highlight areas into the same '@' character.
        val gain = 1f + contrast.coerceIn(0, 100) / 70f
        val contrastLut = FloatArray(256) { level ->
            val d = level - 127.5f
            (127.5f + d * gain / (1f + kotlin.math.abs(d) *
                (gain - 1f) / 127.5f)).coerceIn(0f, 255f)
        }

        for (col in 0 until columns) {
            val seed = (col.toLong() * 1103515245L + 12345L) and 0x7fffffff
            val phase = (seed % (rows + 23)).toFloat()
            val head = (travel * (0.75f + (col % 7) * .065f) + phase) % (rows + 23) - 10f
            val sx = if (hasFrame) {
                (((col + .5f) * cellW - offsetX) / scale).toInt()
                    .coerceIn(0, source!!.width - 1)
            } else 0
            // One-sample offset in camera-space approximates the width of a glyph.
            val sampleDx = if (hasFrame) max(1, (cellW / scale).toInt()) else 1
            val sampleDy = if (hasFrame) max(1, (cellH / scale).toInt()) else 1
            val leftX = if (hasFrame) (sx - sampleDx).coerceAtLeast(0) else 0
            val rightX = if (hasFrame) (sx + sampleDx).coerceAtMost(source!!.width - 1) else 0

            for (row in 0 until rows) {
                val sy = if (hasFrame) {
                    (((row + .5f) * cellH - offsetY) / scale).toInt()
                        .coerceIn(0, source!!.height - 1)
                } else 0
                val v = if (hasFrame) source!!.bytes[sy * source.width + sx].toInt() and 255 else 0
                // Read a small local neighbourhood. This recovers subtle
                // variations in facial features without exposing the source image.
                var localDetail = 0f
                var edge = 0f
                if (hasFrame) {
                    val data = source!!.bytes
                    val stride = source.width
                    val l = data[sy * stride + leftX].toInt() and 255
                    val r = data[sy * stride + rightX].toInt() and 255
                    val upY = (sy - sampleDy).coerceAtLeast(0)
                    val downY = (sy + sampleDy).coerceAtMost(source.height - 1)
                    val u = data[upY * stride + sx].toInt() and 255
                    val d = data[downY * stride + sx].toInt() and 255
                    val localMean = (v * 4 + l + r + u + d) * .125f
                    localDetail = (v - localMean) * .85f
                    if (edges) {
                        edge = ((abs(r - l) + abs(d - u)) * .43f).coerceAtMost(95f)
                    }
                }
                val adjusted = (contrastLut[v] + localDetail).coerceIn(0f, 255f)

                // A long, luminous stream is visible even in darker regions.
                // Picture brightness stays dominant where a face is visible.
                val distance = head - row
                val tail = if (distance >= 0f && distance < 20f) 1f - distance / 20f else 0f
                val structure = (adjusted * .72f + edge * .52f).coerceAtMost(240f)
                val brightness = if (hasFrame) {
                    (structure + tail * (96f + structure * .23f)).coerceAtMost(255f)
                } else tail * 240f
                if (brightness < 12f) continue

                // Dither is deterministic per cell: no image flicker.
                // Only cells inside falling rain swap glyphs over time.
                val dither = (bayer4[(row and 3) * 4 + (col and 3)] - 7.5f) * .095f
                val toneIndex = (1f + adjusted / 255f * (alphabet.size - 2) +
                    dither + localDetail * .014f).toInt().coerceIn(1, alphabet.size - 1)
                val glyphIndex = if (hasFrame && tail < .25f) {
                    toneIndex
                } else {
                    1 + ((col * 17L + row * 31L + variation * 7L) and 0x7fffffffL)
                        .rem(alphabet.size - 1).toInt()
                }

                val green = brightness.toInt().coerceIn(0, 255)
                paint.color = Color.rgb((green * .11f).toInt(), green, (green * .27f).toInt())
                // Glow only for brighter rain heads / strong edges: cheaper than glow on every glyph.
                val highlight = glow && (tail > .85f || (hasFrame && edges && edge > 80f))
                if (highlight) paint.setShadowLayer(cellW * .5f, 0f, 0f, Color.GREEN)
                else paint.clearShadowLayer()

                canvas.drawText(alphabet, glyphIndex, 1, col * cellW,
                    (row + 1) * cellH - cellH * .08f, paint)
            }
        }
        paint.clearShadowLayer()
    }
}

class MatrixView(context: Context, private val renderer: MatrixRenderer) : View(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.render(canvas, width, height)
        if (isAttachedToWindow) postInvalidateDelayed((1000L / renderer.fps.coerceIn(10, 60)))
    }
}
