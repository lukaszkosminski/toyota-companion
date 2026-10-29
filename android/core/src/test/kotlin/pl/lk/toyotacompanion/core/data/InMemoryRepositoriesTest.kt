@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.data

import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** Repository contract on the in-memory repositories used by [pl.lk.toyotacompanion.core.sync.SyncEngineTest]. */
class InMemoryRepositoriesTest {
    private val contract = RepositoryContract { InMemoryStores() }

    @Test
    fun `upsert counts only new ids`() = runTest { contract.`upsert counts only new ids`() }

    @Test
    fun `upsert replaces the row of an existing id`() = runTest { contract.`upsert replaces the row of an existing id`() }

    @Test
    fun `trips are separated per vin`() = runTest { contract.`trips are separated per vin`() }

    @Test
    fun `all is oldest first and allSince is newest first`() = runTest { contract.`all is oldest first and allSince is newest first`() }

    @Test
    fun `idsBetween is inclusive at both ends`() = runTest { contract.`idsBetween is inclusive at both ends`() }

    @Test
    fun `addIfAbsent ignores a duplicate refuel id`() = runTest { contract.`addIfAbsent ignores a duplicate refuel id`() }

    @Test
    fun `update keeps the vehicle`() = runTest { contract.`update keeps the vehicle`() }

    @Test
    fun `confirmedUpTo is inclusive and ordered by ts then id`() = runTest { contract.`confirmedUpTo is inclusive and ordered by ts then id`() }

    @Test
    fun `pending is newest first and vin filtered`() = runTest { contract.`pending is newest first and vin filtered`() }

    @Test
    fun `reading of the same vin and ts is stored once`() = runTest { contract.`reading of the same vin and ts is stored once`() }

    @Test
    fun `latest is the newest reading of that vin`() = runTest { contract.`latest is the newest reading of that vin`() }

    @Test
    fun `since is ascending and vin filtered`() = runTest { contract.`since is ascending and vin filtered`() }

    @Test
    fun `costs are replaced per trip id`() = runTest { contract.`costs are replaced per trip id`() }

    @Test
    fun `vehicle upsert keeps firstSeen`() = runTest { contract.`vehicle upsert keeps firstSeen`() }

    @Test
    fun `sync state round trips`() = runTest { contract.`sync state round trips`() }
}
