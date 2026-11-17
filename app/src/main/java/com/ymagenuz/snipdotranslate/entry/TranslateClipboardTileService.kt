package com.ymagenuz.snipdotranslate.entry

import android.content.Intent
import android.service.quicksettings.TileService
import com.ymagenuz.snipdotranslate.SnipDoApp

class TranslateClipboardTileService : TileService() {
    override fun onTileAdded() = (application as SnipDoApp).noteTile(selectOnScreen = false, added = true)

    override fun onTileRemoved() = (application as SnipDoApp).noteTile(selectOnScreen = false, added = false)

    override fun onClick() {
        // A tile added before the app kept track of it.
        (application as SnipDoApp).noteTile(selectOnScreen = false, added = true)
        openFromTile(Intent(this, PopupActivity::class.java).setAction(PopupActivity.ACTION_TRANSLATE_CLIPBOARD))
    }
}
