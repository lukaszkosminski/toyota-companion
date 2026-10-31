package pl.lk.toyotacompanion.ui

import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.sync.LoginCheck
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.core.toyota.TokenInfo
import pl.lk.toyotacompanion.core.toyota.TokenStore
import pl.lk.toyotacompanion.security.CredentialStore
import pl.lk.toyotacompanion.security.Credentials
import pl.lk.toyotacompanion.sync.SyncScheduler
import pl.lk.toyotacompanion.sync.SyncRunner

/**
 * State holder of the login and status screens (docs/SPEC.md §9).
 *
 * Not a ViewModel: `lifecycle-viewmodel-compose` is not a dependency of this
 * module, so the controller lives in [pl.lk.toyotacompanion.AppContainer] and
 * exposes a [StateFlow] the Composables collect.
 *
 * The password is a parameter only: it goes to [CredentialVerifier] and, on
 * success, to [CredentialStore] — never into [state] (docs/SECURITY.md).
 *
 * @param status reads the archive plus sync bookkeeping (`SyncEngine.status()`).
 * @param scope UI scope (Main) the state-changing coroutines run in.
 * @param io dispatcher for the Keystore and Room reads, injected for tests.
 */
class SessionController(
    private val credentials: CredentialStore,
    private val tokens: TokenStore,
    private val verifier: CredentialVerifier,
    private val runner: SyncRunner,
    private val status: suspend () -> SyncStatus,
    private val workManager: WorkManager,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = ui.asStateFlow()

    /**
     * Which screen to show: reads the stored login once (docs/SPEC.md §9.2).
     *
     * Idempotent — only the very first call (state still [UiState.Loading]) does
     * anything, so [MainActivity.onCreate] on every rotation cannot unlock a
     * login or a synchronization that is still running.
     */
    fun start() {
        if (ui.value !is UiState.Loading) return
        scope.launch {
            // A store that cannot be read means "not logged in", never a crash at start.
            val stored = try {
                withContext(io) { credentials.load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (ui.value !is UiState.Loading) return@launch
            ui.value = if (stored == null) UiState.LoggedOut() else UiState.LoggedIn(status = readStatus())
        }
    }

    /**
     * Verifies first, stores only on success. Clicks are ignored while the form
     * is busy or still loading, and a result only counts while the screen is
     * still waiting for it (docs/SPEC.md §9.2).
     */
    fun login(username: String, password: String) {
        val shown = ui.value
        if (shown !is UiState.LoggedOut || shown.busy) return
        val login = username.trim()
        if (login.isEmpty() || password.isEmpty()) {
            ui.value = UiState.LoggedOut(LoginError.EMPTY_FIELDS)
            return
        }
        val waiting = UiState.LoggedOut(busy = true)
        ui.value = waiting
        scope.launch {
            when (val check = withContext(io) { verifier.verify(login, password) }) {
                is LoginCheck.Ok -> {
                    if (!store(waiting, login, password, check.token)) return@launch
                    if (ui.value != waiting) return@launch
                    SyncScheduler.onLogin(workManager)
                    ui.value = UiState.LoggedIn(status = readStatus())
                }

                is LoginCheck.Failed ->
                    if (ui.value == waiting) ui.value = UiState.LoggedOut(check.reason.toLoginError())
            }
        }
    }

    /**
     * Stores the login and the token, or removes whatever a half-failed write
     * left behind: a Keystore that refuses to encrypt after Toyota already
     * accepted the credentials must not crash the app nor leave partial data
     * (docs/SPEC.md §9.2). Returns false and shows the service error in that case.
     */
    private suspend fun store(
        waiting: UiState.LoggedOut,
        login: String,
        password: String,
        token: TokenInfo,
    ): Boolean =
        try {
            withContext(io) {
                credentials.save(Credentials(login, password))
                tokens.save(login, token)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(io) {
                // Best effort: the same Keystore that just failed may fail again.
                runCatching { tokens.clear(login) }
                runCatching { credentials.clear() }
            }
            if (ui.value == waiting) ui.value = UiState.LoggedOut(LoginError.SERVICE)
            false
        }

    /** Runs one synchronization in this process; WorkManager stays the background driver. */
    fun syncNow() {
        val current = ui.value as? UiState.LoggedIn ?: return
        if (current.syncing) return
        ui.value = current.copy(syncing = true)
        scope.launch {
            // `SyncEngine` returns outcomes, but the runner around it may still throw; the
            // button must never stay stuck on "syncing" (docs/SPEC.md §9.3).
            val failedKind: SyncErrorKind? = try {
                (withContext(io) { runner.fullSync() } as? SyncOutcome.Failed)?.error?.kind
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncErrorKind.STORAGE
            }
            // Logged out while synchronizing: the late result must not resurrect the status screen.
            val waiting = ui.value as? UiState.LoggedIn ?: return@launch
            ui.value = waiting.copy(
                status = readStatus(),
                syncing = false,
                lastSyncFailed = failedKind,
            )
        }
    }

    /**
     * Pull-to-refresh and "Odśwież": repeats the local read only (docs/SPEC.md §9.3).
     * Never asks Toyota — and never `refresh_status()`, which would drain the
     * 12 V battery (docs/SPEC.md §9.3). Ignored while a synchronization is running.
     */
    fun reload() {
        val shown = ui.value as? UiState.LoggedIn ?: return
        if (shown.syncing) return
        scope.launch {
            val current = ui.value as? UiState.LoggedIn ?: return@launch
            if (current.syncing) return@launch
            ui.value = current.copy(status = readStatus() ?: current.status)
        }
    }

    /** Tokens first, then the login, then the scheduled work; the archive is never touched. */
    fun logout() {
        scope.launch {
            // Every step is best effort: whatever fails, the user ends up logged out.
            withContext(io) {
                val stored = bestEffort { credentials.load() }
                if (stored != null) bestEffort { tokens.clear(stored.username) }
                bestEffort { credentials.clear() }
            }
            bestEffort { workManager.cancelUniqueWork(SyncScheduler.PERIODIC_WORK_NAME) }
            bestEffort { workManager.cancelUniqueWork(SyncScheduler.ONE_TIME_WORK_NAME) }
            ui.value = UiState.LoggedOut(null)
        }
    }

    /** A failing status read leaves "brak danych" on screen instead of crashing the app. */
    /** Runs [step] and swallows its failure; never used for cancellation. */
    private inline fun <T> bestEffort(step: () -> T): T? =
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun readStatus(): SyncStatus? =
        try {
            withContext(io) { status() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}
