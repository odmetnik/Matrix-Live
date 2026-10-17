package com.phoenixart.matrixcamera

import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * The wallpaper never opens the camera. It renders digital rain and (optionally)
 * a frame explicitly saved by the user while the Activity was in the foreground.
 */
class MatrixWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = MatrixEngine()

    private inner class MatrixEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = MatrixRenderer()
        private var isVisible = false

        private val drawTask = object : Runnable {
            override fun run() {
                if (!isVisible) return
                drawFrame()
                handler.postDelayed(this, 1000L / renderer.fps.coerceIn(10, 60))
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            reload()
        }

        private fun reload() {
            MatrixPrefs.apply(this@MatrixWallpaperService, renderer)
            MatrixPrefs.readFrame(this@MatrixWallpaperService)?.let(renderer::setFrame)
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: android.graphics.Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    renderer.render(canvas, canvas.width, canvas.height)
                }
            } catch (_: Exception) {
                // Surface may be temporarily unavailable while wallpaper changes.
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            handler.removeCallbacks(drawTask)
            if (visible) {
                reload()
                handler.post(drawTask)
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder, format: Int, width: Int, height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            if (isVisible) {
                handler.removeCallbacks(drawTask)
                handler.post(drawTask)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(drawTask)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            isVisible = false
            handler.removeCallbacks(drawTask)
            super.onDestroy()
        }
    }
}
