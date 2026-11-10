package com.ymagenuz.snipdotranslate.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.ymagenuz.snipdotranslate.core.isNewerVersion
import java.io.File

private const val FOLDER = "updates"
private const val PREFIX = "SnipDoTranslate-"
private const val SUFFIX = ".apk"
private const val APK_TYPE = "application/vnd.android.package-archive"

/**
 * Hands a downloaded update to Android's own installer, which asks the user to confirm.
 * Downloads live in the app's cache and are removed once they're no longer newer.
 */
object UpdateInstaller {
    fun fileFor(context: Context, version: String): File = File(File(context.cacheDir, FOLDER), "$PREFIX$version$SUFFIX")

    /** Removes partial downloads and any for a version that isn't newer than [currentVersion]. */
    fun removeStale(context: Context, currentVersion: String) {
        File(context.cacheDir, FOLDER).listFiles()?.forEach { file ->
            val version = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX)
                .takeIf { file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            if (version == null || !isNewerVersion(version, currentVersion)) file.delete()
        }
    }

    /** Whether the user has let this app install apps, which Android asks once. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Android's page for letting this app install apps. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Whether [apk] is this app, a newer build, and signed with the same key. Android
     * checks the key again when installing; this catches a wrong file before asking.
     */
    fun isUpdateFor(context: Context, apk: File): Boolean {
        val manager = context.packageManager
        val archive = archiveInfo(manager, apk) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = installedInfo(manager, context.packageName)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val ours = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            if (ours != null && theirs != null && ours != theirs) return false
        }
        return true
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun archiveInfo(manager: PackageManager, apk: File): PackageInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return manager.getPackageArchiveInfo(
                apk.path,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        @Suppress("DEPRECATION")
        return manager.getPackageArchiveInfo(apk.path, flags)
    }

    private fun installedInfo(manager: PackageManager, packageName: String): PackageInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return manager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        @Suppress("DEPRECATION")
        return manager.getPackageInfo(packageName, flags)
    }
}
