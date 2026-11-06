package pl.lk.toyotacompanion.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import java.io.File
import pl.lk.toyotacompanion.ToyotaApp
import pl.lk.toyotacompanion.ui.ApkCheck
import pl.lk.toyotacompanion.ui.ApkInstaller
import pl.lk.toyotacompanion.ui.InstallResult

/**
 * Installs a downloaded update through a `PackageInstaller` session (docs/SPEC.md §37). Before the session the APK
 * must be this package, carry the versionCode of the release tag and be signed by the certificate of the installed
 * app; Android refuses another signer anyway, this only gives a clear message instead of a system error.
 */
class AndroidApkInstaller(private val context: Context) : ApkInstaller {
    private val pm: PackageManager get() = context.packageManager

    override fun canInstall(): Boolean = pm.canRequestPackageInstalls()

    /** A ROM without this settings screen leaves the card asking for the permission instead of crashing. */
    override fun openInstallPermissionSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            // Nothing to open; the card keeps its explanation.
        }
    }

    override fun check(apk: File, versionCode: Long): ApkCheck {
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return ApkCheck.UNREADABLE
        if (archive.packageName != context.packageName) return ApkCheck.OTHER_PACKAGE
        if (archive.longVersionCode != versionCode) return ApkCheck.OTHER_VERSION
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val current = installed.currentSigners()
        val accepted = archive.allSigners()
        return if (current.isNotEmpty() && accepted.containsAll(current)) ApkCheck.OK else ApkCheck.OTHER_SIGNER
    }

    override fun install(apk: File) {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val result = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                // Mutable: the installer adds the status and the confirmation intent to it.
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    result,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }
}

private fun PackageInfo.currentSigners(): Set<String> =
    signingInfo?.apkContentsSigners.orEmpty().mapTo(HashSet()) { it.toCharsString() }

/** The current signers plus the rotation history, so an APK signed after a key rotation is still accepted. */
private fun PackageInfo.allSigners(): Set<String> {
    val info = signingInfo ?: return emptySet()
    val history = if (info.hasMultipleSigners()) emptyList() else info.signingCertificateHistory.orEmpty().toList()
    return (info.apkContentsSigners.orEmpty().toList() + history).mapTo(HashSet()) { it.toCharsString() }
}

/**
 * Status of the install session. The first answer is "user action needed": the system confirmation is opened
 * (the owner is usually still in "Opcje"). Android may block that start when the app is no longer in the foreground; the
 * card then stays in INSTALLING, which offers "Pobierz i zainstaluj" again. Not exported: only the installer's PendingIntent reaches it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null) {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        }
        val result = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallResult.SUCCESS
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.ABORTED
            else -> InstallResult.FAILED
        }
        (context.applicationContext as ToyotaApp).container.updateController.onInstallResult(result)
    }
}
