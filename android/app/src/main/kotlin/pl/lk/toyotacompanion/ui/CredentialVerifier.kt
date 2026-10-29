package pl.lk.toyotacompanion.ui

import pl.lk.toyotacompanion.core.sync.LoginCheck

/**
 * Checks typed credentials against the real Toyota account before anything is
 * stored (docs/SPEC.md §9.1).
 *
 * The production implementation is [pl.lk.toyotacompanion.core.sync.ToyotaGatewayFactory.verifyLogin]
 * reached through this seam, so `app` never builds OkHttp types and tests never
 * touch the network.
 */
fun interface CredentialVerifier {
    suspend fun verify(username: String, password: String): LoginCheck
}
