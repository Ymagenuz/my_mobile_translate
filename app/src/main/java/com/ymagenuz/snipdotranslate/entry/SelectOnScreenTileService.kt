package com.ymagenuz.snipdotranslate.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ymagenuz.snipdotranslate.select.overlay.SelectionAccessibilityService
import com.ymagenuz.snipdotranslate.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

class SelectOnScreenTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (SelectionAccessibilityService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onStopListening() {
        shadeClosed?.complete(Unit)
        shadeClosed = null
    }

    override fun onClick() {
        val service = SelectionAccessibilityService.instance
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            openApp()
            return
        }
        unlockAndRun { service.startScan() }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            // The only form available before Android 14.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        @Volatile
        private var shadeClosed: CompletableDeferred<Unit>? = null

        /** Completes when the shade has finished closing and stopped showing this tile. */
        fun expectShadeClosed(): Deferred<Unit> = CompletableDeferred<Unit>().also { shadeClosed = it }
    }
}
