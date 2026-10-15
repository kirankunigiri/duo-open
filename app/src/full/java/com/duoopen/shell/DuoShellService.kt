package com.duoopen.shell

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.SurfaceControl
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.ObjIntConsumer
import java.util.regex.Pattern

/**
 * Runs in a process Shizuku spawns with ADB (shell) privileges. Two jobs the
 * app process can't do itself:
 *
 * 1. **Display capture without the accessibility rate limit**, through the
 *    hidden `IWindowManager.captureDisplay`, with our own overlay layers
 *    excluded — so the fold can re-capture the live screen while it plays.
 * 2. **Samsung's continuous hinge angle.** Only Samsung's own components get
 *    the real angle; its "Fold interactive" home wallpaper logs it on every
 *    wallpaper command it receives (`mCurrentAngle=…`), and the shell user may
 *    read logcat. The app pings the wallpaper; this tails the log and calls
 *    back with each fresh value.
 *
 * 3. **Device-state overrides** (`DeviceStateManagerGlobal.requestState`, which
 *    needs CONTROL_DEVICE_STATE — the shell has it). The request belongs to
 *    this process, so if the app (and with it this non-daemon service) dies,
 *    the system drops the override by itself.
 *
 * The first two techniques were worked out by Duo Fold Live
 * (github.com/joeconsorti/duo-fold-live, MIT) — the capture-API resolution
 * and the wallpaper log format in particular follow their findings.
 *
 * Nothing here is reachable without the user authorising this app in
 * Shizuku, and every transaction checks the calling uid.
 */
class DuoShellService : Binder() {

    private var owner = -1
    private var captureApi: CaptureApi? = null
    private var reader: AngleReader? = null
    private val hold = StateHold()
    private val mirror = Mirror()
    private val renderers = HashMap<Int, ShellRenderer>()

    private fun renderer(id: Int): ShellRenderer = synchronized(renderers) {
        renderers.getOrPut(id) {
            ShellRenderer(id, ::captureHardware) {
                synchronized(renderers) { renderers.values.mapNotNull { it.layer }.toTypedArray() }
            }
        }
    }

