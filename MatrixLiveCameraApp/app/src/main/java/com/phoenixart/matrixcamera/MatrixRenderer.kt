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

        for (col in 0 until columns) {
            val seed = (col.toLong() * 1103515245L + 12345L) and 0x7fffffff
            val phase = (seed % (rows + 23)).toFloat()
            val head = (travel * (0.75f + (col % 7) * .065f) + phase) % (rows + 23) - 10f
            val sx = if (hasFrame) {
                (((col + .5f) * cellW - offsetX) / scale).toInt()
                    .coerceIn(0, source!!.width - 1)
            } else 0

            for (row in 0 until rows) {
                val sy = if (hasFrame) {
                    (((row + .5f) * cellH - offsetY) / scale).toInt()
                        .coerceIn(0, source!!.height - 1)
                } else 0
                val v = if (hasFrame) source!!.bytes[sy * source.width + sx].toInt() and 255 else 0
                val adjusted = ((v - 128f) * (1f + contrast.coerceIn(0,100) / 70f) + 128f)
                    .coerceIn(0f, 255f)
                val edge = if (hasFrame && edges) {
                    val right = source!!.bytes[sy * source.width +
                        (sx + 1).coerceAtMost(source.width - 1)].toInt() and 255
                    val down = source.bytes[(sy + 1).coerceAtMost(source.height - 1) *
                        source.width + sx].toInt() and 255
                    ((abs(v - right) + abs(v - down)) * 1.7f).coerceAtMost(110f)
                } else 0f

                val distance = head - row
                val tail = if (distance >= 0f && distance < 15f) 1f - distance / 15f else 0f
                val brightness = if (hasFrame) {
                    max(adjusted * .82f + edge, tail * 85f)
                } else tail * 235f
                if (brightness < 16f) continue

                val glyphIndex = if (hasFrame) {
                    (adjusted / 255f * (alphabet.size - 1)).toInt()
                        .coerceIn(1, alphabet.size - 1)
                } else {
                    1 + ((col * 17L + row * 31L + variation) and 0x7fffffffL)
                        .rem(alphabet.size - 1).toInt()
                }

                val green = brightness.toInt().coerceIn(0, 255)
                paint.color = Color.rgb((green * .11f).toInt(), green, (green * .27f).toInt())
                // Glow only for brighter rain heads / strong edges: cheaper than glow on every glyph.
                val highlight = glow && (tail > .89f || edge > 85f)
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
