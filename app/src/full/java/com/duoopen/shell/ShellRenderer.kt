package com.duoopen.shell

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceControl

/**
 * Draws the fold effect straight into SurfaceFlinger from the shell process —
 * no window, no WindowManager, nothing on the app's main thread:
 *
 *   capture the source display (our own layers excluded, zero-copy)
 *   → AGSL fold shader → a root buffer layer on the target layer stack.
 *
 * One renderer per target (id): 0 = display 0's own effect, 1 = the cover's
 * content projected onto the inner panel while both are lit. Renderers are
 * created once and only shown/hidden per fold, so starting the effect costs a
 * single SurfaceFlinger transaction. The app streams the shader uniforms
 * (computed with the same code as the window overlay) one-way, per frame.
 */
class ShellRenderer(
    val id: Int,
    private val capture: (displayId: Int, excluded: Array<SurfaceControl>, scale: Float) -> Pair<HardwareBuffer, ColorSpace?>?,
    private val excludedLayers: () -> Array<SurfaceControl>,
) {
    data class Setup(
        val layerStack: Int,
        val sourceDisplay: Int,
        /** Buffer size (the effect renders at reduced resolution). */
        val bufferW: Int,
        val bufferH: Int,
        /** Layer placement on the target panel, in panel px, and the upscale back to it. */
        val x: Float,
        val y: Float,
        val scale: Float,
        val z: Int,
        /** Where the captured frame is drawn inside the buffer (buffer px). */
        val content: RectF,
        val captureScale: Float,
        val shaderSource: String,
    )

    private val thread = HandlerThread("duo-render-$id", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private var choreographer: Choreographer? = null

    private var setup: Setup? = null
    var layer: SurfaceControl? = null
        private set
    private var surface: Surface? = null
    private var shader: RuntimeShader? = null
    private var shaderSource: String? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    @Volatile private var visible = false
    @Volatile private var alpha = 1f
    @Volatile private var uniforms = FloatArray(UNIFORM_COUNT)
    private var shownOnLayer = false
    private var frameScheduled = false
    private val retired = ArrayDeque<Bitmap>()
    private var frames = 0
    private var lastLogMs = 0L

    fun configure(s: Setup): Boolean = runOn {
        val prev = setup
        val needNewLayer = layer == null || prev == null || prev.bufferW != s.bufferW || prev.bufferH != s.bufferH ||
            prev.layerStack != s.layerStack
        if (needNewLayer) destroyLayer()
        if (layer == null) {
            val sc = SurfaceControl.Builder()
                .setName("DuoFold-$id")
                .setBufferSize(s.bufferW, s.bufferH)
                .setFormat(PixelFormat.RGBA_8888)
                .setOpaque(false)
                .build()
            layer = sc
            surface = Surface(sc)
            shownOnLayer = false
        }
        val sc = layer!!
        val t = SurfaceControl.Transaction()
        SurfaceControl.Transaction::class.java.getMethod("setLayerStack", SurfaceControl::class.java, Integer.TYPE)
            .invoke(t, sc, s.layerStack)
        t.setLayer(sc, s.z)
        t.setScale(sc, s.scale, s.scale)
        t.setPosition(sc, s.x, s.y)
        t.setVisibility(sc, visible)
        t.apply()
        if (shaderSource != s.shaderSource) {
            shader = runCatching { RuntimeShader(s.shaderSource) }.onFailure { Log.e(TAG, "AGSL compile failed", it) }.getOrNull()
            shaderSource = s.shaderSource
        }
        setup = s
        true
    } ?: false

    /** One-way from the app, every frame of the fold. */
    fun update(visible: Boolean, alpha: Float, values: FloatArray) {
        uniforms = values
        this.alpha = alpha
        val wasVisible = this.visible
        this.visible = visible
        handler.post {
            if (visible != wasVisible || visible != shownOnLayer) applyVisibility()
            if (visible) scheduleFrame()
        }
    }

    fun hide() = update(false, alpha, uniforms)

    private fun applyVisibility() {
        val sc = layer ?: return
        // Show only once a frame has been drawn for this fold (applied in drawFrame).
        if (!visible) {
            SurfaceControl.Transaction().setVisibility(sc, false).apply()
            shownOnLayer = false
        }
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        val c = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        c.postFrameCallback { frameScheduled = false; drawFrame(); if (visible) scheduleFrame() }
    }

    private fun drawFrame() {
        val s = setup ?: return
        val surf = surface ?: return
        val sh = shader ?: return
        if (!visible) return
        val shot = capture(s.sourceDisplay, excludedLayers(), s.captureScale) ?: return
        val (buffer, colorSpace) = shot
        val frame = try {
            Bitmap.wrapHardwareBuffer(buffer, colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
        } finally {
            buffer.close()
        } ?: return
        val u = uniforms
        val content = s.content
        matrix.setRectToRect(RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat()), content, Matrix.ScaleToFit.FILL)
        val image = BitmapShader(frame, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply { setLocalMatrix(matrix) }
        sh.setInputShader("content", image)
        sh.setFloatUniform("resolution", u[0], u[1])
        sh.setFloatUniform("tiltDegrees", u[2])
        sh.setFloatUniform("eyeDistancePx", u[3])
        sh.setFloatUniform("hingePos", u[4])
        sh.setFloatUniform("eyeX", u[5])
        sh.setFloatUniform("axisSwap", u[6])
        sh.setFloatUniform("paneSide", u[7])
        sh.setFloatUniform("blurSpread", u[8])
        sh.setFloatUniform("darkening", u[9])
        val canvas = runCatching { surf.lockHardwareCanvas() }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.BLACK, android.graphics.PorterDuff.Mode.SRC)
            paint.shader = sh
            paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawRect(content, paint)
        } finally {
            surf.unlockCanvasAndPost(canvas)
        }
        if (!shownOnLayer) {
            layer?.let { SurfaceControl.Transaction().setVisibility(it, true).apply() }
            shownOnLayer = true
        }
        // The frame on screen (and the one before) may still be in flight.
        retired.addLast(frame)
        while (retired.size > 3) retired.removeFirst().recycle()
        frames++
        val now = SystemClock.uptimeMillis()
        if (now - lastLogMs > 2_000L) {
            Log.i(TAG, "renderer $id: $frames frames")
            lastLogMs = now
        }
    }

    fun destroy() {
        runOn { destroyLayer(); true }
        thread.quitSafely()
    }

    private fun destroyLayer() {
        val sc = layer ?: return
        runCatching { SurfaceControl.Transaction().setVisibility(sc, false).reparent(sc, null).apply() }
        runCatching { surface?.release() }
        runCatching { sc.release() }
        surface = null
        layer = null
        shownOnLayer = false
        while (retired.isNotEmpty()) retired.removeFirst().recycle()
    }

    /** Runs [block] on the render thread and waits for it (setup only). */
    private fun <T> runOn(block: () -> T): T? {
        if (Thread.currentThread() === thread) return block()
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try { result = block() } catch (t: Throwable) { Log.e(TAG, "renderer $id", t) } finally { latch.countDown() }
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    companion object {
        const val TAG = "DuoRender"
        /** resolution.xy, tilt, eyeDistancePx, hingePos, eyeX, axisSwap, paneSide, blurSpread, darkening */
        const val UNIFORM_COUNT = 10
    }
}
