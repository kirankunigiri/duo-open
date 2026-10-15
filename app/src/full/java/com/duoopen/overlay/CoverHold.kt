package com.duoopen.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.duoopen.fold.DeviceProfile
import com.duoopen.fold.FoldHoldState
import com.duoopen.fold.isInnerPanel
import com.duoopen.settings.DuoSettings
import com.duoopen.shell.ShizukuBridge
import java.util.concurrent.Executors

/**
 * Keeps the cover lit while the phone opens, so the cover's half of the fold
 * actually plays. On the OPPO Find N6 the system swaps display 0 to the inner
 * panel ~10° into an open; overriding the device state to the cover-only one
 * (same layout as CLOSED) while closed means opening changes nothing until we
 * let go at [com.duoopen.settings.DuoConfig.holdReleaseHinge] — then the usual
 * swap happens there.
 *
 * Holds only while the screen is on: with the screen off, opening must still
 * reach OPENED, which is what wakes the phone. The override lives in the
 * Shizuku shell process, which dies with the app, so a crash can't leave the
 * phone stuck on the cover.
 */
class CoverHold(
    context: Context,
    private val handler: Handler,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "duo-cover-hold") }

    /** Screen on/off, kept from broadcasts so hinge events cost no binder call. */
    var interactive: Boolean = power?.isInteractive == true
    /** Whether display 0 currently shows the inner panel; pushed from display-change events. */
    var innerIsDefault: Boolean = displays.getDisplay(Display.DEFAULT_DISPLAY).isInnerPanel()

    /** What we last asked the shell for (main thread only). */
    var holding = false
        private set
    private var lastAssertMs = 0L

    private val profileState: Int? get() = DeviceProfile.current.coverHoldState
    private val dualState: Int? get() = DeviceProfile.current.dualState

    /** Both panels lit with the cover mirrored onto the inner one (main thread only). */
    var dual = false
        private set
    /** Set by a fresh at-rest reading; a stale angle left over from a close must not light the inner panel. */
    private var dualArmed = false

    private fun enabled(): Boolean =
        profileState != null && DuoSettings.config.value.holdCover && ShizukuBridge.ready

    private fun dualEnabled(): Boolean = dualState != null && DuoSettings.config.value.dualScreen

    private fun releaseAngle(): Float =
        if (dualEnabled()) DuoSettings.config.value.dualReleaseHinge else DuoSettings.config.value.holdReleaseHinge

    /** Re-evaluate on every hinge reading, display change and screen on/off. */
    /** adb debug: pin the dual state regardless of the hinge until turned off. */
    private var debugPinned = false

    fun update(angle: Float) {
        if (debugPinned) return
        val release = releaseAngle()
        val coverIsDefault = !innerIsDefault
        val want = enabled() && interactive && coverIsDefault &&
            (angle.isNaN() || angle < release)
        if (dual) {
            when {
                !want && angle.isFinite() && angle >= release -> handover(angle)
                !want -> exitDual(toHold = false, reason = if (!interactive) "screen off" else "lost the cover")
                angle.isFinite() && angle <= REASSERT_BELOW_HINGE -> exitDual(toHold = true, reason = "closed again at ${angle.fmt()}°")
            }
            return
        }
        if (holding && angle.isFinite() && angle <= REASSERT_BELOW_HINGE) dualArmed = true
        if (want && holding && dualArmed && dualEnabled() && angle.isFinite() &&
            angle >= DeviceProfile.current.closedHinge + DUAL_ENTER_PAST_CLOSED_DEG
        ) {
            dualArmed = false
            enterDual(angle)
            return
        }
        when {
            want && !holding -> hold("cover is display 0 at ${angle.fmt()}°")
            want && holding && angle.isFinite() && angle <= REASSERT_BELOW_HINGE -> {
                // Closing back without reaching the release angle returns the
                // base state to CLOSED, which cancels overrides; ask again.
                val now = SystemClock.uptimeMillis()
                if (now - lastAssertMs >= REASSERT_INTERVAL_MS) hold(null)
            }
            !want && holding -> release(
                when {
                    angle.isFinite() && angle >= release -> "reached ${angle.fmt()}° (release at ${release.fmt()}°)"
                    !interactive -> "screen off"
                    !coverIsDefault -> "inner is display 0"
                    else -> "disabled"
                },
                handedOverAt = if (angle.isFinite() && angle >= release) angle else Float.NaN,
            )
        }
        if (!holding && coverIsDefault && angle.isFinite() && angle <= REASSERT_BELOW_HINGE) {
            FoldHoldState.innerStartHinge = Float.NaN // closed again: next open starts fresh
        }
    }

    /** Leaving closed: light the inner panel too (cover stays display 0, so no swap) and mirror the cover onto it. */
    private fun enterDual(angle: Float) {
        val state = dualState ?: return
        dual = true
        Log.i(TAG, "dual on at ${angle.fmt()}°: state $state + mirror")
        worker.execute {
            val ok = ShizukuBridge.holdState(state)
            val m = if (ok) ShizukuBridge.startMirror(Display.DEFAULT_DISPLAY) else null
            handler.post {
                if (!ok) { Log.w(TAG, "dual state $state failed"); dual = false }
                else Log.i(TAG, "mirror ${if (m?.getBoolean("ok") == true) "on display ${m.getInt("target")} (layer stack ${m.getInt("layerStack")}, scale ${m.getFloat("scale")})" else "failed: ${m?.getString("error")}"}")
            }
        }
    }

    /** Opened far enough: drop the mirror and hand the screen over to the inner panel, the cheap way. */
    private fun handover(angle: Float) {
        val coverOnly = profileState ?: return
        dual = false
        holding = false
        FoldHoldState.coverHeldUntil = Float.NaN
        FoldHoldState.innerStartHinge = angle
        Log.i(TAG, "handover at ${angle.fmt()}°")
        worker.execute { ShizukuBridge.handover(coverOnly) }
    }

    private fun exitDual(toHold: Boolean, reason: String) {
        dual = false
        Log.i(TAG, "dual off: $reason")
        val coverOnly = profileState
        worker.execute {
            ShizukuBridge.stopMirror()
            if (toHold && coverOnly != null) ShizukuBridge.holdState(coverOnly) else ShizukuBridge.releaseState()
        }
        if (!toHold) {
            holding = false
            FoldHoldState.coverHeldUntil = Float.NaN
        }
    }

    private fun hold(reason: String?) {
        val state = profileState ?: return
        holding = true
        lastAssertMs = SystemClock.uptimeMillis()
        FoldHoldState.coverHeldUntil = releaseAngle()
        if (reason != null) Log.i(TAG, "hold state $state: $reason")
        worker.execute {
            val ok = ShizukuBridge.holdState(state)
            if (!ok) handler.post {
                Log.w(TAG, "hold state $state failed")
                holding = false
                FoldHoldState.coverHeldUntil = Float.NaN
            }
        }
    }

    private fun release(reason: String, handedOverAt: Float = Float.NaN) {
        holding = false
        FoldHoldState.coverHeldUntil = Float.NaN
        if (handedOverAt.isFinite()) FoldHoldState.innerStartHinge = handedOverAt
        Log.i(TAG, "release: $reason")
        worker.execute { ShizukuBridge.releaseState() }
    }

    /** adb-only debug hook (see FoldOverlayService): force the dual state + mirror on/off. */
    fun debugDual(on: Boolean) {
        if (on && !dual) { debugPinned = true; holding = true; enterDual(Float.NaN) }
        else if (!on && dual) { debugPinned = false; exitDual(toHold = true, reason = "debug") }
    }

    fun destroy() {
        if (dual) exitDual(toHold = false, reason = "service stopping")
        if (holding) release("service stopping")
        worker.shutdown()
    }

    private fun Float.fmt() = "%.1f".format(this)

    private companion object {
        const val TAG = "DuoHold"
        /** At or below this the phone is shut; re-assert the hold if the system dropped it. */
        const val REASSERT_BELOW_HINGE = 2f
        const val REASSERT_INTERVAL_MS = 500L
        /** Light both panels this many degrees past the closed idle. */
        const val DUAL_ENTER_PAST_CLOSED_DEG = 1f
    }
}
