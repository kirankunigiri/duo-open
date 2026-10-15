package com.duoopen.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.isInnerPanel
import com.duoopen.fold.isLivePanel
import com.duoopen.settings.DuoSettings
import com.duoopen.shell.ShizukuBridge
import com.duoopen.shell.WallpaperAngleFeed
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * System-wide fold effect. Accessibility services may screenshot any display
 * and draw above every other window, which is what lets the fold cover the
 * launcher, lock screen and apps — not just the wallpaper.
 *
 * Runs one [PanelEngine] per lit built-in display and keeps that set in step
 * with the DisplayManager: a panel that lights up mid-fold gets an engine
 * (which captures it as the second half of the fold), one that switches off
 * loses its engine. Phones that only ever light one panel (OnePlus Open)
 * therefore run a single engine whose display flips mode at the swap; phones
 * that keep both panels on run the cover and inner engines together, so the
 * handover has no gap.
 */
class FoldOverlayService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var hinge: HingeAngleSource
    private lateinit var displayManager: DisplayManager
    private val engines = LinkedHashMap<Int, PanelEngine>()
    private val snapshots = SnapshotCache(maxAgeMs = SNAPSHOT_MAX_AGE_MS)
    private var angleFeed: WallpaperAngleFeed? = null
    private var coverHold: CoverHold? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            coverHold?.interactive = intent.action != Intent.ACTION_SCREEN_OFF
            coverHold?.update(hinge.lastAngle)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncDisplays()
        override fun onDisplayRemoved(displayId: Int) = syncDisplays()
        override fun onDisplayChanged(displayId: Int) = syncDisplays()
    }

    /** `adb shell am broadcast -a com.duoopen.DEMO` plays the effect over whatever is on screen. */
    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = playDemo()
    }
    private var receiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!receiverRegistered) {
            // Exported so `adb shell am broadcast` works, but gated on DUMP, which
            // the shell holds and ordinary apps can't — otherwise any app could
            // trigger a capture-and-play.
            registerReceiver(demoReceiver, IntentFilter(ACTION_DEMO), DEMO_PERMISSION, null, RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, handler)
        hinge = HingeAngleSource(this) { onHinge(it) }
        hinge.start()
        ShizukuBridge.init(this)
        coverHold = CoverHold(this, handler)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, RECEIVER_NOT_EXPORTED)
        scope.launch { ShizukuBridge.state.collect { coverHold?.update(hinge.lastAngle) } }
        scope.launch { DuoSettings.config.collect { coverHold?.update(hinge.lastAngle) } }
        angleFeed = WallpaperAngleFeed(this, handler, hinge)
        scope.launch { ShizukuBridge.state.collect { syncAngleFeed() } }
        scope.launch { DuoSettings.config.collect { syncAngleFeed() } }
        handler.post(stallTick)
        syncDisplays()
        Log.i(TAG, "connected; hinge=${hinge.sensor?.name} live displays=${engines.keys}")
    }

    override fun onDestroy() {
        instance = null
        angleFeed?.stop()
        handler.removeCallbacks(stallTick)
        coverHold?.destroy()
        coverHold = null
        runCatching { unregisterReceiver(screenReceiver) }
        if (receiverRegistered) unregisterReceiver(demoReceiver)
        hinge.stop()
        displayManager.unregisterDisplayListener(displayListener)
        for (e in engines.values) e.destroy()
        engines.clear()
        OverlayState.setRunning(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit


    // Main-thread stall monitor (instrumentation): a 50 ms tick; report the
    // worst lateness each 2 s window if anything was over a frame or two.
    private var stallExpected = 0L
    private var stallWorst = 0L
    private var stallWindowStart = 0L
    private val stallTick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (stallExpected > 0L) stallWorst = maxOf(stallWorst, now - stallExpected)
            if (now - stallWindowStart >= 2_000L) {
                if (stallWorst > 25L) Log.i(TAG, "main thread stalled up to ${stallWorst} ms in the last 2 s")
                stallWorst = 0L
                stallWindowStart = now
            }
            stallExpected = now + 50L
            handler.postDelayed(this, 50L)
        }
    }

    private fun onHinge(angle: Float) {
        // No display queries here: they're binder calls on the main thread
        // (~50 Hz mid-fold), and panel changes arrive through the display
        // listener anyway.
        coverHold?.update(angle)
        for (e in engines.values.toList()) e.onHinge(angle)
    }

    /** Starts engines for panels that lit up, stops those that went dark, then lets each re-evaluate. */
    private fun syncDisplays() {
        // `adb shell settings put global duoopen_test_displays 1` lets a
        // simulated secondary display stand in for a second panel (see Panels.kt).
        val testDisplays = Settings.Global.getInt(contentResolver, TEST_DISPLAYS_SETTING, 0) != 0
        val defaultName = runCatching { displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.name }.getOrNull()
        val live = displayManager.displays
            .filter { it.isLivePanel(defaultDisplayName = defaultName, includePresentation = testDisplays) }
            .associateBy { it.displayId }
        val gone = engines.keys.filter { it !in live }
        for (id in gone) {
            engines.remove(id)?.destroy()
        }
        for ((id, display) in live) {
            if (id !in engines) {
                engines[id] = PanelEngine(
                    service = this,
                    display = display,
                    hinge = hinge,
                    handler = handler,
                    scope = scope,
                    hasLiveInnerElsewhere = { engines.values.any { it !== engines[id] && it.innerPanel } },
                    onShowingChanged = ::updateRunning,
                    cache = snapshots,
                )
            }
        }
        if (gone.isNotEmpty()) updateRunning()
        for (e in engines.values.toList()) e.evaluate(readDisplay = true)
        angleFeed?.onDisplayChanged()
        coverHold?.innerIsDefault = live[Display.DEFAULT_DISPLAY]?.let { it.isInnerPanel() }
            ?: coverHold?.innerIsDefault ?: false
        if (::hinge.isInitialized) coverHold?.update(hinge.lastAngle)
    }

    /** Samsung continuous angle via Shizuku + fold wallpaper, when everything lines up. */
    private fun syncAngleFeed() {
        val feed = angleFeed ?: return
        val want = DuoSettings.config.value.shizukuAngle && ShizukuBridge.ready && WallpaperAngleFeed.foldWallpaperActive(this)
        if (want && !feed.active) feed.start() else if (!want && feed.active) feed.stop()
    }

    fun angleFeedStatus(): String = angleFeed?.status ?: "Idle"

    private fun updateRunning() {
        OverlayState.setRunning(engines.values.any { it.showing })
    }

    /** Replays the effect on every lit panel — the default display is the one you're looking at. */
    fun playDemo() {
        syncDisplays()
        (engines[Display.DEFAULT_DISPLAY]?.let { listOf(it) } ?: engines.values.toList())
            .forEach { it.playDemo() }
    }

    companion object {
        private const val TAG = "DuoOverlay"
        const val ACTION_DEMO = "com.duoopen.DEMO"
        private const val TEST_DISPLAYS_SETTING = "duoopen_test_displays"
        private const val DEMO_PERMISSION = "android.permission.DUMP"
        /** How old a panel's last picture may be and still bridge the next fold (kept in memory only). */
        private const val SNAPSHOT_MAX_AGE_MS = 3 * 60_000L

        /** The connected service, for in-process control from the app. */
        @Volatile
        var instance: FoldOverlayService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val self = ComponentName(context, FoldOverlayService::class.java)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == self }
        }
    }
}
