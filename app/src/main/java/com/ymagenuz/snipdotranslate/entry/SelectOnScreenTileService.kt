package com.ymagenuz.snipdotranslate.entry

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.ymagenuz.snipdotranslate.select.overlay.SelectionAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Captures the screen below Quick Settings and opens it with the pens: through the
 * accessibility service when it's turned on, without asking, and otherwise with Android's
 * screen-capture permission, which asks each time.
 */
class SelectOnScreenTileService : TileService() {
    override fun onStopListening() {
        shadeClosed?.complete(Unit)
        shadeClosed = null
    }

    override fun onClick() {
        val service = SelectionAccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The service closes the shade itself and waits for it before capturing.
            unlockAndRun { service.startScan() }
        } else {
            openFromTile(Intent(this, ScreenCaptureActivity::class.java))
        }
    }

    companion object {
        @Volatile
        private var shadeClosed: CompletableDeferred<Unit>? = null

        /** Completes when the shade has finished closing and stopped showing this tile. */
        fun expectShadeClosed(): Deferred<Unit> = CompletableDeferred<Unit>().also { shadeClosed = it }
    }
}
