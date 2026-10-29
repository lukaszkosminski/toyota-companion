package pl.lk.toyotacompanion.security

/** MyToyota login. [toString] never prints the password. */
class Credentials(val username: String, val password: String) {
    override fun toString(): String = "Credentials(username=<redacted>, password=<redacted>)"

    override fun equals(other: Any?): Boolean =
        other is Credentials && other.username == username && other.password == password

    override fun hashCode(): Int = 31 * username.hashCode() + password.hashCode()
}

/** Login and password, encrypted at rest (ADR-007). */
class CredentialStore(private val store: EncryptedSecretStore) {
    fun save(credentials: Credentials) {
        store.putText(USERNAME_KEY, credentials.username)
        store.putText(PASSWORD_KEY, credentials.password)
    }

    /** Both parts or null; a half-readable pair is treated as missing and cleared. */
    fun load(): Credentials? {
        val username = store.getText(USERNAME_KEY)
        val password = store.getText(PASSWORD_KEY)
        if (username == null || password == null) {
            if (username != null || password != null) clear()
            return null
        }
        return Credentials(username, password)
    }

    fun clear() {
        store.remove(USERNAME_KEY)
        store.remove(PASSWORD_KEY)
    }

    private companion object {
        const val USERNAME_KEY = "credentials.username"
        const val PASSWORD_KEY = "credentials.password"
    }
}
