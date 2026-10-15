package com.duoopen.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.duoopen.fold.DeviceProfile
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.fold.isInnerPanel
import com.duoopen.settings.DuoSettings
import com.duoopen.shell.ShizukuBridge
import android.view.SurfaceControl
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The fold effect on one lit display: screenshot → touch-transparent overlay
 * drawn through the shader → tracks the hinge → removed at rest.
 *
 * One engine runs per live built-in panel (see [FoldOverlayService]). On a
 * phone that only ever lights one panel (OnePlus Open) that's a single engine
 * whose display flips between the cover and inner modes mid-fold; on a phone
 * that keeps both panels on, the cover and inner engines run side by side and
 * the effect hands over with no gap.
 *
 * Opening: leaving closed → capture on the cover → frost sweeps in; the inner
 * panel lights up → capture (retried while the panel is still black) → frost
 * clears to flat. Closing is the reverse. A frozen snapshot for the fraction
 * of a second of a fold is invisible in practice; if the hinge stops partway
 * (tent) the overlay fades out so live content isn't hidden.
 *
 * On a stops-only hinge sensor (Galaxy Z Fold 7 and earlier: 0/90/180) the
 * overlay can't follow the hinge, so each stop change plays a timed ease
 * instead — the same path the close-onto-cover already uses.
 *
 * Two ways to draw ([FoldSurface]): a warped screenshot (default), or — when
 * [com.duoopen.settings.DuoConfig.liveBlur] is on and the system allows
 * cross-window blur — the system blur over the live screen, which needs no
 * capture at all and so starts the instant a phase begins.
 */
