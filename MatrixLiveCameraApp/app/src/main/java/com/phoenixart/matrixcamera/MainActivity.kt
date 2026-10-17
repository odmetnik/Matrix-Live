package com.phoenixart.matrixcamera

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Camera is bound to this visible Activity's lifecycle. WallpaperService NEVER opens it.
 * Front camera is the default; image is horizontally mirrored for a natural selfie view.
 */
class MainActivity : ComponentActivity() {
    private val renderer = MatrixRenderer()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var useFront = true
    private lateinit var status: TextView

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed) startCamera() else status.text = "Dopusti pristup kameri za prikaz uživo."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MatrixPrefs.apply(this, renderer)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            MatrixView(this, renderer),
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val scroller = ScrollView(this).apply { isFillViewport = false }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(14))
            setBackgroundColor(Color.rgb(14, 19, 15))
        }
        scroller.addView(controls)
        root.addView(scroller, LinearLayout.LayoutParams(-1, dp(284)))

        status = TextView(this).apply {
            text = "Matrix Live Camera • prednja kamera"
            setTextColor(Color.GREEN)
            gravity = Gravity.CENTER
            textSize = 14f
        }
        controls.addView(status)

        val cameraRow = LinearLayout(this)
        val swap = Button(this).apply {
            text = "Promijeni kameru"
            setOnClickListener {
                useFront = !useFront
                startCamera()
            }
        }
        val capture = Button(this).apply {
            text = "Spremi kadar"
            setOnClickListener { saveSnapshot() }
        }
        cameraRow.addView(swap, LinearLayout.LayoutParams(0, -2, 1f))
        cameraRow.addView(capture, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(cameraRow)

        controls.addView(Button(this).apply {
            text = "Postavi Matrix Live Wallpaper"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            ComponentName(this@MainActivity, MatrixWallpaperService::class.java)
                        )
                    )
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Sustav nije mogao otvoriti postavke wallpapera: " + e.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        slider(controls, "Gustoća znakova", "density", renderer.density) {
            renderer.density = it
        }
        slider(controls, "Brzina padanja", "speed", renderer.speed) {
            renderer.speed = it
        }
        slider(controls, "Kontrast", "contrast", renderer.contrast) {
            renderer.contrast = it
        }

        controls.addView(CheckBox(this).apply {
            text = "Neonski sjaj"
            setTextColor(Color.WHITE)
            isChecked = renderer.glow
            setOnCheckedChangeListener { _, checked ->
                renderer.glow = checked
                MatrixPrefs.bool(this@MainActivity, "glow", checked)
            }
        })
        controls.addView(CheckBox(this).apply {
            text = "Naglašavanje kontura"
            setTextColor(Color.WHITE)
            isChecked = renderer.edges
            setOnCheckedChangeListener { _, checked ->
                renderer.edges = checked
                MatrixPrefs.bool(this@MainActivity, "edges", checked)
            }
        })

        val fpsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fpsRow.addView(TextView(this).apply {
            text = "Način rada: "
            setTextColor(Color.WHITE)
        })
        val modes = arrayOf("Battery 20 FPS", "Balanced 30 FPS", "Ultra 60 FPS")
        fpsRow.addView(Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                modes
            )
            setSelection(when (renderer.fps) { 20 -> 0; 60 -> 2; else -> 1 })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    renderer.fps = when (position) { 0 -> 20; 2 -> 60; else -> 30 }
                    MatrixPrefs.int(this@MainActivity, "fps", renderer.fps)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })
        controls.addView(fpsRow)
        controls.addView(TextView(this).apply {
            text = "Kamera radi samo u otvorenoj aplikaciji. Wallpaper koristi posljednji spremljeni kadar."
            setTextColor(Color.LTGRAY)
            textSize = 12f
        })

        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun slider(
        root: LinearLayout,
        label: String,
        key: String,
        initial: Int,
        onValue: (Int) -> Unit
    ) {
        val caption = TextView(this).apply {
            text = label + ": " + initial + "%"
            setTextColor(Color.WHITE)
        }
        root.addView(caption)
        root.addView(SeekBar(this).apply {
            max = 100
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        caption.text = label + ": " + value + "%"
                        onValue(value)
                        MatrixPrefs.int(this@MainActivity, key, value)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
    }

    private fun startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()
                val lens = if (useFront) CameraSelector.LENS_FACING_FRONT
                           else CameraSelector.LENS_FACING_BACK
                val selector = CameraSelector.Builder().requireLensFacing(lens).build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        val frame = imageToLuma(image, useFront)
                        renderer.setFrame(frame)
                    } catch (_: Exception) {
                        // A malformed or stale camera frame is discarded safely.
                    } finally {
                        image.close()
                    }
                }
                provider.bindToLifecycle(this, selector, analysis)
                status.text = if (useFront) "Matrix Live Camera • prednja kamera"
                              else "Matrix Live Camera • stražnja kamera"
            } catch (e: Exception) {
                status.text = "Kamera nije dostupna: " + (e.message ?: "greška")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageToLuma(image: ImageProxy, mirror: Boolean): LumaFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val srcW = image.width
        val srcH = image.height
        val rotation = image.imageInfo.rotationDegrees
        val rotated = rotation == 90 || rotation == 270
        val outW = if (rotated) srcH else srcW
        val outH = if (rotated) srcW else srcH
        val step = maxOf(1, (outW + 179) / 180, (outH + 319) / 320)
        val w = (outW + step - 1) / step
        val h = (outH + step - 1) / step
        val bytes = ByteArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x * step).coerceAtMost(outW - 1)
                val dy = (y * step).coerceAtMost(outH - 1)
                val rx = if (mirror) outW - 1 - dx else dx
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> { sx = dy; sy = srcH - 1 - rx }
                    180 -> { sx = srcW - 1 - rx; sy = srcH - 1 - dy }
                    270 -> { sx = srcW - 1 - dy; sy = rx }
                    else -> { sx = rx; sy = dy }
                }
                bytes[y * w + x] = buffer.get(sy * plane.rowStride + sx * plane.pixelStride)
            }
        }
        return LumaFrame(bytes, w, h)
    }

    private fun saveSnapshot() {
        val snapshot = renderer.snapshot()
        if (snapshot == null) {
            Toast.makeText(this, "Pričekaj kadar kamere.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            MatrixPrefs.saveFrame(this, snapshot)
            Toast.makeText(this, "Kadar spremljen za wallpaper.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Spremanje nije uspjelo: " + e.message,
                Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
