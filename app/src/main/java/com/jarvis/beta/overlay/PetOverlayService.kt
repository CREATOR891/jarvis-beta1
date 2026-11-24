package com.jarvis.beta.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.IBinder
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import kotlin.math.sin

class PetOverlayService : Service() {

    companion object {
        const val EXTRA_TYPE = "pet_type"
    }

    private var wm: WindowManager? = null
    private var view: PetView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = intent?.getIntExtra(EXTRA_TYPE, 0) ?: 0
        if (view != null) {
            runCatching { wm?.removeView(view) }
            view = null
        }
        show(type)
        return START_STICKY
    }

    private fun show(type: Int) {
        view = PetView(this, type)
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

    // --- Анимированный персонаж ---

    class PetView(context: Context, private val type: Int) : SurfaceView(context),
        SurfaceHolder.Callback {

        private var thread: PetThread? = null

        private var x = 200f
        private var y = 300f
        private var baseY = 300f
        private var vx = 3f
        private var facingRight = true

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
            baseY = h.surfaceFrame.height() * 0.25f
            y = baseY
            thread = PetThread(h).apply { start() }
        }

        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
        override fun surfaceDestroyed(h: SurfaceHolder) = stop()

        inner class PetThread(private val h: SurfaceHolder) : Thread() {
            @Volatile
            var running = true
            private var t = 0L

            override fun run() {
                while (running && !isInterrupted) {
                    val c: Canvas = h.lockCanvas() ?: continue
                    t += 16
                    try {
                        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        val w = c.width.toFloat()
                        x += vx
                        if (x > w - 90f) { vx = -3f; facingRight = false }
                        if (x < 90f) { vx = 3f; facingRight = true }
                        y = baseY + sin(t / 400.0).toFloat() * 40f
                        drawPet(c, x, y, t, facingRight)
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

        private fun drawPet(c: Canvas, cx: Float, cy: Float, t: Long, right: Boolean) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.save()
            c.translate(cx, cy)
            if (!right) c.scale(-1f, 1f)
            when (type) {
                0 -> drawRobot(c, p, t)
                1 -> drawGhost(c, p, t)
                2 -> drawCat(c, p, t)
                else -> drawFish(c, p, t)
            }
            c.restore()
        }

        // 🤖 Робот Джарвис (бесплатный)
        private fun drawRobot(c: Canvas, p: Paint, t: Long) {
            val gold = 0xFFD4AF37.toInt()
            val blue = 0xFF00E5FF.toInt()
            p.style = Paint.Style.FILL
            p.color = gold
            c.drawRoundRect(RectF(-40f, -30f, 40f, 40f), 14f, 14f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = gold
            c.drawLine(0f, -30f, 0f, -48f, p)
            p.style = Paint.Style.FILL
            p.color = blue
            c.drawCircle(0f, -52f, 6f + 2f * sin(t / 200.0).toFloat(), p)
            val blink = if (sin(t / 1500.0) > 0.97) 2f else 10f
            p.color = 0xFF101418.toInt()
            c.drawRoundRect(RectF(-26f, -12f, -10f, -12f + blink * 2), 4f, 4f, p)
            c.drawRoundRect(RectF(10f, -12f, 26f, -12f + blink * 2), 4f, 4f, p)
            p.color = blue
            c.drawCircle(-18f, -6f, 3f, p)
            c.drawCircle(18f, -6f, 3f, p)
            p.color = 0xFF101418.toInt()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            c.drawLine(-12f, 22f, 12f, 22f, p)
        }

        // 👻 Призрак (бесплатный)
        private fun drawGhost(c: Canvas, p: Paint, t: Long) {
            p.style = Paint.Style.FILL
            p.color = 0xCCFFFFFF.toInt()
            val path = Path()
            path.moveTo(-35f, 40f)
            path.lineTo(-35f, -10f)
            path.quadTo(-35f, -45f, 0f, -45f)
            path.quadTo(35f, -45f, 35f, -10f)
            path.lineTo(35f, 40f)
            var px = 35f
            while (px > -35f) {
                path.quadTo(
                    px - 9f, 30f + 10f * sin(t / 200.0 + px).toFloat(), px - 18f, 40f
                )
                px -= 18f
            }
            path.close()
            c.drawPath(path, p)
            p.color = 0xFF1A2340.toInt()
            c.drawCircle(-13f, -12f, 6f, p)
            c.drawCircle(13f, -12f, 6f, p)
        }

        // 🐱 Кот Оксид (Premium / Beta)
        private fun drawCat(c: Canvas, p: Paint, t: Long) {
            val gold = 0xFFE8B84B.toInt()
            p.style = Paint.Style.FILL
            p.color = gold
            c.drawCircle(0f, 0f, 38f, p)
            val lEar = Path()
            lEar.moveTo(-30f, -20f); lEar.lineTo(-42f, -52f); lEar.lineTo(-12f, -38f); lEar.close()
            val rEar = Path()
            rEar.moveTo(30f, -20f); rEar.lineTo(42f, -52f); rEar.lineTo(12f, -38f); rEar.close()
            c.drawPath(lEar, p); c.drawPath(rEar, p)
            p.color = 0xFF00E5FF.toInt()
            c.drawCircle(-14f, -6f, 6f, p)
            c.drawCircle(14f, -6f, 6f, p)
            p.color = 0xFF0A0E14.toInt()
            c.drawCircle(-14f, -6f, 2.5f, p)
            c.drawCircle(14f, -6f, 2.5f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f
            c.drawLine(-8f, 8f, 0f, 14f, p)
            c.drawLine(8f, 8f, 0f, 14f, p)
            c.drawLine(8f, 10f, 34f, 4f, p)
            c.drawLine(8f, 14f, 34f, 16f, p)
            c.drawLine(-8f, 10f, -34f, 4f, p)
            c.drawLine(-8f, 14f, -34f, 16f, p)
        }

        // 🐟 Рыба Неон (Premium / Beta)
        private fun drawFish(c: Canvas, p: Paint, t: Long) {
            val neon = 0xFF00E5FF.toInt()
            p.style = Paint.Style.FILL
            p.color = neon
            c.drawOval(RectF(-38f, -18f, 30f, 18f), p)
            val tail = Path()
            tail.moveTo(-30f, 0f); tail.lineTo(-58f, -18f); tail.lineTo(-58f, 18f); tail.close()
            c.drawPath(tail, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(14f, -4f, 6f, p)
            p.color = 0xFF0A0E14.toInt()
            c.drawCircle(16f, -4f, 3f, p)
            p.color = 0x66FFFFFF.toInt()
            c.drawCircle(46f, -14f, 4f, p)
            c.drawCircle(56f, -24f, 3f, p)
            c.drawCircle(64f, -34f, 2f, p)
        }
    }
}
