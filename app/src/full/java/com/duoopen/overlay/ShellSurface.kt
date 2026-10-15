package com.duoopen.overlay

import android.animation.ValueAnimator
import android.graphics.RectF
import android.view.animation.DecelerateInterpolator
import com.duoopen.fold.FoldLine
import com.duoopen.settings.DuoConfig
import com.duoopen.shell.ShellRenderer
import com.duoopen.shell.ShizukuBridge

/**
 * The fold drawn by the Shizuku helper straight into SurfaceFlinger (see
 * [ShellRenderer]) instead of an accessibility-overlay window: the helper
 * captures the live screen and runs the shader itself, so nothing here touches
 * WindowManager and the per-frame cost on this side is one one-way binder call.
 */
class ShellSurface(
    private val rendererId: Int,
    private val layerStack: Int,
    private val sourceDisplay: Int,
    private val width: Int,
    private val height: Int,
    private val config: DuoConfig,
    /** Device px per mm on the target panel. */
    private val pxPerMm: Float,
    private val foldLine: (w: Float, h: Float, config: DuoConfig) -> FoldLine,
    shaderSource: String,
    private val renderScale: Float = RENDER_SCALE,
) : FoldSurface {

    private val bw = (width * renderScale).toInt().coerceAtLeast(1)
    private val bh = (height * renderScale).toInt().coerceAtLeast(1)
    private val uniforms = FloatArray(ShellRenderer.UNIFORM_COUNT)
    private var alpha = 1f
    private var fade: ValueAnimator? = null
    private var visible = true

    val attached: Boolean = ShizukuBridge.renderSetup(
        rendererId,
        ShizukuBridge.RenderSetup(
            layerStack = layerStack,
            sourceDisplay = sourceDisplay,
            bufferW = bw,
            bufferH = bh,
            x = 0f,
            y = 0f,
            scale = 1f / renderScale,
            z = Int.MAX_VALUE - 200,
            content = RectF(0f, 0f, bw.toFloat(), bh.toFloat()),
            captureScale = renderScale,
            shaderSource = shaderSource,
        ),
    )

    override var tilt: Float = 0f
        set(value) {
            field = value
            send()
        }

    private fun send() {
        val fold = foldLine(bw.toFloat(), bh.toFloat(), config)
        val pxPerMmBuf = pxPerMm * renderScale
        uniforms[0] = bw.toFloat()
        uniforms[1] = bh.toFloat()
        uniforms[2] = tilt
        uniforms[3] = config.eyeDistanceMm * pxPerMmBuf
        uniforms[4] = fold.position
        uniforms[5] = fold.eyePos
        uniforms[6] = if (fold.splitsX) 0f else 1f
        uniforms[7] = (fold.movingSide ?: config.movingSide).toFloat()
        uniforms[8] = config.blurSpread
        uniforms[9] = config.darkening * REFERENCE_PX_PER_MM / pxPerMmBuf
        ShizukuBridge.renderParams(rendererId, visible, alpha, uniforms)
    }

    override fun fadeIn(durationMs: Long) = animate(0f, 1f, durationMs, null)

    override fun fadeOut(durationMs: Long, onEnd: () -> Unit) = animate(alpha, 0f, durationMs, onEnd)

    private fun animate(from: Float, to: Float, durationMs: Long, onEnd: (() -> Unit)?) {
        fade?.cancel()
        fade = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { alpha = it.animatedValue as Float; send() }
            if (onEnd != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
            })
            start()
        }
    }

    /** Hides the layer; the helper keeps it for the next fold. */
    override fun detach() {
        fade?.cancel()
        visible = false
        ShizukuBridge.renderParams(rendererId, false, alpha, uniforms)
    }

    companion object {
        const val RENDER_SCALE = 0.5f
        private const val REFERENCE_PX_PER_MM = 6f

        @Volatile
        private var source: String? = null

        fun shaderSource(context: android.content.Context): String = source
            ?: context.resources.openRawResource(com.duoopen.R.raw.duo_unfold).bufferedReader().use { it.readText() }
                .also { source = it }
    }
}
