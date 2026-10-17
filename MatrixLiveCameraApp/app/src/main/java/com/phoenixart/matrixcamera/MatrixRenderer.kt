package com.phoenixart.matrixcamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/** Immutable luminance frame, rotated to display orientation by MainActivity. */
data class LumaFrame(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * Two independent layers:
 * 1. Camera image rebuilt from a stable, green ASCII glyph grid.
 * 2. Irregular rain streams: random lateral positions, asynchronous speed,
 *    varying trail lengths, small sideways drift and deterministic gaps.
 *
 * The foreground camera is never accessed by the wallpaper service.
 */
class MatrixRenderer {
    private val alphabet = " .,:;irsXA253hMHGS#9B&@".toCharArray()
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
    private val started = SystemClock.uptimeMillis()

    fun setFrame(data: LumaFrame) { frame = data }
    fun snapshot(): LumaFrame? = frame?.let {
        LumaFrame(it.bytes.copyOf(), it.width, it.height)
    }

    /** Stable, reproducible hash. Does not change between consecutive frames. */
    private fun hash(value: Long): Long {
        var x = (value * 1103515245L + 12345L) and 0x7fffffffL
        x = ((x xor (x ushr 11)) * 16807L) and 0x7fffffffL
        return x
    }

    private fun noise(seed: Long, salt: Long): Float =
        (hash(seed + salt) % 10000L).toFloat() / 10000f

    fun render(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.BLACK)
        if (width <= 0 || height <= 0) return

        val columns = (30 + density.coerceIn(0, 100) * .75f).toInt().coerceIn(24, 110)
        val cellW = width.toFloat() / columns
        val cellH = cellW / .56f
        val rows = ceil(height / cellH).toInt().coerceAtLeast(1)
        paint.textSize = cellH * .94f
        paint.clearShadowLayer()

        val source = frame
        val hasFrame = source != null && source.width > 2 && source.height > 2
        val scale = if (hasFrame) {
            max(width.toFloat() / source!!.width, height.toFloat() / source.height)
        } else 1f
        val offsetX = if (hasFrame) (width - source!!.width * scale) * .5f else 0f
        val offsetY = if (hasFrame) (height - source!!.height * scale) * .5f else 0f
        val sampleDx = if (hasFrame) max(1, (cellW / scale).toInt()) else 1
        val sampleDy = if (hasFrame) max(1, (cellH / scale).toInt()) else 1

        val gain = 1f + contrast.coerceIn(0, 100) / 70f
        val contrastLut = FloatArray(256) { level ->
            val d = level - 127.5f
            (127.5f + d * gain /
                (1f + abs(d) * (gain - 1f) / 127.5f)).coerceIn(0f, 255f)
        }

        // PASS 1: stationary camera glyphs. Image never wobbles with the rain.
        if (hasFrame) {
            val image = source!!
            val pixels = image.bytes
            for (col in 0 until columns) {
                val sx = (((col + .5f) * cellW - offsetX) / scale)
                    .toInt().coerceIn(0, image.width - 1)
                val leftX = (sx - sampleDx).coerceAtLeast(0)
                val rightX = (sx + sampleDx).coerceAtMost(image.width - 1)

                for (row in 0 until rows) {
                    val sy = (((row + .5f) * cellH - offsetY) / scale)
                        .toInt().coerceIn(0, image.height - 1)
                    val v = pixels[sy * image.width + sx].toInt() and 255
                    val l = pixels[sy * image.width + leftX].toInt() and 255
                    val r = pixels[sy * image.width + rightX].toInt() and 255
                    val upY = (sy - sampleDy).coerceAtLeast(0)
                    val downY = (sy + sampleDy).coerceAtMost(image.height - 1)
                    val up = pixels[upY * image.width + sx].toInt() and 255
                    val down = pixels[downY * image.width + sx].toInt() and 255

                    val localMean = (v * 4 + l + r + up + down) * .125f
                    val detail = (v - localMean) * .85f
                    val outline = if (edges)
                        ((abs(r - l) + abs(down - up)) * .43f).coerceAtMost(95f)
                    else 0f
                    val tone = (contrastLut[v] + detail).coerceIn(0f, 255f)
                    val brightness = (tone * .72f + outline * .52f)
                        .coerceIn(0f, 235f)
                    if (brightness < 12f) continue

                    val dither = (bayer4[(row and 3) * 4 + (col and 3)] - 7.5f) * .24f
                    val index = (1f + tone / 255f * (alphabet.size - 2) +
                        dither + detail * .014f).toInt()
                        .coerceIn(1, alphabet.size - 1)
                    val green = brightness.toInt().coerceIn(0, 255)
                    paint.color = Color.rgb((green * .11f).toInt(),
                        green, (green * .27f).toInt())
                    canvas.drawText(alphabet, index, 1, col * cellW,
                        (row + 1) * cellH - cellH * .08f, paint)
                }
            }
        }

        // PASS 2: independently seeded drifting rain streams.
        // No fixed per-column bands: start positions are irregular on the X axis.
        val time = (SystemClock.uptimeMillis() - started) / 1000f
        val baseSpeed = 3.5f + speed.coerceIn(0, 100) * .22f
        val streamCount = (columns * .55f).toInt().coerceIn(14, 70)
        val glyphTick = (time * 6f).toLong()

        for (stream in 0 until streamCount) {
            val seed = hash(stream.toLong() * 7919L + 83L)
            val xBase = noise(seed, 19L) * (width - cellW).coerceAtLeast(0f)
            val speedFactor = .45f + noise(seed, 29L) * 1.35f
            val trailLength = 7 + (noise(seed, 37L) * 27f).toInt()
            val extraGap = 12f + noise(seed, 47L) * (rows * .72f)
            val cycleDistance = rows + trailLength + extraGap
            val cycleProgress = time * baseSpeed * speedFactor +
                noise(seed, 59L) * cycleDistance
            val cycleIndex = (cycleProgress / cycleDistance).toLong()
            val head = (cycleProgress % cycleDistance) - trailLength
            val headRow = head.toInt()
            val swayFreq = .28f + noise(seed, 67L) * 1.12f
            val swayPhase = noise(seed, 71L) * 6.283185f
            val swayAmplitude = cellW * (.14f + noise(seed, 73L) * .52f)

            for (age in 0 until trailLength) {
                val row = headRow - age
                if (row < 0 || row >= rows) continue
                val strength = 1f - age.toFloat() / trailLength

                // Stable per-glyph breaks, do not randomly blink every frame.
                val token = hash(seed + cycleIndex * 104729L + row * 997L)
                if (age > 2 && (token % 100L) < 13L) continue

                val sway = sin(time * swayFreq + row * .19f + swayPhase) * swayAmplitude
                val jitter = ((hash(seed + cycleIndex * 227L + row * 101L) %
                    1000L).toFloat() / 1000f - .5f) * cellW * .48f
                val x = xBase + sway + jitter
                val y = (row + 1) * cellH - cellH * .08f

                val symbolIndex = 1 + (hash(seed + cycleIndex * 71L +
                    row * 127L + glyphTick * 17L) % (alphabet.size - 1)).toInt()
                val green = (36f + strength * 185f).toInt().coerceIn(0, 255)
                val alpha = (90f + strength * 165f).toInt().coerceIn(0, 255)

                if (age == 0) {
                    paint.color = Color.argb(255, 130, 255, 152)
                    if (glow) paint.setShadowLayer(cellW * .72f, 0f, 0f,
                        Color.rgb(20, 255, 65))
                } else {
                    paint.clearShadowLayer()
                    paint.color = Color.argb(alpha, (green * .08f).toInt(),
                        green, (green * .21f).toInt())
                }
                canvas.drawText(alphabet, symbolIndex, 1, x, y, paint)
            }
        }
        paint.clearShadowLayer()
    }
}

class MatrixView(context: Context, private val renderer: MatrixRenderer) : View(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.render(canvas, width, height)
        if (isAttachedToWindow) postInvalidateDelayed(
            1000L / renderer.fps.coerceIn(10, 60)
        )
    }
}
