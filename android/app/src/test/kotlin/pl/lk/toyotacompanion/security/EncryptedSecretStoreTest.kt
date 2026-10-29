package pl.lk.toyotacompanion.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyStoreException
import java.security.ProviderException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lk.toyotacompanion.core.toyota.TokenInfo

@RunWith(RobolectricTestRunner::class)
class EncryptedSecretStoreTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private val cipher = JceAesTestCipher()
    private lateinit var store: EncryptedSecretStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("test-secrets", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = EncryptedSecretStore(prefs, cipher)
    }

    @Test
    fun `text round trips`() {
        store.putText("k", "zażółć gęślą jaźń")

        assertEquals("zażółć gęślą jaźń", store.getText("k"))
        assertNull(store.getText("missing"))
    }

    @Test
    fun `plain text is not in the preferences file`() {
        val secret = "hunter2-very-secret"
        store.putText("k", secret)

        val file = File(context.applicationInfo.dataDir, "shared_prefs/test-secrets.xml")
        assertTrue(file.isFile)
        assertFalse(file.readText().contains(secret))
        assertFalse(prefs.all.values.any { it.toString().contains(secret) })
    }

    @Test
    fun `same text encrypts differently each time`() {
        store.putText("k", "same")
        val first = prefs.getString("k", null)
        store.putText("k", "same")

        assertNotNull(first)
        assertTrue(first != prefs.getString("k", null))
    }

    @Test
    fun `a value moved to another key does not decrypt and is removed`() {
        store.putText("a", "secret")
        prefs.edit().putString("b", prefs.getString("a", null)).commit()

        assertNull(store.getText("b"))
        assertFalse(prefs.contains("b"))
        assertEquals("secret", store.getText("a"))
    }

    @Test
    fun `a corrupted blob reads as null and is removed`() {
        store.putText("k", "secret")
        val tampered = prefs.getString("k", null)!!.let { it.dropLast(6) + "AAAAAA" }
        prefs.edit().putString("k", tampered).commit()

        assertNull(store.getText("k"))
        assertFalse(prefs.contains("k"))
    }

    @Test
    fun `a value that is not Base64 reads as null and is removed`() {
        prefs.edit().putString("k", "%%% not base64 %%%").commit()

        assertNull(store.getText("k"))
        assertFalse(prefs.contains("k"))
    }

    @Test
    fun `a value encrypted with another key reads as null`() {
        store.putText("k", "secret")

        assertNull(EncryptedSecretStore(prefs, JceAesTestCipher()).getText("k"))
        assertFalse(prefs.contains("k"))
    }

    @Test
    fun `an unsupported blob version reads as null and is removed`() {
        store.putText("k", "secret")
        val blob = Base64.decode(prefs.getString("k", null), Base64.NO_WRAP)
        blob[0] = 99
        prefs.edit().putString("k", Base64.encodeToString(blob, Base64.NO_WRAP)).commit()

        assertNull(store.getText("k"))
        assertFalse(prefs.contains("k"))
    }

    @Test
    fun `a permanently invalidated key removes the entry`() {
        store.putText("k", "secret")

        assertNull(failing(KeyPermanentlyInvalidatedException()).getText("k"))
        assertFalse(prefs.contains("k"))
    }

    @Test
    fun `a transient provider failure reads as null but keeps the entry`() {
        store.putText("k", "secret")
        val stored = prefs.getString("k", null)

        assertNull(failing(ProviderException("keystore not ready")).getText("k"))
        assertNull(failing(KeyStoreException("not loaded")).getText("k"))
        assertEquals(stored, prefs.getString("k", null))
        assertEquals("secret", store.getText("k"))
    }

    private fun failing(error: Exception) = EncryptedSecretStore(
        prefs,
        object : SecretCipher {
            override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = throw error

            override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = throw error
        },
    )

    @Test
    fun `remove deletes the entry`() {
        store.putText("k", "secret")
        store.remove("k")

        assertFalse(prefs.contains("k"))
    }

    @Test
    fun `credentials round trip and clear`() {
        val credentials = CredentialStore(store)
        assertNull(credentials.load())

        credentials.save(Credentials("jan@example.com", "p@ss"))

        assertEquals(Credentials("jan@example.com", "p@ss"), credentials.load())
        assertFalse(prefs.all.keys.any { it.contains("jan") })
        credentials.clear()
        assertNull(credentials.load())
    }

    @Test
    fun `credentials with a missing half are treated as absent and cleared`() {
        val credentials = CredentialStore(store)
        credentials.save(Credentials("jan@example.com", "p@ss"))
        store.remove("credentials.password")

        assertNull(credentials.load())
        assertNull(store.getText("credentials.username"))
    }

    @Test
    fun `credentials toString does not leak`() {
        val text = Credentials("jan@example.com", "p@ss").toString()

        assertFalse(text.contains("p@ss"))
        assertFalse(text.contains("jan@example.com"))
    }

    @Test
    fun `token round trips with expiration and is keyed per user`() {
        val tokens = KeystoreTokenStore(store)
        val token = TokenInfo("access", "refresh", "uuid-1", Instant.parse("2026-10-29T12:34:56.789Z"))

        tokens.save("jan@example.com", token)

        assertEquals(token, tokens.load("jan@example.com"))
        assertNull(tokens.load("ewa@example.com"))
        assertFalse(prefs.all.keys.any { it.contains("jan") })
        assertFalse(prefs.all.values.any { it.toString().contains("access") })
        tokens.clear("jan@example.com")
        assertNull(tokens.load("jan@example.com"))
    }

    @Test
    fun `a token that is not valid JSON reads as null and is removed`() {
        val tokens = KeystoreTokenStore(store)
        tokens.save("u", TokenInfo("a", "r", "id", Instant.EPOCH))
        val key = prefs.all.keys.single()
        store.putText(key, "not json")

        assertNull(tokens.load("u"))
        assertFalse(prefs.contains(key))
    }
}
