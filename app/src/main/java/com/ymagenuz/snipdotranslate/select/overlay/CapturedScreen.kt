package com.ymagenuz.snipdotranslate.select.overlay

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Hands a screenshot from the capture service to the selection screen. It stays in memory
 * only and is never written to disk; each one is taken once.
 */
object CapturedScreen {
    @Volatile
    private var waiting: CompletableDeferred<Bitmap?>? = null

    @Volatile
    private var held: Bitmap? = null

    /** Starts waiting for the next screenshot. */
    fun expect(): Deferred<Bitmap?> = CompletableDeferred<Bitmap?>().also { waiting = it }

    /** From the capture service; null when no screenshot could be taken. */
    fun deliver(bitmap: Bitmap?) {
        waiting?.complete(bitmap)
        waiting = null
    }

    /** Keeps the screenshot until the selection screen takes it. */
    fun hold(bitmap: Bitmap) {
        held = bitmap
    }

    fun take(): Bitmap? = held.also { held = null }
}
