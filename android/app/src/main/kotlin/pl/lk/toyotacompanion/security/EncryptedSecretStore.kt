package pl.lk.toyotacompanion.security

import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Text secrets in [SharedPreferences]: value = Base64 of the [SecretCipher] blob,
 * AAD = the preference key (a value copied under another key does not decrypt).
 *
 * A value that cannot be decrypted reads as `null`; nothing is thrown and nothing
 * about the content is logged. The entry is removed only on permanent damage
 * (bad tag/AAD/changed key, invalid Base64, unsupported blob, permanently
 * invalidated key); any other failure keeps it (docs/SECURITY.md).
 */
class EncryptedSecretStore(
    private val prefs: SharedPreferences,
    private val cipher: SecretCipher,
) {
    fun putText(key: String, value: String) {
        val blob = cipher.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
        // commit(): a just-entered password must be on disk before the caller moves on.
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }

    fun getText(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            String(cipher.decrypt(blob, key.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Only permanent damage deletes the entry. Anything else (e.g. a transient
            // KeyStoreException/ProviderException right after boot) must not destroy the
            // owner's password: report "no value" and keep the entry for the next attempt.
            if (isPermanent(e)) prefs.edit().remove(key).commit()
            null
        }
    }

    private fun isPermanent(e: Exception): Boolean =
        e is AEADBadTagException ||
            e is IllegalArgumentException || // not Base64
            e is UnsupportedSecretBlobException ||
            e is KeyPermanentlyInvalidatedException

    fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }
}