class PanelEngine(
    private val service: AccessibilityService,
    val display: Display,
    private val hinge: HingeAngleSource,
    private val handler: Handler,
    private val scope: CoroutineScope,
    /** True when another engine is live on an inner panel right now. */
    private val hasLiveInnerElsewhere: () -> Boolean,
    private val onShowingChanged: () -> Unit,
    /** Last capture of each panel kind, shared by all engines (see [SnapshotCache]). */
    private val cache: SnapshotCache,
) {
    private enum class Phase { IDLE, CAPTURING, SHOWING }

    private val displayId = display.displayId
    private val windowManager: WindowManager by lazy {
        service.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
    }

    private var phase = Phase.IDLE
    private var surface: FoldSurface? = null
    private var follower: TiltFollower? = null

    /** Which panel this display is driving; the effect restarts whenever it flips mid-fold. */
    var innerPanel = display.isInnerPanel()
        private set
    /** Set at a rest pose so leaving it plays once. */
    private var restArmed = true
    private var panelSwitched = false
    private var lastHingeMoveMs = 0L
    private var demoRunning = false
    /** Bumped per capture so a late or hung screenshot can't act on a newer phase. */
    private var captureGen = 0
    /** Attempt number of the capture in flight, so only its own timeout can give up. */
    private var captureAttempt = 0
    /** Overlay is resolving on a timer, ignoring the hinge (see [show]). */
    private var timedResolve = false
    /** Showing a cached picture of this panel while a fresh capture is in flight. */
    private var bridging = false
    /** Live re-capture loop (Shizuku mode) is scheduled. */
    private var liveLoop = false

    val showing: Boolean get() = phase == Phase.SHOWING

    private val settleCheck = object : Runnable {
        override fun run() {
            val o = surface ?: return
            if (o.tilt < DuoShader.FLAT_EPSILON) return
            if (!demoRunning && SystemClock.uptimeMillis() - lastHingeMoveMs >= SETTLE_TIMEOUT_MS) {
                dismiss(fadeMs = FADE_OUT_STALLED_MS)
            } else {
                handler.postDelayed(this, 100)
            }
        }
    }

    /** A timed frost-up that no panel swap has replaced: the fold stalled, let the live screen through. */
    private val peakHold = Runnable {
        if (timedResolve && !demoRunning) dismiss(fadeMs = FADE_OUT_STALLED_MS)
    }

    init {
        // A panel that lights up mid-fold is the second half of a fold in
        // progress; one that is already at rest just waits to leave it.
        val tilt = currentTilt()
        panelSwitched = tilt >= DuoShader.FLAT_EPSILON
        restArmed = !panelSwitched
        Log.i(TAG, "engine display=$displayId inner=$innerPanel midFold=$panelSwitched")
    }

    /** Whether this cover panel is lit alongside an inner panel (no swap will hand over). */
    private fun concurrentCover(): Boolean = !innerPanel && hasLiveInnerElsewhere()

    private fun tiltFor(angle: Float): Float {
        if (angle.isNaN()) return 0f
        val config = DuoSettings.config.value
        return when {
            innerPanel -> DuoShader.tiltForHinge(angle, config)
            concurrentCover() -> DuoShader.concurrentCoverTiltForHinge(angle, config)
            else -> DuoShader.coverTiltForHinge(angle, config)
        }
    }

    private fun currentTilt(): Float = tiltFor(hinge.lastAngle)

    /** Hinge speed in °/s (smoothed), for prediction and the close extrapolation. */
    private var hingeVelocity = 0f
    private var lastSampleAngle = Float.NaN
    private var lastSampleMs = 0L

    private fun trackVelocity(angle: Float, now: Long) {
        if (lastSampleAngle.isFinite() && lastSampleMs > 0L) {
            val dt = (now - lastSampleMs) / 1000f
            if (dt in 0.004f..0.2f) {
                val v = (angle - lastSampleAngle) / dt
                hingeVelocity += (v - hingeVelocity) * VELOCITY_SMOOTHING
            } else if (dt > 0.2f) {
                hingeVelocity = 0f
            }
        }
        lastSampleAngle = angle
        lastSampleMs = now
    }

    /**
     * The sensor (~50 Hz, ~20 ms old) plus the follower's ease leave the frost
     * a few degrees behind the hand; aim [PREDICT_S] ahead along the current
     * speed to cancel most of it. Only while clearly moving.
     */
    private fun predicted(angle: Float): Float =
        if (kotlin.math.abs(hingeVelocity) < PREDICT_MIN_SPEED) angle
        else (angle + hingeVelocity * PREDICT_S).coerceIn(0f, 180f)

    private fun heldCover(): Boolean = !innerPanel && com.duoopen.fold.FoldHoldState.coverHeldUntil.isFinite()

    /**
     * Closing onto a held cover, the hinge sensor goes quiet in the last
     * degrees (the magnets snap it shut), which used to leave the frost hanging
     * until the 0.7 s stall check. If it's falling fast near closed and then
     * goes silent, finish the close ourselves.
     */
    private val closeExtrapolation = Runnable {
        val f = follower ?: return@Runnable
        if (phase != Phase.SHOWING || timedResolve || demoRunning) return@Runnable
        if (SystemClock.uptimeMillis() - lastHingeMoveMs < CLOSE_QUIET_MS) return@Runnable
        Log.i(TAG, "display $displayId: hinge quiet at ${lastSampleAngle}° while closing; finishing the close")
        f.tauS = CLOSE_FINISH_TAU_S
        f.setTarget(0f)
        restArmed = true // the reading is stale; the phone is shut, so the next open starts fresh
    }

    fun onHinge(angle: Float) {
        val now = SystemClock.uptimeMillis()
        lastHingeMoveMs = now
        trackVelocity(angle, now)
        evaluate()
        handler.removeCallbacks(closeExtrapolation)
        if (heldCover() && phase == Phase.SHOWING && angle < CLOSE_EXTRAPOLATE_BELOW && hingeVelocity < -PREDICT_MIN_SPEED) {
            handler.postDelayed(closeExtrapolation, CLOSE_QUIET_MS)
        }
        follower?.let { if (!timedResolve) it.tauS = TiltFollower.DEFAULT_TAU_S }
        val tilt = tiltFor(predicted(angle))
        if (tilt < DuoShader.FLAT_EPSILON && phase == Phase.SHOWING && !demoRunning) {
            val f = follower
            if (timedResolve && hinge.isCoarse && f != null && f.current > DuoShader.FLAT_EPSILON) {
                // Stops-only sensor: the rest stop (180° / 0°) is the first news
                // that the fold has finished, so clear from here — the frost
                // was held meanwhile. The follower dismisses at flat.
                handler.removeCallbacks(peakHold)
                f.tauS = COARSE_CLEAR_TAU_S
                f.setTarget(0f)
            } else {
                // At rest: drop the overlay now rather than easing the last degrees.
                dismiss(fadeMs = FADE_OUT_FLAT_MS)
            }
        } else if (!timedResolve) {
            follower?.setTarget(tilt)
        }
    }

    /**
     * Drives the effect from two signals: which panel this display shows and
     * the hinge angle. The picture is flat at the panel's rest pose and fully
     * frosted at the swap, so an open or a close is one continuous frost-up on
     * the first panel and frost-down on the second. A capture starts on
     * leaving rest and again on each swap.
     */
    /** Last panel kind read from the display; refreshed on display changes only (it's a binder read). */
    private var displayInner = innerPanel

    fun evaluate(readDisplay: Boolean = false) {
        if (demoRunning) return
        if (readDisplay) displayInner = display.isInnerPanel()
        val inner = displayInner
        if (inner != innerPanel) {
            innerPanel = inner
            panelSwitched = true
            if (phase != Phase.IDLE) removeOverlay() // old panel's snapshot is meaningless now
        }
        val angle = hinge.lastAngle
        if (angle.isNaN()) return
        val tilt = tiltFor(angle)
        if (tilt < DuoShader.FLAT_EPSILON) {
            restArmed = true
            panelSwitched = false
            return
        }
        if (phase != Phase.IDLE) return
        when {
            panelSwitched -> {
                panelSwitched = false
                restArmed = false
                Log.i(TAG, "display $displayId: panel swapped (inner=$inner) at hinge=$angle")
                // The fresh panel may still be lighting up: retry if black.
                startEffect(afterSwap = true)
            }
            restArmed && (tilt >= REST_LEAVE_TILT || heldCoverLeavingRest(angle) ||
                (innerPanel && angle <= DuoShader.FLAT_HINGE - INNER_LEAVE_FLAT_DEG)) -> {
                restArmed = false
                Log.i(TAG, "display $displayId: leaving rest (inner=$inner) at hinge=$angle")
                startEffect(afterSwap = false)
            }
        }
    }

    /**
     * While the cover is held lit (see CoverHold) its frost is spread over
     * ~90° of hinge, so a 3° tilt threshold would only fire ~8–20° in. Start
     * from the hinge instead, a degree past the closed idle.
     */
    private fun heldCoverLeavingRest(angle: Float): Boolean =
        !innerPanel && com.duoopen.fold.FoldHoldState.coverHeldUntil.isFinite() &&
            angle >= DuoShader.CLOSED_HINGE + HELD_LEAVE_REST_DEG

    /** The Shizuku helper draws the effect itself (no window, no capture here). */
    private fun shellMode(): Boolean = DuoSettings.config.value.shellRender && ShizukuBridge.ready

    /** Live blur needs no capture: the system blurs whatever is on screen. */
    private fun liveMode(): Boolean =
        DuoSettings.config.value.liveBlur &&
            runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)

    private fun startEffect(afterSwap: Boolean, startTilt: Float? = null) {
        if (shellMode() || liveMode()) {
            phase = Phase.CAPTURING // same gate as a capture in flight, resolved synchronously
            present(bitmap = null, afterSwap = afterSwap, startTilt = startTilt, t0 = SystemClock.uptimeMillis())
            return
        }
        // A panel that has just switched on takes ~0.3 s to screenshot. Start
        // at once with the picture it showed last time and swap in the fresh
        // capture when it lands — the frost hides the difference.
        if (afterSwap && startTilt == null && DuoSettings.config.value.instantStart) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            val stale = cache.get(innerPanel, bounds.width(), bounds.height())
            if (stale != null) {
                phase = Phase.CAPTURING
                Log.i(TAG, "display $displayId: bridging with a ${cache.ageMs(innerPanel)}ms-old snapshot")
                present(stale, afterSwap = true, startTilt = null, t0 = SystemClock.uptimeMillis())
                bridging = phase == Phase.SHOWING
            }
        }
        startCapture(afterSwap, startTilt)
    }

    private fun startCapture(afterSwap: Boolean, startTilt: Float? = null) {
        if (!bridging) phase = Phase.CAPTURING
        capture(gen = ++captureGen, attempt = 1, afterSwap = afterSwap, startTilt = startTilt)
    }

    private fun capture(gen: Int, attempt: Int, afterSwap: Boolean, startTilt: Float?) {
        val t0 = SystemClock.uptimeMillis()
        captureAttempt = attempt
        fun stale() = gen != captureGen || (phase != Phase.CAPTURING && !bridging)
        // The framework refuses captures closer than ~333 ms apart, measured
        // from the previous request — so a slow capture costs no extra wait.
        fun retry() {
            val wait = (t0 + SCREENSHOT_MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed({ if (!stale()) capture(gen, attempt + 1, afterSwap, startTilt) }, wait)
        }
        // A screenshot requested as a panel switches off may never call back.
        // Only the latest attempt's timeout counts: an earlier one must not
        // give up on behalf of a retry that is still in flight.
        handler.postDelayed({
            if (!stale() && captureAttempt == attempt) {
                Log.w(TAG, "display $displayId: capture $attempt timed out; giving up")
                if (bridging) {
                    bridging = false // keep playing on the stale picture
                } else {
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }
        }, CAPTURE_TIMEOUT_MS)
        // Shizuku mode: the shell-side capture has no rate limit and takes
        // ~50 ms even on a waking panel. Falls back to the accessibility
        // screenshot if it fails.
        if (shellCapture()) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    ShizukuBridge.capture(displayId, excludedLayers(), INITIAL_SHELL_SCALE)
                }
                if (stale()) return@launch
                if (bitmap == null) {
                    Log.i(TAG, "display $displayId: shell capture unavailable; using accessibility screenshot")
                    accessibilityCapture(gen, attempt, afterSwap, startTilt, t0, ::retry, ::stale)
                    return@launch
                }
                if (afterSwap && attempt < MAX_CAPTURE_ATTEMPTS) {
                    val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                    if (stale()) return@launch
                    if (black && !demoRunning) {
                        Log.i(TAG, "display $displayId: shell capture $attempt is black after ${SystemClock.uptimeMillis() - t0}ms; retrying")
                        handler.postDelayed({ if (!stale()) capture(gen, attempt + 1, afterSwap, startTilt) }, SHELL_RETRY_MS)
                        return@launch
                    }
                }
                onCaptured(bitmap, afterSwap, startTilt, t0)
            }
            return
        }
        accessibilityCapture(gen, attempt, afterSwap, startTilt, t0, ::retry, ::stale)
    }

    private fun shellCapture(): Boolean = DuoSettings.config.value.shizukuCapture && ShizukuBridge.ready

    /** Our own overlay's layer, so a capture taken while it's up sees the screen beneath it. */
    private fun excludedLayers(): List<SurfaceControl> {
        val view = (surface as? SnapshotSurface)?.view ?: return emptyList()
        return listOfNotNull(rootSurfaceControl(view))
    }

    private fun accessibilityCapture(
        gen: Int,
        attempt: Int,
        afterSwap: Boolean,
        startTilt: Float?,
        t0: Long,
        retry: () -> Unit,
        stale: () -> Boolean,
    ) {
        service.takeScreenshot(displayId, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (stale()) {
                    Log.i(TAG, "display $displayId: stale capture after ${SystemClock.uptimeMillis() - t0}ms; dropped")
                    bitmap?.recycle()
                    return
                }
                if (bitmap == null) {
                    Log.w(TAG, "display $displayId: screenshot buffer could not be wrapped")
                    if (bridging) bridging = false else phase = Phase.IDLE
                    return
                }
                if (!afterSwap || attempt >= MAX_CAPTURE_ATTEMPTS) {
                    onCaptured(bitmap, afterSwap, startTilt, t0)
                    return
                }
                scope.launch {
                    val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                    if (stale()) {
                        bitmap.recycle()
                        return@launch
                    }
                    if (black && !demoRunning) {
                        bitmap.recycle()
                        Log.i(TAG, "display $displayId: capture $attempt is black after ${SystemClock.uptimeMillis() - t0}ms; retrying")
                        retry()
                    } else {
                        onCaptured(bitmap, afterSwap, startTilt, t0)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (stale()) return
                // Secure content (banking, DRM video) and rate limits land here.
                Log.w(TAG, "display $displayId: screenshot failed: $errorCode")
                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT &&
                    attempt < MAX_CAPTURE_ATTEMPTS
                ) {
                    retry()
                } else if (bridging) {
                    bridging = false // secure content etc.: keep playing on the stale picture
                } else {
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }
        })
    }

    private fun onCaptured(bitmap: Bitmap, afterSwap: Boolean, startTilt: Float?, t0: Long) {
        cache.put(innerPanel, bitmap)
        if (bridging) {
            bridging = false
            val s = surface as? SnapshotSurface
            if (s != null && phase == Phase.SHOWING) {
                Log.i(TAG, "display $displayId: fresh capture (${SystemClock.uptimeMillis() - t0}ms) replaces the bridge at tilt=${s.tilt}")
                s.replaceSnapshot(bitmap)
            }
            return
        }
        present(bitmap, afterSwap, startTilt, t0)
    }

    /**
     * Puts the effect on screen: [bitmap] through the shader, or with no
     * bitmap the live blur over whatever is there.
     */
    private fun present(bitmap: Bitmap?, afterSwap: Boolean, startTilt: Float?, t0: Long) {
        if (phase != Phase.CAPTURING) {
            return
        }
        val angle = hinge.lastAngle
        val live = startTilt == null
        val coarse = live && hinge.isCoarse
        val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
        // A stops-only sensor can't be followed. Between stops the fold is in
        // motion, so: frost in on leaving rest and hold; on the fresh panel
        // start frosted and hold; clear only when the rest stop arrives (see
        // onHinge) or the hold cap expires.
        val tilt = startTilt ?: if (coarse) (if (afterSwap) peak else DuoShader.FLAT_EPSILON * 1.2f) else currentTilt()
        // A capture lands late; the live blur is instant, so it's never "too late".
        val profile = DeviceProfile.current
        val nearlyDone = bitmap != null && afterSwap && live &&
            if (innerPanel) angle > profile.skipInnerAboveHinge else angle < profile.skipCoverBelowHinge
        if (tilt < DuoShader.FLAT_EPSILON || nearlyDone) {
            // Too late to be worth a pop-in: the fold is (almost) over.
            Log.i(TAG, "display $displayId: hinge=$angle by capture time (${SystemClock.uptimeMillis() - t0}ms); skipping")
            phase = Phase.IDLE
            return
        }
        // Closing onto a cover that only lights after the swap: the hinge HAL
        // goes quiet around 30°, so the overlay would never hear "closed".
        // Resolve on a timer instead — by the time this capture lands the
        // phone is shut anyway, so it reads as the cover settling into focus.
        // A cover lit alongside the inner panel keeps hearing the hinge.
        val easeTo = when {
            coarse -> peak
            afterSwap && !innerPanel && !concurrentCover() && live && !heldCover() -> 0f
            else -> null
        }
        val how = if (bitmap != null) "${bitmap.width}x${bitmap.height} snapshot (capture ${SystemClock.uptimeMillis() - t0}ms)"
            else if (shellMode()) "shell renderer" else "live blur"
        Log.i(TAG, "display $displayId: showing $how at tilt=$tilt${easeTo?.let { " easing to $it" } ?: ""}${if (bitmap != null && shellCapture()) " [shizuku]" else ""}")
        show(bitmap, tilt, fadeIn = afterSwap, easeTo = easeTo)
    }

    /** Samples a coarse grid; true when nothing on screen is brighter than near-black. */
    private fun isMostlyBlack(hw: Bitmap): Boolean {
        val sw = runCatching { hw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return false
        try {
            val n = 24
            var maxSum = 0
            for (iy in 0 until n) {
                val y = ((iy + 0.5f) * sw.height / n).toInt()
                for (ix in 0 until n) {
                    val x = ((ix + 0.5f) * sw.width / n).toInt()
                    val c = sw.getPixel(x, y)
                    val sum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                    if (sum > maxSum) maxSum = sum
                }
            }
            return maxSum < BLACK_THRESHOLD
        } finally {
            sw.recycle()
        }
    }

    /**
     * [easeTo] non-null plays a timed ease to that tilt, ignoring the hinge
     * until it's back at rest; easing up to a frosted peak holds there
     * briefly, then fades unless a panel swap has replaced it.
     */
    private fun show(bitmap: Bitmap?, startTilt: Float, fadeIn: Boolean = false, easeTo: Float? = null) {
        val inner = innerPanel
        val config = DuoSettings.config.value
        val foldLine = { w: Float, h: Float, c: com.duoopen.settings.DuoConfig -> DuoShader.foldFor(inner, w, h, c) }
        val created: FoldSurface? = if (bitmap != null) {
            SnapshotSurface(service, windowManager, bitmap, config, foldLine).takeIf { it.attached }
        } else if (shellMode()) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            ShellSurface(
                rendererId = SHELL_RENDERER_MAIN,
                layerStack = displayId, // display 0 draws on layer stack 0
                sourceDisplay = displayId,
                width = bounds.width(),
                height = bounds.height(),
                config = config,
                pxPerMm = DuoShader.pxPerMm(service.createDisplayContext(display)),
                foldLine = foldLine,
                shaderSource = ShellSurface.shaderSource(service),
            ).takeIf { it.attached }
        } else {
            LiveBlurSurface(service, windowManager, config, DuoShader.pxPerMm(service.createDisplayContext(display)), foldLine)
                .takeIf { it.attached }
        }
        if (created == null) {
            Log.e(TAG, "display $displayId: could not attach overlay")
            phase = Phase.IDLE
            return
        }
        created.tilt = startTilt
        surface = created
        if (bitmap != null && shellCapture()) startLiveLoop()
        phase = Phase.SHOWING
        onShowingChanged()
        if (fadeIn) {
            // Content was already live on this panel; ease the frost in.
            created.fadeIn(FADE_IN_MS)
        }
        follower = TiltFollower { t ->
            created.tilt = t
            if (t < DuoShader.FLAT_EPSILON && !demoRunning) dismiss(fadeMs = FADE_OUT_FLAT_MS)
        }.also { it.snap(startTilt) }
        lastHingeMoveMs = SystemClock.uptimeMillis()
        timedResolve = easeTo != null
        if (easeTo != null) {
            follower?.tauS = if (hinge.isCoarse) COARSE_EASE_TAU_S else TIMED_RESOLVE_TAU_S
            follower?.setTarget(easeTo)
            if (easeTo > DuoShader.FLAT_EPSILON) {
                handler.postDelayed(peakHold, if (hinge.isCoarse) COARSE_PEAK_HOLD_MS else PEAK_HOLD_MS)
            }
        } else {
            handler.postDelayed(settleCheck, SETTLE_TIMEOUT_MS)
        }
    }

    private fun dismiss(fadeMs: Long) {
        val s = surface ?: return
        Log.i(TAG, "display $displayId: dismiss (fade ${fadeMs}ms) at tilt=${s.tilt}")
        clearOverlayState()
        s.fadeOut(fadeMs) { s.detach() }
    }

    private fun removeOverlay() {
        phase = Phase.IDLE
        timedResolve = false
        val s = surface ?: return
        clearOverlayState()
        s.detach()
    }

    /**
     * Shizuku mode: keep re-capturing the screen beneath the overlay at a
     * reduced scale, so the picture under the frost is live instead of frozen.
     */
    private fun startLiveLoop() {
        if (liveLoop) return
        liveLoop = true
        val myGen = captureGen
        var frames = 0
        // Zero-copy frames pin compositor buffers until released. The one on
        // screen (and the one before it) may still be in a display list the
        // RenderThread is drawing, so only release frames three back.
        val retired = ArrayDeque<android.graphics.Bitmap>()
        fun retire(b: android.graphics.Bitmap) {
            retired.addLast(b)
            while (retired.size > 3) retired.removeFirst().let { if (it.config == android.graphics.Bitmap.Config.HARDWARE) it.recycle() }
        }
        fun tick() {
            if (!liveLoop || phase != Phase.SHOWING || myGen != captureGen) { liveLoop = false; return }
            val s = surface as? SnapshotSurface
            if (s == null || s.tilt < DuoShader.FLAT_EPSILON) { handler.postDelayed({ tick() }, LIVE_IDLE_POLL_MS); return }
            val excluded = excludedLayers()
            if (excluded.isEmpty()) { handler.postDelayed({ tick() }, LIVE_IDLE_POLL_MS); return }
            scope.launch {
                val frame = withContext(Dispatchers.IO) { ShizukuBridge.capture(displayId, excluded, LIVE_SHELL_SCALE) }
                if (liveLoop && phase == Phase.SHOWING && myGen == captureGen && frame != null && !bridging) {
                    (surface as? SnapshotSurface)?.replaceSnapshot(frame)
                    retire(frame)
                    frames++
                    if (frames == 1 || frames % 25 == 0) Log.i(TAG, "display $displayId: live frame $frames (${frame.width}x${frame.height})")
                }
                handler.postDelayed({ tick() }, LIVE_INTERVAL_MS)
            }
        }
        handler.post { tick() }
    }

    private fun clearOverlayState() {
        liveLoop = false
        handler.removeCallbacks(closeExtrapolation)
        handler.removeCallbacks(settleCheck)
        handler.removeCallbacks(peakHold)
        follower?.cancel()
        follower = null
        surface = null
        timedResolve = false
        bridging = false
        phase = Phase.IDLE
        onShowingChanged()
    }

    /** The display went away or the service is stopping. */
    fun destroy() {
        captureGen++ // orphan any capture in flight
        removeOverlay()
        Log.i(TAG, "engine display=$displayId destroyed")
    }

    /**
     * Manual check without folding: snapshot the screen and play what this
     * panel shows during a fold. Inner panel: an unfold from full frost to
     * flat. Cover panel: frost sweeping in (opening) then back out (closing).
     */
    fun playDemo(durationMs: Long = 1400) {
        if (phase != Phase.IDLE || demoRunning) return
        demoRunning = true
        val inner = innerPanel
        val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
        // Frost at the very start so the overlay is visibly there; on the
        // cover it starts flat and sweeps in first.
        startEffect(afterSwap = false, startTilt = if (inner) peak else 0.06f)
        handler.postDelayed({
            val f = follower
            if (f == null) {
                demoRunning = false
                return@postDelayed
            }
            // Slow ease so the demo reads as a fold rather than a snap.
            f.tauS = durationMs / 4000f
            if (inner) {
                f.setTarget(0f)
                handler.postDelayed({
                    demoRunning = false
                    dismiss(fadeMs = FADE_OUT_FLAT_MS)
                }, durationMs)
            } else {
                f.setTarget(peak)
                handler.postDelayed({ f.setTarget(0f) }, durationMs)
                handler.postDelayed({
                    demoRunning = false
                    dismiss(fadeMs = FADE_OUT_FLAT_MS)
                }, durationMs * 2)
            }
        }, 450)
    }

    private companion object {
        const val TAG = "DuoOverlay"
        /** The framework rejects screenshots closer together than ~333 ms. */
        const val SCREENSHOT_MIN_INTERVAL_MS = 340L
        const val MAX_CAPTURE_ATTEMPTS = 3
        /** A screenshot of a panel that is still lighting up can take most of a second. */
        const val CAPTURE_TIMEOUT_MS = 1_100L
        const val BLACK_THRESHOLD = 30
        /** Ease time constant for the timed resolve (≈ 250 ms to settle). */
        const val TIMED_RESOLVE_TAU_S = 0.07f
        /** Slower ease for stops-only sensors, so a frost-in reads as a fold (≈ 450 ms). */
        const val COARSE_EASE_TAU_S = 0.12f
        /** Clear-out once a stops-only sensor reports the rest stop (≈ 300 ms). */
        const val COARSE_CLEAR_TAU_S = 0.08f
        /** How long a timed frost-up stays before it fades, absent a panel swap. */
        const val PEAK_HOLD_MS = 1_200L
        /** Stops-only sensors: hold the frost between stops, but not forever (flex mode). */
        const val COARSE_PEAK_HOLD_MS = 2_500L
        /** Tilt hysteresis for leaving a rest pose, so hinge jitter doesn't fire. */
        const val REST_LEAVE_TILT = 3f
        /** Look-ahead along the hinge speed, roughly the sensor age plus the follower's lag. */
        const val PREDICT_S = 0.03f
        /** °/s below which the hinge counts as still (no prediction, no extrapolation). */
        const val PREDICT_MIN_SPEED = 20f
        const val VELOCITY_SMOOTHING = 0.5f
        /** Held cover closing: below this angle a silent sensor means "it's shutting". */
        const val CLOSE_EXTRAPOLATE_BELOW = 20f
        const val CLOSE_QUIET_MS = 70L
        const val CLOSE_FINISH_TAU_S = 0.05f
        /** Held cover: leave rest this many degrees past the closed idle. */
        const val HELD_LEAVE_REST_DEG = 1f
        /** Inner panel: start closing this many degrees below the flat rest. */
        const val INNER_LEAVE_FLAT_DEG = 2f
        const val SHELL_RENDERER_MAIN = 0
        const val SETTLE_TIMEOUT_MS = 700L
        const val FADE_IN_MS = 140L
        const val FADE_OUT_FLAT_MS = 120L
        const val FADE_OUT_STALLED_MS = 300L
        /** Shizuku capture: full-size first frame, then half-size zero-copy live frames back to back. */
        const val INITIAL_SHELL_SCALE = 1f
        const val LIVE_SHELL_SCALE = 0.5f
        const val LIVE_INTERVAL_MS = 0L
        /** While there's nothing to refresh (flat, or no layer to exclude yet). */
        const val LIVE_IDLE_POLL_MS = 32L
        const val SHELL_RETRY_MS = 60L

        /**
         * The window's root layer (hidden `ViewRootImpl.getSurfaceControl`),
         * needed to exclude our overlay from a display capture. Null if the
         * hidden-API exemption isn't in place.
         */
        fun rootSurfaceControl(view: View): SurfaceControl? = runCatching {
            val root = view.rootView.parent ?: return null
            val sc = root.javaClass.getMethod("getSurfaceControl").invoke(root) as? SurfaceControl
            sc?.takeIf { it.isValid }
        }.getOrNull()
    }
}
