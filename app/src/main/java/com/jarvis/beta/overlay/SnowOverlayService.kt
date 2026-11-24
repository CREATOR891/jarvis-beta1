package com.jarvis.beta.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.IBinder
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import kotlin.math.sin
import kotlin.random.Random

class SnowOverlayService : Service() {

    private var wm: WindowManager? = null
    private var view: SnowView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (view == null) show()
        return START_STICKY
    }

    private fun show() {
        view = SnowView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm?.addView(view, lp)
    }

    override fun onDestroy() {
        view?.stop()
        view?.let { runCatching { wm?.removeView(it) } }
        view = null
        super.onDestroy()
    }

    // --- Вью с падающим снегом ---

    class SnowView(context: android.content.Context) : SurfaceView(context),
        SurfaceHolder.Callback {

        private var thread: DrawThread? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private data class Flake(
            var x: Float = Random.nextFloat() * 2000f,
            var y: Float = Random.nextFloat() * 4000f,
            val r: Float = 3f + Random.nextFloat() * 6f,
            val speed: Float = 2f + Random.nextFloat() * 5f,
            val drift: Float = -1f + Random.nextFloat() * 2f,
            var phase: Float = Random.nextFloat() * 6.28f
        )

        private val flakes = List(150) { Flake() }

        init {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(this)
        }

        fun stop() {
            thread?.interrupt()
            thread = null
        }

        override fun surfaceCreated(h: SurfaceHolder) {
            thread = DrawThread(h).apply { start() }
        }

        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
        override fun surfaceDestroyed(h: SurfaceHolder) = stop()

        inner class DrawThread(private val h: SurfaceHolder) : Thread() {
            @Volatile
            var running = true

            override fun run() {
                while (running && !isInterrupted) {
                    val c: Canvas = h.lockCanvas() ?: continue
                    try {
                        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        val w = c.width.toFloat()
                        val ht = c.height.toFloat()
                        for (f in flakes) {
                            f.y += f.speed
                            f.phase += 0.02f
                            if (f.y > ht + 10f) {
                                f.y = -10f
                                f.x = Random.nextFloat() * w
                            }
                            val x = (f.x + sin(f.phase) * 30f * f.drift).coerceIn(0f, w)
                            paint.color = Color.WHITE
                            paint.alpha = (140 + f.r * 14f).toInt().coerceAtMost(230)
                            c.drawCircle(x, f.y, f.r, paint)
                        }
                    } finally {
                        h.unlockCanvasAndPost(c)
                    }
                    try {
                        sleep(16)
                    } catch (e: InterruptedException) {
                        running = false
                    }
                }
            }
        }
    }
}
