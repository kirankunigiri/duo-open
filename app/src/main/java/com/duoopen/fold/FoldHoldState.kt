package com.duoopen.fold

/**
 * Shared between the cover-hold controller (full flavor) and the tilt maps in
 * [DuoShader]: while the cover is held lit past the system's own swap point,
 * the cover's frost spans the whole held range, and after the hand-over the
 * inner panel's frost starts from the angle it was handed over at.
 */
object FoldHoldState {
    /** Hinge angle the cover is being held until; NaN when not holding. */
    @Volatile
    var coverHeldUntil: Float = Float.NaN

    /** Hinge angle the last hold handed over to the inner panel at; NaN once closed again. */
    @Volatile
    var innerStartHinge: Float = Float.NaN
}
