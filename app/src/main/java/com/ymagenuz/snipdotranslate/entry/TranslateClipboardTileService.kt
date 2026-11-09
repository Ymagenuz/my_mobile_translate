package com.ymagenuz.snipdotranslate.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

class TranslateClipboardTileService : TileService() {
    override fun onClick() {
        if (isLocked) unlockAndRun(::openPopup) else openPopup()
    }

    private fun openPopup() {
        val intent = Intent(this, PopupActivity::class.java)
            .setAction(PopupActivity.ACTION_TRANSLATE_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
}
