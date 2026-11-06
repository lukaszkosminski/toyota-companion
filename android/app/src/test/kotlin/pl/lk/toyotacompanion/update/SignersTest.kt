package pl.lk.toyotacompanion.update

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Signer rules of [AndroidApkInstaller.check] as pure functions (docs/SPEC.md §37): the certificate of the installed
 * app must be among the downloaded APK's signers, with the rotation history of the new APK taken into account.
 * No Android here — [AndroidApkInstaller] only feeds the parsed `SigningInfo` into these functions.
 */
class SignersTest {
    private val ours = "our-release-key"
    private val other = "another-key"

    @Test
    fun `the same key matches`() {
        val accepted = acceptedSigners(listOf(ours), emptyList(), multipleSigners = false)
        assertTrue(signerMatches(setOf(ours), accepted))
    }

    @Test
    fun `another key does not match`() {
        val accepted = acceptedSigners(listOf(other), emptyList(), multipleSigners = false)
        assertFalse(signerMatches(setOf(ours), accepted))
    }

    @Test
    fun `an installed app without signers is not accepted`() {
        assertFalse(signerMatches(emptySet(), acceptedSigners(listOf(ours), emptyList(), multipleSigners = false)))
        assertFalse(signerMatches(emptySet(), emptySet()))
    }

    @Test
    fun `an apk signed after a key rotation is accepted`() {
        // The installed app still carries the old key; the new APK is signed with the new one and lists the old
        // key in its lineage (signingCertificateHistory).
        val accepted = acceptedSigners(listOf("new-key"), listOf("new-key", ours), multipleSigners = false)
        assertTrue(signerMatches(setOf(ours), accepted))
    }

    @Test
    fun `multiple signers ignore the history`() {
        // An APK signed by several keys at once has no usable lineage: only its current signers count.
        val accepted = acceptedSigners(listOf(other), listOf(ours), multipleSigners = true)
        assertFalse(signerMatches(setOf(ours), accepted))
        assertEquals(setOf(other), accepted)
    }

    @Test
    fun `our key plus an additional signer still passes this check`() {
        // Deliberately loose: the system refuses a signer set that differs from the installed app
        // (INSTALL_FAILED_UPDATE_INCOMPATIBLE), so this only avoids a confusing system error instead of the
        // clear "another key" message.
        val accepted = acceptedSigners(listOf(ours, other), emptyList(), multipleSigners = true)
        assertTrue(signerMatches(setOf(ours), accepted))
    }
}
