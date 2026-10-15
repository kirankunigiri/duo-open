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

    /** callback: float angle, long uptimeMs */
    const val CB_ANGLE = 1
}
