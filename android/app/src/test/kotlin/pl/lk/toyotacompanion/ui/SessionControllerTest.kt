package pl.lk.toyotacompanion.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.math.BigDecimal
import java.security.ProviderException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lk.toyotacompanion.core.data.InMemoryTripRepository
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.sync.LoginCheck
import pl.lk.toyotacompanion.core.sync.SyncError
import pl.lk.toyotacompanion.core.sync.LoginFailure
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.core.toyota.TokenInfo
import pl.lk.toyotacompanion.security.CredentialStore
import pl.lk.toyotacompanion.security.Credentials
import pl.lk.toyotacompanion.security.EncryptedSecretStore
import pl.lk.toyotacompanion.security.JceAesTestCipher
import pl.lk.toyotacompanion.security.SecretCipher
import pl.lk.toyotacompanion.security.KeystoreTokenStore
import pl.lk.toyotacompanion.sync.SyncScheduler
import pl.lk.toyotacompanion.sync.SyncRunner

private const val LOGIN = "user@example.com"
private const val PASSWORD = "p@ss-123"

private val TOKEN = TokenInfo("secret-access", "secret-refresh", "uuid-1", Instant.parse("2099-01-01T00:00:00Z"))

private val STATUS = SyncStatus(
    state = SyncState(vin = "VIN1", lastSuccessAt = Instant.parse("2026-10-29T10:00:00Z")),
    tripsStored = 3,
    readingsStored = 2,
    pendingRefuels = 1,
)

/** Stands in for the Toyota login: no network, and it counts its own calls. */
private class FakeVerifier(private val result: LoginCheck) : CredentialVerifier {
    var calls = 0
    var lastUsername: String? = null

    override suspend fun verify(username: String, password: String): LoginCheck {
        calls++
        lastUsername = username
        return result
    }
}

private class FakeRunner(private val outcome: SyncOutcome = SyncOutcome.Ok(1, 1, 0, 1, false, SyncState())) : SyncRunner {
    var calls = 0

    override suspend fun fullSync(): SyncOutcome {
        calls++
        return outcome
    }
}

/** Holds the synchronization until the test releases [gate], to race it against a logout. */
private class WaitingRunner(private val gate: CompletableDeferred<Unit>) : SyncRunner {
    var calls = 0

    override suspend fun fullSync(): SyncOutcome {
        calls++
        gate.await()
        return SyncOutcome.Ok(1, 1, 0, 1, false, SyncState())
    }
}

/** A Keystore that stopped working: every write fails, exactly like a broken provider. */
private class FailingCipher : SecretCipher {
    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray =
        throw ProviderException("Keystore unavailable")

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray =
        throw ProviderException("Keystore unavailable")
}

