package com.ymagenuz.snipdotranslate.entry

import android.content.Intent
import android.service.quicksettings.TileService

/** Captures the screen below Quick Settings and opens it with the pens. */
class SelectOnScreenTileService : TileService() {
    override fun onClick() {
        openFromTile(Intent(this, ScreenCaptureActivity::class.java))
    }
}
