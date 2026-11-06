package pl.lk.toyotacompanion.ui

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.update.AppRelease
import pl.lk.toyotacompanion.core.update.AppUpdateException
import pl.lk.toyotacompanion.core.update.AppUpdateGateway
import pl.lk.toyotacompanion.core.update.UpdateErrorKind

/** What the downloaded APK turned out to be, checked before it is handed to the system installer. */
enum class ApkCheck { OK, UNREADABLE, OTHER_PACKAGE, OTHER_VERSION, OTHER_SIGNER }

/** The Android side of an update: permission to install and the `PackageInstaller` session (docs/SPEC.md §37). */
interface ApkInstaller {
    /** "Install unknown apps" is allowed for this app. */
    fun canInstall(): Boolean

    fun openInstallPermissionSettings()

    /** Package name, versionCode and signing certificate of [apk] against the installed app. */
    fun check(apk: File, versionCode: Long): ApkCheck

    /** Starts the system install; the result comes back through [UpdateController.onInstallResult]. */
    fun install(apk: File)
}

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, NEEDS_PERMISSION, DOWNLOADING, INSTALLING, FAILED }

enum class UpdateFailure { NETWORK, API, DATA, BAD_APK, INSTALL }

/** Outcome of the system installer, mapped from `PackageInstaller.STATUS_*` by the receiver. */
enum class InstallResult { SUCCESS, ABORTED, FAILED }

/**
 * @param currentVersion versionName of the installed app
 * @param release the newer release, once found
 * @param downloaded bytes of the APK downloaded so far
 * @param failure why the last step failed; the text comes from [Texts], never from an exception
 */
data class UpdateState(
    val currentVersion: String,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: AppRelease? = null,
    val downloaded: Long = 0,
    val failure: UpdateFailure? = null,
)

/**
 * App update from GitHub Releases (PRD F36, docs/SPEC.md §37): nothing goes to the network until the owner taps
 * "Sprawdź aktualizacje" in "Opcje". A newer release is downloaded only after "Pobierz i zainstaluj", checked
 * (size, SHA-256, package, versionCode, signing certificate) and handed to the system installer, which asks the
 * owner once more. Independent of the Toyota login, so logging out does not reset it.
 */
class UpdateController(
    private val gateway: AppUpdateGateway,
    private val installer: ApkInstaller,
    private val currentVersionCode: Long,
    currentVersionName: String,
    private val apkFile: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(UpdateState(currentVersion = currentVersionName))
    val state: StateFlow<UpdateState> = ui.asStateFlow()

    private var job: Job? = null

    fun check() {
        if (job?.isActive == true) return
        ui.update { it.copy(phase = UpdatePhase.CHECKING, failure = null) }
        job = scope.launch {
            val release = try {
                withContext(io) { gateway.latest() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppUpdateException) {
                fail(e.kind.toFailure())
                return@launch
            } catch (e: Exception) {
                fail(UpdateFailure.DATA)
                return@launch
            }
            ui.update {
                if (release == null || release.versionCode <= currentVersionCode) {
                    it.copy(phase = UpdatePhase.UP_TO_DATE, release = null)
                } else {
                    it.copy(phase = UpdatePhase.AVAILABLE, release = release)
                }
            }
        }
    }

    /** Downloads and installs the found release; first sends the owner to the settings when installing is not allowed. */
    fun install() {
        val release = ui.value.release ?: return
        if (job?.isActive == true) return
        if (!installer.canInstall()) {
            ui.update { it.copy(phase = UpdatePhase.NEEDS_PERMISSION, failure = null) }
            return
        }
        ui.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0, failure = null) }
        job = scope.launch {
            val failure = try {
                withContext(io) {
                    apkFile.delete()
                    gateway.download(release, apkFile) { bytes -> ui.update { it.copy(downloaded = bytes) } }
                    if (installer.check(apkFile, release.versionCode) != ApkCheck.OK) {
                        apkFile.delete()
                        UpdateFailure.BAD_APK
                    } else {
                        ui.update { it.copy(phase = UpdatePhase.INSTALLING) }
                        installer.install(apkFile)
                        null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppUpdateException) {
                e.kind.toFailure()
            } catch (e: Exception) {
                apkFile.delete()
                UpdateFailure.INSTALL
            }
            if (failure != null) fail(failure)
        }
    }

    fun openInstallPermissionSettings() = installer.openInstallPermissionSettings()

    /** The system installer finished; on success the process is replaced, so only the other outcomes matter here. */
    fun onInstallResult(result: InstallResult) {
        apkFile.delete()
        when (result) {
            InstallResult.SUCCESS -> ui.update { it.copy(phase = UpdatePhase.UP_TO_DATE, release = null) }
            InstallResult.ABORTED -> ui.update { it.copy(phase = UpdatePhase.AVAILABLE) }
            InstallResult.FAILED -> fail(UpdateFailure.INSTALL)
        }
    }

    private fun fail(failure: UpdateFailure) {
        ui.update { it.copy(phase = UpdatePhase.FAILED, failure = failure) }
    }

    private fun UpdateErrorKind.toFailure(): UpdateFailure = when (this) {
        UpdateErrorKind.NETWORK -> UpdateFailure.NETWORK
        UpdateErrorKind.API -> UpdateFailure.API
        UpdateErrorKind.DATA -> UpdateFailure.DATA
    }
}
