package com.duoopen.shell

/** Binder protocol between the app and [DuoShellService] (runs with Shizuku's ADB privileges). */
object ShellProtocol {
    const val TOKEN = "com.duoopen.shell.DuoShellService"
    const val CALLBACK_TOKEN = "com.duoopen.shell.AngleCallback"

    /** → Bundle{uid, pid, version} */
    const val PING = 1
    /** in: displayId, n, SurfaceControl×n (excluded), scale → Bundle{ok, bitmap, width, height, error, secure} */
    const val CAPTURE = 2
    /** in: action, callback IBinder → starts the Samsung wallpaper angle reader */
    const val START_ANGLES = 3
    const val STOP_ANGLES = 4
    /** → Bundle{state, lines, parsed, rejected, last, angle} */
    const val ANGLE_STATUS = 5
    /** in: device state id → Bundle{ok, error}; overrides the device state until released or this process dies */
    const val HOLD_STATE = 6
    /** → Bundle{ok, error}; cancels a [HOLD_STATE] override */
    const val RELEASE_STATE = 7
    /** in: source displayId → Bundle{ok, error, target, layerStack}; live mirror of the source on the other lit panel */
    const val MIRROR_START = 8
    /** → Bundle{ok}; removes the mirror */
    const val MIRROR_STOP = 9
    /**
     * in: cover-only state id → Bundle{ok, error, ms}: the cheap way out of a
     * both-panels-lit state — first drop to cover-only (display 0 doesn't move,
     * the second panel switches off), then release so the normal swap follows.
     */
    const val HANDOVER = 10

    /** callback: float angle, long uptimeMs */
    const val CB_ANGLE = 1
}
