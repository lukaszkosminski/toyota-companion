package pl.lk.toyotacompanion.core.toyota

/**
 * Scrubber for any text that may end up in exception messages or UI errors.
 * Replaces values of secret-looking JSON/form keys with REDACTED and caps
 * length. Never log raw server bodies (docs/SECURITY.md).
 */
object ToyotaSecrets {
    private const val MAX_LEN = 200

    private val SECRET_KEY = Regex(
        "(?i)([\"']?(password|access_token|refresh_token|id_token|token_id|tokenid|code|authorization|vin)[\"']?\\s*[:=]\\s*[\"']?)([^\"'&,}\\s]+)",
    )

    fun sanitize(text: String): String {
        val redacted = SECRET_KEY.replace(text) { "${it.groupValues[1]}REDACTED" }
        val single = redacted.replace('\n', ' ').replace('\r', ' ')
        return if (single.length <= MAX_LEN) single else single.take(MAX_LEN) + "…"
    }
}
