package com.ymagenuz.snipdotranslate.entry

import android.content.Intent
import android.service.quicksettings.TileService

class TranslateClipboardTileService : TileService() {
    override fun onClick() {
        openFromTile(Intent(this, PopupActivity::class.java).setAction(PopupActivity.ACTION_TRANSLATE_CLIPBOARD))
    }
}
