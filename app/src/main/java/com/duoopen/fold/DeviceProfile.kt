package com.duoopen.fold

import android.os.Build

/**
 * Per-device fold geometry and timing. Everything here was measured on real
 * hardware (hinge traces + logcat of the panel swap); unknown devices get the
 * OnePlus Open profile, which the effect was originally tuned on.
 */
data class DeviceProfile(
    val name: String,
    /** Hinge angle treated as fully flat; real hinges rest short of 180°. */
    val flatHinge: Float,
    /** Hinge angle at which the system swaps panels; the cover's frost peaks here. */
    val panelOnHinge: Float,
    /** A closed phone idles below this; the cover counts as at rest. */
    val closedHinge: Float,
    /** After a swap, skip the inner effect if the capture lands above this angle. */
    val skipInnerAboveHinge: Float,
    /** After a swap, skip the cover effect if the capture lands below this angle (negative = never). */
    val skipCoverBelowHinge: Float,
    /** Physical aspect below which a panel counts as the inner one. */
    val innerMaxAspect: Float = 1.45f,
) {
    companion object {
        /** OnePlus Open: swaps at ~20°, idles 0–5° closed, rests at 176–179° open. */
        val ONEPLUS_OPEN = DeviceProfile(
            name = "OnePlus Open",
            flatHinge = 172f,
            panelOnHinge = 20f,
            closedHinge = 6f,
            skipInnerAboveHinge = 135f,
            skipCoverBelowHinge = 10f,
        )

        /**
         * OPPO Find N6 (CPH2765 / OP6271L1, ColorOS 16): the vendor fold_state
         * sensor swaps panels at 8–17° opening and 4–14° closing; closed idles at
         * 0–0.3°, flat reads 175–178°. The cover only lights once the phone is
         * (nearly) shut, so its capture always lands near 0° — never skip it.
         */
        val OPPO_FIND_N6 = DeviceProfile(
            name = "OPPO Find N6",
            flatHinge = 172f,
            panelOnHinge = 10f,
            closedHinge = 2f,
            skipInnerAboveHinge = 150f,
            skipCoverBelowHinge = -1f,
        )

        val current: DeviceProfile by lazy { detect(Build.MANUFACTURER, Build.MODEL, Build.DEVICE) }

        fun detect(manufacturer: String?, model: String?, device: String?): DeviceProfile {
            val m = manufacturer.orEmpty().lowercase()
            return when {
                m == "oppo" && (model == "CPH2765" || device == "OP6271L1") -> OPPO_FIND_N6
                else -> ONEPLUS_OPEN
            }
        }
    }
}
