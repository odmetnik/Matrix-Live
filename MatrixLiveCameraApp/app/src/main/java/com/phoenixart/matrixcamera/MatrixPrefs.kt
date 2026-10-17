package com.phoenixart.matrixcamera

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Settings and the optional last frame are kept only in private app storage. */
object MatrixPrefs {
    private const val PREFS = "matrix_settings"
    private const val SAVED_FRAME = "matrix_last_frame.bin"

    fun apply(context: Context, renderer: MatrixRenderer) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        renderer.density = p.getInt("density", 75)
        renderer.speed = p.getInt("speed", 55)
        renderer.contrast = p.getInt("contrast", 70)
        renderer.glow = p.getBoolean("glow", true)
        renderer.edges = p.getBoolean("edges", true)
        renderer.fps = p.getInt("fps", 30).coerceIn(10, 60)
    }

    fun int(context: Context, key: String, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(key, value).apply()
    }

    fun bool(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    fun saveFrame(context: Context, frame: LumaFrame) {
        val file = File(context.filesDir, SAVED_FRAME)
        DataOutputStream(file.outputStream().buffered()).use {
            it.writeInt(frame.width)
            it.writeInt(frame.height)
            it.write(frame.bytes)
        }
    }

    fun readFrame(context: Context): LumaFrame? {
        return try {
            DataInputStream(File(context.filesDir, SAVED_FRAME).inputStream().buffered()).use {
                val w = it.readInt()
                val h = it.readInt()
                if (w !in 1..1000 || h !in 1..1000 || w.toLong() * h > 300_000L) {
                    return null
                }
                LumaFrame(ByteArray(w * h).also { data -> it.readFully(data) }, w, h)
            }
        } catch (_: Exception) {
            null
        }
    }
}
