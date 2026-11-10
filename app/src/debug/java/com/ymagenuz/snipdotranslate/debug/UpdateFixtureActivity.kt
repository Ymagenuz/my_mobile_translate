package com.ymagenuz.snipdotranslate.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.ymagenuz.snipdotranslate.update.UpdateInstaller

/**
 * Hands an APK already in the update folder to Android's installer, as Settings does after
 * a download, so the install step can be tried without publishing a release.
 * Pass the string extra "version" naming SnipDoTranslate-<version>.apk.
 */
class UpdateFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = UpdateInstaller.fileFor(this, intent.getStringExtra("version").orEmpty())
        val accepted = file.exists() && UpdateInstaller.isUpdateFor(this, file)
        val allowed = UpdateInstaller.canInstall(this)
        Log.i("UpdateFixture", "file=${file.exists()} accepted=$accepted canInstall=$allowed")
        when {
            !accepted -> Unit
            allowed -> startActivity(UpdateInstaller.installIntent(this, file))
            else -> startActivity(UpdateInstaller.permissionIntent(this))
        }
        finish()
    }
}
