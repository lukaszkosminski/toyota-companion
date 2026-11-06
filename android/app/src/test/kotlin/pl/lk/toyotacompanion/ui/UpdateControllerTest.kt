package pl.lk.toyotacompanion.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.update.AppRelease
import pl.lk.toyotacompanion.core.update.AppUpdateException
import pl.lk.toyotacompanion.core.update.AppUpdateGateway
import pl.lk.toyotacompanion.core.update.UpdateErrorKind

private val RELEASE = AppRelease("0.2.0", 200, "https://github.com/o/r/releases/download/v0.2.0/a.apk", 3, null)

private class FakeUpdates : AppUpdateGateway {
    var latest: AppRelease? = RELEASE
    var checkFailure: AppUpdateException? = null
    var downloadFailure: AppUpdateException? = null
    var downloads = 0
    var latestCalls = 0

    override suspend fun latest(): AppRelease? {
        latestCalls++
        checkFailure?.let { throw it }
        return latest
    }

    override suspend fun download(release: AppRelease, target: File, onProgress: (Long) -> Unit) {
        downloads++
        downloadFailure?.let { throw it }
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3))
        onProgress(3)
    }
}

private class FakeInstaller : ApkInstaller {
    var allowed = true
    var check = ApkCheck.OK
    var installed: File? = null
    var installFailure: Exception? = null
    var settingsOpened = 0

    override fun canInstall() = allowed
    override fun openInstallPermissionSettings() { settingsOpened++ }
    override fun check(apk: File, versionCode: Long) = check

    override fun install(apk: File) {
        installed = apk
        installFailure?.let { throw it }
    }
}

class UpdateControllerTest {
    private val gateway = FakeUpdates()
    private val installer = FakeInstaller()
    private val apk = Files.createTempDirectory("upd").resolve("updates/update.apk").toFile()

    private fun TestScope.controller(installedCode: Long = 100): UpdateController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return UpdateController(gateway, installer, installedCode, "0.1.0", apk, CoroutineScope(SupervisorJob() + dispatcher), dispatcher)
    }

    @Test
    fun `nothing happens before the tap`() = runTest {
        val c = controller()
        advanceUntilIdle()
        assertEquals(UpdatePhase.IDLE, c.state.value.phase)
        assertEquals("0.1.0", c.state.value.currentVersion)
        // SPEC §37 / PRD F36: nothing goes to the network until the owner taps "Sprawdź aktualizacje".
        assertEquals(0, gateway.latestCalls)
        assertEquals(0, gateway.downloads)
    }

    @Test
    fun `newer release is offered`() = runTest {
        val c = controller()
        c.check()
        assertEquals(UpdatePhase.CHECKING, c.state.value.phase)
        advanceUntilIdle()
        assertEquals(UpdatePhase.AVAILABLE, c.state.value.phase)
        assertEquals(RELEASE, c.state.value.release)
    }

    @Test
    fun `same or older release and no release are up to date`() = runTest {
        val same = controller(installedCode = 200)
        same.check()
        advanceUntilIdle()
        assertEquals(UpdatePhase.UP_TO_DATE, same.state.value.phase)
        assertNull(same.state.value.release)

        gateway.latest = null
        val none = controller()
        none.check()
        advanceUntilIdle()
        assertEquals(UpdatePhase.UP_TO_DATE, none.state.value.phase)
    }

    @Test
    fun `check failure keeps only the kind`() = runTest {
        gateway.checkFailure = AppUpdateException(UpdateErrorKind.API, "x")
        val c = controller()
        c.check()
        advanceUntilIdle()
        assertEquals(UpdatePhase.FAILED, c.state.value.phase)
        assertEquals(UpdateFailure.API, c.state.value.failure)
    }

    @Test
    fun `install without permission asks for it and downloads nothing`() = runTest {
        installer.allowed = false
        val c = controller()
        c.check()
        advanceUntilIdle()
        c.install()
        advanceUntilIdle()
        assertEquals(UpdatePhase.NEEDS_PERMISSION, c.state.value.phase)
        assertEquals(0, gateway.downloads)
        c.openInstallPermissionSettings()
        assertEquals(1, installer.settingsOpened)
    }

    @Test
    fun `checked apk goes to the installer`() = runTest {
        val c = controller()
        c.check()
        advanceUntilIdle()
        c.install()
        advanceUntilIdle()
        assertEquals(UpdatePhase.INSTALLING, c.state.value.phase)
        assertEquals(3L, c.state.value.downloaded)
        assertEquals(apk, installer.installed)
    }

    @Test
    fun `apk of another signer is deleted and not installed`() = runTest {
        installer.check = ApkCheck.OTHER_SIGNER
        val c = controller()
        c.check()
        advanceUntilIdle()
        c.install()
        advanceUntilIdle()
        assertEquals(UpdateFailure.BAD_APK, c.state.value.failure)
        assertNull(installer.installed)
        assertFalse(apk.exists())
    }

    @Test
    fun `unreadable, other package or other version is deleted and not installed`() = runTest {
        for (bad in listOf(ApkCheck.UNREADABLE, ApkCheck.OTHER_PACKAGE, ApkCheck.OTHER_VERSION)) {
            installer.check = bad
            installer.installed = null
            val c = controller()
            c.check()
            advanceUntilIdle()
            c.install()
            advanceUntilIdle()
            assertEquals(UpdatePhase.FAILED, c.state.value.phase, "phase for $bad")
            assertEquals(UpdateFailure.BAD_APK, c.state.value.failure, "failure for $bad")
            assertNull(installer.installed, "installer called for $bad")
            assertFalse(apk.exists(), "apk kept for $bad")
        }
    }

    @Test
    fun `installer failure is an install failure and removes the file`() = runTest {
        installer.installFailure = IllegalStateException("session broken")
        val c = controller()
        c.check()
        advanceUntilIdle()
        c.install()
        advanceUntilIdle()
        assertEquals(apk, installer.installed)
        assertEquals(UpdatePhase.FAILED, c.state.value.phase)
        assertEquals(UpdateFailure.INSTALL, c.state.value.failure)
        assertFalse(apk.exists())
    }

    @Test
    fun `download failure can be retried`() = runTest {
        gateway.downloadFailure = AppUpdateException(UpdateErrorKind.NETWORK, "x")
        val c = controller()
        c.check()
        advanceUntilIdle()
        c.install()
        advanceUntilIdle()
        assertEquals(UpdateFailure.NETWORK, c.state.value.failure)

        gateway.downloadFailure = null
        c.install()
        advanceUntilIdle()
        assertEquals(UpdatePhase.INSTALLING, c.state.value.phase)
    }

    @Test
    fun `install can start over when the system confirmation never showed up`() = runTest {
        val c = controller()
        c.check()
        advanceUntilIdle()
        c.install()
        advanceUntilIdle()
        assertEquals(UpdatePhase.INSTALLING, c.state.value.phase)

        c.install()
        advanceUntilIdle()
        assertEquals(2, gateway.downloads)
        assertEquals(UpdatePhase.INSTALLING, c.state.value.phase)
    }

    @Test
    fun `cancelled confirmation offers the release again and removes the file`() = runTest {
        val c = controller()
        c.check()
        advanceUntilIdle()
        c.install()
        advanceUntilIdle()
        c.onInstallResult(InstallResult.ABORTED)
        assertEquals(UpdatePhase.AVAILABLE, c.state.value.phase)
        assertFalse(apk.exists())

        c.onInstallResult(InstallResult.FAILED)
        assertEquals(UpdateFailure.INSTALL, c.state.value.failure)
    }
}
