package pl.lk.toyotacompanion.security

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Same blob format as `AndroidKeystoreCipher`, with an ordinary JCE key (Robolectric has no AndroidKeyStore). */
class JceAesTestCipher(private val key: SecretKey = newKey()) : SecretCipher {
    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return byteArrayOf(BLOB_VERSION) + cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size <= 1 + GCM_IV_BYTES || blob[0] != BLOB_VERSION) throw UnsupportedSecretBlobException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob, 1, GCM_IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, 1 + GCM_IV_BYTES, blob.size - 1 - GCM_IV_BYTES)
    }

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