    init {
        attachInterface(null, ShellProtocol.TOKEN)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(ShellProtocol.TOKEN)
            return true
        }
        if (code == SHIZUKU_DESTROY) {
            reader?.stop()
            mirror.stop()
            synchronized(renderers) { renderers.values.forEach { it.destroy() } }
            hold.release()
            System.exit(0)
            return true
        }
        data.enforceInterface(ShellProtocol.TOKEN)
        val caller = getCallingUid()
        if (owner < 0) owner = caller
        if (caller != owner) throw SecurityException("wrong caller")
        if (code == ShellProtocol.RENDER_PARAMS) {
            val id = data.readInt()
            val visible = data.readInt() != 0
            val alpha = data.readFloat()
            val values = FloatArray(ShellRenderer.UNIFORM_COUNT) { data.readFloat() }
            renderer(id).update(visible, alpha, values)
            return true
        }
        val out = reply ?: return false
        when (code) {
            ShellProtocol.PING -> {
                out.writeNoException()
                out.writeBundle(Bundle().apply {
                    putInt("uid", Process.myUid())
                    putInt("pid", Process.myPid())
                    putString("capture", runCatching { api().name }.getOrElse { "unavailable: ${it.message}" })
                })
            }
            ShellProtocol.CAPTURE -> {
                val displayId = data.readInt()
                val n = data.readInt()
                val excluded = Array(n) { data.readTypedObject(SurfaceControl.CREATOR) }
                val scale = data.readFloat()
                val hardware = data.dataAvail() > 0 && data.readInt() != 0
                val identity = clearCallingIdentity()
                val result = try {
                    capture(displayId, excluded.filterNotNull().toTypedArray(), scale, hardware)
                } catch (t: Throwable) {
                    var c: Throwable = t
                    while (c.cause != null) c = c.cause!!
                    Bundle().apply { putString("error", "${c.javaClass.simpleName}: ${c.message}") }
                } finally {
                    excluded.forEach { runCatching { it?.release() } }
                    restoreCallingIdentity(identity)
                }
                out.writeNoException()
                out.writeBundle(result)
                // The parcel holds its own dup of the buffer's fd now.
                @Suppress("DEPRECATION")
                (result.getParcelable("buffer") as? HardwareBuffer)?.close()
            }
            ShellProtocol.START_ANGLES -> {
                val action = data.readString() ?: throw IllegalArgumentException("action")
                val callback = data.readStrongBinder() ?: throw IllegalArgumentException("callback")
                reader?.stop()
                reader = AngleReader(action, callback).also { it.start() }
                out.writeNoException()
            }
            ShellProtocol.STOP_ANGLES -> {
                reader?.stop()
                reader = null
                out.writeNoException()
            }
            ShellProtocol.ANGLE_STATUS -> {
                out.writeNoException()
                out.writeBundle(reader?.status() ?: Bundle().apply { putString("state", "not started") })
            }
            ShellProtocol.HOLD_STATE -> {
                val state = data.readInt()
                out.writeNoException()
                out.writeBundle(hold.request(state))
            }
            ShellProtocol.RELEASE_STATE -> {
                out.writeNoException()
                out.writeBundle(hold.release())
            }
            ShellProtocol.RENDER_SETUP -> {
                val id = data.readInt()
                val setup = ShellRenderer.Setup(
                    layerStack = data.readInt(),
                    sourceDisplay = data.readInt(),
                    bufferW = data.readInt(),
                    bufferH = data.readInt(),
                    x = data.readFloat(),
                    y = data.readFloat(),
                    scale = data.readFloat(),
                    z = data.readInt(),
                    content = android.graphics.RectF(data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat()),
                    captureScale = data.readFloat(),
                    shaderSource = data.readString() ?: "",
                )
                val identity = clearCallingIdentity()
                val ok = try { renderer(id).configure(setup) } finally { restoreCallingIdentity(identity) }
                out.writeNoException()
                out.writeBundle(Bundle().apply { putBoolean("ok", ok) })
            }
            ShellProtocol.RENDER_HIDE -> {
                renderer(data.readInt()).hide()
                out.writeNoException()
            }
            ShellProtocol.MIRROR_START -> {
                val src = data.readInt()
                val align = if (data.dataAvail() > 0) data.readInt() else 0
                val identity = clearCallingIdentity()
                val b = try { mirror.start(src, align) } finally { restoreCallingIdentity(identity) }
                out.writeNoException()
                out.writeBundle(b)
            }
            ShellProtocol.MIRROR_STOP -> {
                out.writeNoException()
                out.writeBundle(mirror.stop())
            }
            ShellProtocol.HANDOVER -> {
                val coverOnly = data.readInt()
                mirror.stop()
                out.writeNoException()
                out.writeBundle(hold.handover(coverOnly) { mirror.otherLitDisplay(Display.DEFAULT_DISPLAY) })
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    // ---- capture -----------------------------------------------------------

    private class CaptureApi(
        val name: String,
        val builderCtor: Constructor<*>,
        val builder: Class<*>,
        val listenerCtor: Constructor<*>,
        val statusCallback: Boolean,
        val captureDisplay: Method,
    )

    private fun systemService(name: String, stub: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder
        return Class.forName(stub).getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /**
     * Finds whichever generation of the hidden capture API this Android has:
     * `android.window.ScreenCaptureInternal` (status callback) or
     * `android.window.ScreenCapture` (plain consumer), driven through
     * `IWindowManager.captureDisplay`.
     */
    private fun api(): CaptureApi {
        captureApi?.let { return it }
        val wm = Class.forName("android.view.IWindowManager")
        val errors = StringBuilder()
        for (family in FAMILIES) {
            try {
                val args = Class.forName("$family\$CaptureArgs")
                val builder = Class.forName("$family\$CaptureArgs\$Builder")
                val listener = Class.forName("$family\$ScreenCaptureListener")
                val ctor = builder.getConstructor()
                builder.getMethod("setSourceCrop", Rect::class.java)
                builder.getMethod("setFrameScale", java.lang.Float.TYPE)
                builder.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java)
                builder.getMethod("build")
                var status = true
                val lctor = try {
                    listener.getConstructor(ObjIntConsumer::class.java)
                } catch (e: NoSuchMethodException) {
                    status = false
                    listener.getConstructor(Consumer::class.java)
                }
                val capture = wm.getMethod("captureDisplay", Integer.TYPE, args, listener)
                return CaptureApi(family, ctor, builder, lctor, status, capture).also { captureApi = it }
            } catch (e: ReflectiveOperationException) {
                errors.append(family).append(": ").append(e).append("; ")
            }
        }
        throw ClassNotFoundException("no compatible display capture API: $errors")
    }

    /** In-process zero-copy capture for the renderers; null on failure or secure content. */
    private fun captureHardware(displayId: Int, excluded: Array<SurfaceControl>, scale: Float): Pair<HardwareBuffer, android.graphics.ColorSpace?>? {
        val b = runCatching { capture(displayId, excluded, scale, hardware = true) }.getOrNull() ?: return null
        @Suppress("DEPRECATION")
        val buffer = b.getParcelable("buffer") as? HardwareBuffer ?: return null
        if (b.getBoolean("secure")) { buffer.close(); return null }
        val id = b.getInt("colorSpace", -1)
        val cs = android.graphics.ColorSpace.Named.entries.asSequence().map { android.graphics.ColorSpace.get(it) }.firstOrNull { it.id == id }
        return buffer to cs
    }

    private var wmService: Any? = null
    private var dmService: Any? = null
    private var getDisplayInfo: Method? = null

    /**
     * @param hardware Return the compositor's HardwareBuffer itself (zero-copy,
     *   the fd is shared over binder) instead of reading it back into an
     *   ARGB bitmap — the difference between ~9 and ~60 live frames a second.
     */
    private fun capture(displayId: Int, excluded: Array<SurfaceControl>, scale: Float, hardware: Boolean): Bundle {
        val t0 = SystemClock.elapsedRealtime()
        val api = api()
        val wm = wmService ?: systemService("window", "android.view.IWindowManager\$Stub").also { wmService = it }
        val dm = dmService ?: systemService("display", "android.hardware.display.IDisplayManager\$Stub").also { dmService = it }
        val gdi = getDisplayInfo ?: Class.forName("android.hardware.display.IDisplayManager")
            .getMethod("getDisplayInfo", Integer.TYPE).also { getDisplayInfo = it }
        val info = gdi.invoke(dm, displayId) ?: throw IllegalStateException("no display $displayId")
        val w = info.javaClass.getField("logicalWidth").getInt(info)
        val h = info.javaClass.getField("logicalHeight").getInt(info)
        if (w <= 0 || h <= 0) throw IllegalStateException("display $displayId has no size")

        val b = api.builderCtor.newInstance()
        api.builder.getMethod("setSourceCrop", Rect::class.java).invoke(b, Rect(0, 0, w, h))
        api.builder.getMethod("setFrameScale", java.lang.Float.TYPE).invoke(b, scale.coerceIn(0.1f, 1f))
        if (excluded.isNotEmpty()) {
            api.builder.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java).invoke(b, excluded)
        }
        val args = api.builder.getMethod("build").invoke(b)

        val latch = CountDownLatch(1)
        var shot: Any? = null
        val callback: Any = if (api.statusCallback) {
            ObjIntConsumer<Any?> { s, _ -> shot = s; latch.countDown() }
        } else {
            Consumer<Any?> { s -> shot = s; latch.countDown() }
        }
        val listener = api.listenerCtor.newInstance(callback)
        api.captureDisplay.invoke(wm, displayId, args, listener)
        if (!latch.await(400, TimeUnit.MILLISECONDS)) throw IllegalStateException("capture timed out")
        java.lang.ref.Reference.reachabilityFence(callback)
        java.lang.ref.Reference.reachabilityFence(listener)
        val result = shot ?: throw IllegalStateException("no frame")

        var buffer: HardwareBuffer? = null
        try {
            buffer = result.javaClass.getMethod("getHardwareBuffer").invoke(result) as? HardwareBuffer
            val secure = runCatching {
                result.javaClass.getMethod("containsSecureLayers").invoke(result) as Boolean
            }.getOrDefault(false)
            if (hardware && buffer != null) {
                val colorSpace = runCatching {
                    (result.javaClass.getMethod("getColorSpace").invoke(result) as? android.graphics.ColorSpace)?.id
                }.getOrNull() ?: -1
                val sent = buffer
                buffer = null // closed by the caller once parcelled
                return Bundle().apply {
                    putBoolean("ok", true)
                    putParcelable("buffer", sent)
                    putInt("colorSpace", colorSpace)
                    putInt("width", w)
                    putInt("height", h)
                    putBoolean("secure", secure)
                    putLong("ms", SystemClock.elapsedRealtime() - t0)
                }
            }
            val hwBitmap = result.javaClass.getMethod("asBitmap").invoke(result) as? Bitmap
                ?: throw IllegalStateException("frame not readable")
            val bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
            hwBitmap.recycle()
            return Bundle().apply {
                putBoolean("ok", true)
                putParcelable("bitmap", bitmap)
                putInt("width", w)
                putInt("height", h)
                putBoolean("secure", secure)
                putLong("ms", SystemClock.elapsedRealtime() - t0)
            }
        } finally {
            buffer?.close()
        }
    }

    // ---- device-state override ----------------------------------------------

    /**
     * One device-state override at a time through the framework's own client
     * (`DeviceStateManagerGlobal`), so the request token and the process
     * callback the service insists on are handled exactly as for a system app.
     */
    private class StateHold {
        private var global: Any? = null
        @Volatile private var held = -1
        /** What the app wants held; the system drops overrides when the base state returns to CLOSED. */
        @Volatile private var desired = -1
        @Volatile private var lastEvent = "none"

        private fun global(): Any = global ?: Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal")
            .getMethod("getInstance").invoke(null)
            ?.also { global = it }
            ?: throw IllegalStateException("device_state service unavailable")

        fun request(state: Int): Bundle = result {
            desired = state
            requestInternal(state)
        }

        /** Bumped per request; a callback only acts for the request that is current. */
        @Volatile private var generation = 0

        /**
         * A new override *replaces* the active one in the system (no cancel in
         * between — cancelling first would drop to the base state for a moment,
         * which is exactly the double power-cycle we're avoiding).
         */
        @Synchronized
        private fun requestInternal(state: Int) {
            if (held == state) return
            val gen = ++generation
            val reqClass = Class.forName("android.hardware.devicestate.DeviceStateRequest")
            val builder = reqClass.getMethod("newBuilder", Integer.TYPE).invoke(null, state)!!
            val request = builder.javaClass.getMethod("build").invoke(builder)
            val cbClass = Class.forName("android.hardware.devicestate.DeviceStateRequest\$Callback")
            val callback = java.lang.reflect.Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { proxy, m, args ->
                when (m.name) {
                    "onRequestActivated", "onRequestSuspended", "onRequestCanceled" -> {
                        lastEvent = "${m.name}(${args?.firstOrNull()}) gen=$gen"
                        if (m.name == "onRequestCanceled" && gen == generation) {
                            held = -1
                            // Closing back without the app letting go (base → CLOSED
                            // cancels overrides): put the hold straight back.
                            val want = desired
                            if (want >= 0) Thread {
                                Thread.sleep(REASSERT_DELAY_MS)
                                if (desired == want && generation == gen) runCatching { requestInternal(want) }
                                    .onSuccess { lastEvent = "re-held $want" }
                            }.start()
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "DuoStateHoldCallback"
                    else -> null
                }
            }
            val executor = java.util.concurrent.Executor { it.run() }
            global().javaClass.getMethod(
                "requestState", reqClass, java.util.concurrent.Executor::class.java, cbClass,
            ).invoke(global(), request, executor, callback)
            held = state
        }

        fun release(): Bundle = result {
            desired = -1
            if (held >= 0) cancel()
        }

        /**
         * Both-panels-lit → normal without the slow path: switching straight
         * from a dual state power-cycles both panels (~600 ms black). Going to
         * the cover-only state first keeps display 0 where it is and just turns
         * the second panel off; once it's gone, releasing is an ordinary swap.
         */
        fun handover(coverOnly: Int, secondPanelLit: () -> Int?): Bundle {
            val t0 = SystemClock.elapsedRealtime()
            val b = result {
                desired = coverOnly
                requestInternal(coverOnly)
                val deadline = t0 + HANDOVER_WAIT_MS
                while (secondPanelLit() != null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
                desired = -1
                if (held >= 0) cancel()
            }
            b.putLong("ms", SystemClock.elapsedRealtime() - t0)
            return b
        }

        @Synchronized
        private fun cancel() {
            generation++ // the cancel callback that follows is ours, not a system drop
            global().javaClass.getMethod("cancelStateRequest").invoke(global())
            held = -1
        }

        private inline fun result(block: () -> Unit): Bundle = Bundle().apply {
            try {
                block()
                putBoolean("ok", true)
            } catch (t: Throwable) {
                var c: Throwable = t
                while (c.cause != null) c = c.cause!!
                putBoolean("ok", false)
                putString("error", "${c.javaClass.simpleName}: ${c.message}")
            }
            putInt("held", held)
            putString("event", lastEvent)
        }
    }

    // ---- live mirror onto the second panel ----------------------------------

    /**
     * While both panels are lit (device state ALL2: cover = display 0, inner =
     * a second logical display), put a live SurfaceFlinger mirror of display 0
     * on the other panel's layer stack, scaled to fit and centred — the inner
     * screen shows the very same content as the cover, frame for frame.
     */
    private inner class Mirror {
        private var sc: SurfaceControl? = null
        /** Our own root container on the target layer stack; the mirror (offscreen as created) hangs under it. */
        private var host: SurfaceControl? = null
        @Volatile var target = -1
            private set

        private fun dm(): Any = dmService ?: systemService("display", "android.hardware.display.IDisplayManager\$Stub").also { dmService = it }
        private fun info(id: Int): Any? = runCatching {
            Class.forName("android.hardware.display.IDisplayManager").getMethod("getDisplayInfo", Integer.TYPE).invoke(dm(), id)
        }.getOrNull()
        private fun Any.int(field: String) = javaClass.getField(field).getInt(this)

        /** A lit display other than [source] that has its own layer stack, else null. */
        fun otherLitDisplay(source: Int): Int? {
            val ids = runCatching {
                Class.forName("android.hardware.display.IDisplayManager").getMethod("getDisplayIds", java.lang.Boolean.TYPE)
                    .invoke(dm(), false) as IntArray
            }.getOrNull() ?: return null
            return ids.firstOrNull { id ->
                id != source && info(id)?.let { it.int("layerStack") >= 0 && it.int("state") != Display.STATE_OFF && it.int("logicalWidth") > 1000 } == true
            }
        }

        /** @param align −1 = left edge, 0 = centred, +1 = right edge of the target panel. */
        fun start(source: Int, align: Int): Bundle = Bundle().apply {
            try {
                stop()
                // The second panel's logical display appears a moment after the state change.
                val deadline = SystemClock.elapsedRealtime() + MIRROR_WAIT_MS
                var dst: Int? = otherLitDisplay(source)
                while (dst == null && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(10)
                    dst = otherLitDisplay(source)
                }
                if (dst == null) throw IllegalStateException("no second lit display")
                val src = info(source) ?: throw IllegalStateException("no display $source")
                val dstInfo = info(dst) ?: throw IllegalStateException("no display $dst")
                val sw = src.int("logicalWidth").toFloat(); val sh = src.int("logicalHeight").toFloat()
                val tw = dstInfo.int("logicalWidth").toFloat(); val th = dstInfo.int("logicalHeight").toFloat()
                val layerStack = dstInfo.int("layerStack")

                val out = SurfaceControl::class.java.getDeclaredConstructor().newInstance()
                val wm = wmService ?: systemService("window", "android.view.IWindowManager\$Stub").also { wmService = it }
                val ok = Class.forName("android.view.IWindowManager")
                    .getMethod("mirrorDisplay", Integer.TYPE, SurfaceControl::class.java).invoke(wm, source, out) as Boolean
                if (!ok || !out.isValid) throw IllegalStateException("mirrorDisplay($source) failed")

                // WMS hands back an offscreen "MirrorRoot"; a layer stack only
                // applies to root layers, so make our own root on the target
                // panel's stack and parent the mirror under it.
                val builder = SurfaceControl.Builder().setName("DuoMirrorHost")
                SurfaceControl.Builder::class.java.getMethod("setContainerLayer").invoke(builder) // hidden
                val root = builder.build()
                val scale = minOf(tw / sw, th / sh)
                val t = SurfaceControl.Transaction()
                SurfaceControl.Transaction::class.java.getMethod("setLayerStack", SurfaceControl::class.java, Integer.TYPE)
                    .invoke(t, root, layerStack)
                t.setLayer(root, Int.MAX_VALUE - 100)
                t.setVisibility(root, true)
                t.reparent(out, root)
                t.setScale(out, scale, scale)
                val spare = tw - sw * scale
                val x = when { align > 0 -> spare; align < 0 -> 0f; else -> spare / 2f }
                t.setPosition(out, x, (th - sh * scale) / 2f)
                t.setVisibility(out, true)
                t.apply()
                host = root
                sc = out
                target = dst
                putBoolean("ok", true)
                putInt("target", dst)
                putInt("layerStack", layerStack)
                putFloat("scale", scale)
            } catch (t: Throwable) {
                var c: Throwable = t
                while (c.cause != null) c = c.cause!!
                putBoolean("ok", false)
                putString("error", "${c.javaClass.simpleName}: ${c.message}")
            }
        }

        fun stop(): Bundle {
            sc?.let { s ->
                runCatching { SurfaceControl.Transaction().setVisibility(s, false).reparent(s, null).apply() }
                runCatching { s.release() }
            }
            host?.let { h ->
                runCatching { SurfaceControl.Transaction().setVisibility(h, false).reparent(h, null).apply() }
                runCatching { h.release() }
            }
            host = null
            sc = null
            target = -1
            return Bundle().apply { putBoolean("ok", true) }
        }
    }

    // ---- Samsung wallpaper angle reader -------------------------------------

    /**
     * Tails logcat for the "Fold interactive" wallpaper's command log. Each
     * command the app sends makes the wallpaper log a line containing the
     * action it was sent, whether it's visible, and `mCurrentAngle=<deg>`.
     * Log format per Duo Fold Live's findings.
     */
    private class AngleReader(private val action: String, private val callback: IBinder) {
        @Volatile private var process: java.lang.Process? = null
        @Volatile private var stopped = false
        @Volatile private var state = "starting"
        private var lines = 0
        private var parsed = 0
        private var rejected = 0
        private var lastAngle = Float.NaN
        private var lastUptime = 0L

        fun start() {
            val thread = Thread({
                var child: java.lang.Process? = null
                try {
                    child = ProcessBuilder(
                        "logcat", "-v", "epoch", "-T", "1", "-s", "SprWallpaper|FoldInteractive:V", "*:S",
                    ).redirectErrorStream(true).start()
                    process = child
                    state = "listening"
                    BufferedReader(InputStreamReader(child.inputStream)).use { input ->
                        while (!stopped) {
                            val line = input.readLine() ?: break
                            lines++
                            val value = parse(line) ?: continue
                            // Never treat a buffered line as current.
                            val epoch = line.trim().split(Regex("\\s+"), 2).firstOrNull()?.toDoubleOrNull()
                            val age = if (epoch == null) Long.MAX_VALUE else System.currentTimeMillis() - (epoch * 1000).toLong()
                            if (age < -100 || age > 1500) {
                                rejected++
                                continue
                            }
                            parsed++
                            lastAngle = value
                            lastUptime = SystemClock.uptimeMillis() - age.coerceAtLeast(0)
                            state = "receiving"
                            val p = Parcel.obtain()
                            try {
                                p.writeInterfaceToken(ShellProtocol.CALLBACK_TOKEN)
                                p.writeFloat(value)
                                p.writeLong(lastUptime)
                                callback.transact(ShellProtocol.CB_ANGLE, p, null, IBinder.FLAG_ONEWAY)
                            } catch (e: Exception) {
                                state = "callback gone: ${e.message}"
                                break
                            } finally {
                                p.recycle()
                            }
                        }
                    }
                    if (!stopped) state = "log reader ended"
                } catch (e: Exception) {
                    state = "reader error: $e"
                } finally {
                    child?.destroy()
                }
            }, "duo-angle-reader")
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            stopped = true
            process?.destroy()
            state = "stopped"
        }

        fun status(): Bundle = Bundle().apply {
            putString("state", state)
            putInt("lines", lines)
            putInt("parsed", parsed)
            putInt("rejected", rejected)
            putFloat("angle", lastAngle)
            putLong("last", lastUptime)
        }

        private fun parse(line: String): Float? {
            if (!line.contains("SprWallpaper|FoldInteractive") || !line.contains("onCommand:")) return null
            if (!(line.contains("action=$action,") || line.contains("action[$action]"))) return null
            if (!(line.contains("isVisible=true") || line.contains("isVisible[true]"))) return null
            val m = ANGLE.matcher(line)
            if (!m.find()) return null
            val v = m.group(1)?.toFloatOrNull() ?: return null
            return if (v in 0f..180f) v else null
        }

        private companion object {
            val ANGLE: Pattern = Pattern.compile("mCurrentAngle(?:=|\\[)([0-9]+(?:\\.[0-9]+)?)")
        }
    }

    private companion object {
        /** Shizuku asks user services to exit with this code. */
        const val SHIZUKU_DESTROY = 16777115
        const val REASSERT_DELAY_MS = 30L
        const val MIRROR_WAIT_MS = 600L
        const val HANDOVER_WAIT_MS = 400L
        val FAMILIES = listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture")
    }
}