/** docs/SPEC.md §9 behaviour of the login and status screens, without a real account. */
@RunWith(RobolectricTestRunner::class)
class SessionControllerTest {
    private lateinit var context: Context
    private lateinit var credentials: CredentialStore
    private lateinit var tokens: KeystoreTokenStore
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val store = EncryptedSecretStore(prefs("ui-session-test"), JceAesTestCipher())
        credentials = CredentialStore(store)
        tokens = KeystoreTokenStore(store)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    private fun prefs(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    /**
     * UI scope and io dispatcher both live on the test scheduler, so
     * `advanceUntilIdle()` drives every Keystore, verifier and archive read.
     */
    private fun session(
        io: CoroutineDispatcher,
        verifier: CredentialVerifier = FakeVerifier(LoginCheck.Ok(TOKEN)),
        runner: SyncRunner = FakeRunner(),
        status: suspend () -> SyncStatus = { STATUS },
    ) = SessionController(
        credentials = credentials,
        tokens = tokens,
        verifier = verifier,
        runner = runner,
        status = status,
        workManager = workManager,
        scope = CoroutineScope(SupervisorJob() + io),
        io = io,
    )

    private fun scheduled(name: String) = workManager.getWorkInfosForUniqueWork(name).get()

    @Test
    fun `start without credentials shows login`() = runTest {
        val controller = session(StandardTestDispatcher(testScheduler))

        controller.start()
        advanceUntilIdle()

        assertEquals(UiState.LoggedOut(), controller.state.value)
    }

    @Test
    fun `start with credentials shows status`() = runTest {
        credentials.save(Credentials(LOGIN, PASSWORD))
        val controller = session(StandardTestDispatcher(testScheduler))

        controller.start()
        advanceUntilIdle()

        assertEquals(UiState.LoggedIn(STATUS), controller.state.value)
    }

    @Test
    fun `empty fields do not call verifier`() = runTest {
        val verifier = FakeVerifier(LoginCheck.Ok(TOKEN))
        val controller = session(StandardTestDispatcher(testScheduler), verifier)
        controller.start()
        advanceUntilIdle()

        controller.login("   ", PASSWORD)
        controller.login(LOGIN, "")
        advanceUntilIdle()

        assertEquals(0, verifier.calls)
        assertEquals(LoginError.EMPTY_FIELDS, (controller.state.value as UiState.LoggedOut).error)
        assertNull(credentials.load())
    }

    @Test
    fun `successful login stores credentials and token and schedules sync`() = runTest {
        val verifier = FakeVerifier(LoginCheck.Ok(TOKEN))
        val controller = session(StandardTestDispatcher(testScheduler), verifier)
        controller.start()
        advanceUntilIdle()

        controller.login(" $LOGIN ", PASSWORD)
        advanceUntilIdle()

        assertEquals(1, verifier.calls)
        assertEquals(LOGIN, verifier.lastUsername)
        assertEquals(Credentials(LOGIN, PASSWORD), credentials.load())
        assertNotNull(tokens.load(LOGIN))
        assertEquals(1, scheduled(SyncScheduler.PERIODIC_WORK_NAME).size)
        assertEquals(1, scheduled(SyncScheduler.ONE_TIME_WORK_NAME).size)
        assertEquals(UiState.LoggedIn(STATUS), controller.state.value)
    }

    @Test
    fun `invalid credentials store nothing`() = runTest {
        val controller = session(
            StandardTestDispatcher(testScheduler),
            FakeVerifier(LoginCheck.Failed(LoginFailure.INVALID_CREDENTIALS)),
        )

        controller.start()
        advanceUntilIdle()

        controller.login(LOGIN, PASSWORD)
        advanceUntilIdle()

        assertEquals(LoginError.INVALID_CREDENTIALS, (controller.state.value as UiState.LoggedOut).error)
        assertNull(credentials.load())
        assertNull(tokens.load(LOGIN))
        assertTrue(scheduled(SyncScheduler.PERIODIC_WORK_NAME).isEmpty())
        assertTrue(scheduled(SyncScheduler.ONE_TIME_WORK_NAME).isEmpty())
    }

    @Test
    fun `network failure stores nothing`() = runTest {
        val controller = session(
            StandardTestDispatcher(testScheduler),
            FakeVerifier(LoginCheck.Failed(LoginFailure.NETWORK)),
        )

        controller.start()
        advanceUntilIdle()

        controller.login(LOGIN, PASSWORD)
        advanceUntilIdle()

        assertEquals(LoginError.NETWORK, (controller.state.value as UiState.LoggedOut).error)
        assertNull(credentials.load())
        assertNull(tokens.load(LOGIN))
    }

    @Test
    fun `double submit verifies once`() = runTest {
        val verifier = FakeVerifier(LoginCheck.Ok(TOKEN))
        val controller = session(StandardTestDispatcher(testScheduler), verifier)
        controller.start()
        advanceUntilIdle()

        controller.login(LOGIN, PASSWORD)
        controller.login(LOGIN, PASSWORD)
        advanceUntilIdle()

        assertEquals(1, verifier.calls)
    }

    @Test
    fun `sync now failure is shown and status refreshed`() = runTest {
        credentials.save(Credentials(LOGIN, PASSWORD))
        var reads = 0
        val runner = FakeRunner(SyncOutcome.Failed(SyncError(SyncErrorKind.NETWORK, "offline"), SyncState()))
        val controller = session(
            StandardTestDispatcher(testScheduler),
            runner = runner,
            status = { reads++; STATUS },
        )

        controller.start()
        advanceUntilIdle()
        assertEquals(1, reads)

        controller.syncNow()
        advanceUntilIdle()

        val state = controller.state.value as UiState.LoggedIn
        assertEquals(1, runner.calls)
        assertEquals(SyncErrorKind.NETWORK, state.lastSyncFailed)
        assertFalse(state.syncing)
        assertEquals(2, reads)
    }

    @Test
    fun `double sync now runs once`() = runTest {
        credentials.save(Credentials(LOGIN, PASSWORD))
        val runner = FakeRunner()
        val controller = session(StandardTestDispatcher(testScheduler), runner = runner)

        controller.start()
        advanceUntilIdle()
        controller.syncNow()
        controller.syncNow()
        advanceUntilIdle()

        assertEquals(1, runner.calls)
    }

    @Test
    fun `logout clears credentials and token and cancels work but keeps archive`() = runTest {
        val trips = InMemoryTripRepository()
        trips.upsertAll(
            listOf(
                TripRecord(
                    trip = Trip("trip-1", Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T08:30:00Z"), 14.6, BigDecimal("0.600"), null),
                    vin = "VIN1",
                ),
            ),
        )
        credentials.save(Credentials(LOGIN, PASSWORD))
        tokens.save(LOGIN, TOKEN)
        SyncScheduler.onLogin(workManager)
        val controller = session(
            StandardTestDispatcher(testScheduler),
            status = { SyncStatus(SyncState(vin = "VIN1"), tripsStored = trips.count(), 0, 0) },
        )

        controller.start()
        advanceUntilIdle()

        controller.logout()
        advanceUntilIdle()

        assertNull(credentials.load())
        assertNull(tokens.load(LOGIN))
        assertEquals(UiState.LoggedOut(), controller.state.value)
        assertEquals(WorkInfo.State.CANCELLED, scheduled(SyncScheduler.PERIODIC_WORK_NAME).single().state)
        assertEquals(WorkInfo.State.CANCELLED, scheduled(SyncScheduler.ONE_TIME_WORK_NAME).single().state)
        assertEquals(1, trips.count(), "logout must never delete the archive")
    }

    @Test
    fun `no ui state contains the password`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val seen = mutableListOf<UiState>()

        for (result in listOf(
            LoginCheck.Ok(TOKEN),
            LoginCheck.Failed(LoginFailure.INVALID_CREDENTIALS),
            LoginCheck.Failed(LoginFailure.NETWORK),
            LoginCheck.Failed(LoginFailure.SERVICE),
        )) {
            val controller = session(dispatcher, FakeVerifier(result))
            seen += controller.state.value
            controller.login(LOGIN, PASSWORD)
            seen += controller.state.value
            advanceUntilIdle()
            seen += controller.state.value
        }

        assertTrue(seen.isNotEmpty())
        for (state in seen) {
            val text = state.toString()
            assertFalse(PASSWORD in text, "${state.javaClass.simpleName}: $text")
            assertFalse("secret-access" in text, "${state.javaClass.simpleName}: $text")
        }
        for (error in LoginError.entries) {
            assertFalse(PASSWORD in Texts.loginError(error), error.name)
        }
    }

    @Test
    fun `start again keeps the current state`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = WaitingRunner(gate)
        val controller = session(StandardTestDispatcher(testScheduler), runner = runner)

        controller.start()
        advanceUntilIdle()
        assertEquals(UiState.LoggedOut(), controller.state.value)

        controller.login(LOGIN, PASSWORD)
        controller.start() // onCreate after a rotation: must not unlock the busy form

        assertTrue((controller.state.value as UiState.LoggedOut).busy)
        advanceUntilIdle()
        assertEquals(UiState.LoggedIn(STATUS), controller.state.value)

        controller.syncNow()
        controller.start() // again: must not unlock the running synchronization

        assertTrue((controller.state.value as UiState.LoggedIn).syncing)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, runner.calls)
        assertEquals(UiState.LoggedIn(STATUS), controller.state.value)
    }

    @Test
    fun `logout during sync stays logged out`() = runTest {
        credentials.save(Credentials(LOGIN, PASSWORD))
        val gate = CompletableDeferred<Unit>()
        val controller = session(StandardTestDispatcher(testScheduler), runner = WaitingRunner(gate))

        controller.start()
        advanceUntilIdle()
        controller.syncNow()
        advanceUntilIdle()

        controller.logout()
        advanceUntilIdle()
        assertEquals(UiState.LoggedOut(), controller.state.value)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(UiState.LoggedOut(), controller.state.value)
    }

    @Test
    fun `login is ignored while loading`() = runTest {
        val verifier = FakeVerifier(LoginCheck.Ok(TOKEN))
        val controller = session(StandardTestDispatcher(testScheduler), verifier)

        controller.login(LOGIN, PASSWORD)
        advanceUntilIdle()

        assertEquals(0, verifier.calls)
        assertEquals(UiState.Loading, controller.state.value)
    }

    @Test
    fun `storage failure after verification stores nothing and shows service error`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val failingPrefs = prefs("ui-session-failing")
        val failing = EncryptedSecretStore(failingPrefs, FailingCipher())
        val controller = SessionController(
            credentials = CredentialStore(failing),
            tokens = KeystoreTokenStore(failing),
            verifier = FakeVerifier(LoginCheck.Ok(TOKEN)),
            runner = FakeRunner(),
            status = { STATUS },
            workManager = workManager,
            scope = CoroutineScope(SupervisorJob() + io),
            io = io,
        )

        controller.start() // the broken Keystore reads as “no login stored”
        advanceUntilIdle()

        controller.login(LOGIN, PASSWORD)
        advanceUntilIdle()

        assertEquals(LoginError.SERVICE, (controller.state.value as UiState.LoggedOut).error)
        assertTrue(failingPrefs.all.isEmpty(), failingPrefs.all.toString())
        assertTrue(scheduled(SyncScheduler.PERIODIC_WORK_NAME).isEmpty())
        assertTrue(scheduled(SyncScheduler.ONE_TIME_WORK_NAME).isEmpty())
    }
}
