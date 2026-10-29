package pl.lk.toyotacompanion.security

import java.security.GeneralSecurityException

/**
 * Authenticated encryption of small secrets (docs/SECURITY.md, ADR-007).
 * [aad] is bound to the ciphertext: decrypting with a different [aad] fails.
 * Implementations throw on any failure; callers decide how to degrade.
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray

    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/** The blob is not in a format this cipher understands (permanent: the stored value is unusable). */
class UnsupportedSecretBlobException(message: String = "Unsupported secret blob") : GeneralSecurityException(message)

/** Blob layout shared by implementations: `version (1 B) || IV (12 B) || ciphertext+tag`. */
internal const val BLOB_VERSION: Byte = 1
internal const val GCM_IV_BYTES = 12
internal const val GCM_TAG_BITS = 128
