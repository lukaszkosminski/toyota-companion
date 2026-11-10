package pl.lk.toyotacompanion.core.toyota

/**
 * Scrubber for any text that may end up in exception messages or UI errors.
 * Replaces values of secret-looking JSON/form keys with REDACTED and caps
 * length. Never log raw server bodies (docs/SECURITY.md).
 */
object ToyotaSecrets {
    private const val MAX_LEN = 200

    /**
     * A bare `Bearer <credential>` (a header in a message, a body) - the credential is at least eight characters, so the word
     * "Bearer" in a sentence next to a short word stays as it is.
     */
    private val BEARER = Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}")

    /**
     * A secret-looking key with its value: `key=value`, `key: value`, `"key":"value"`. The key may be the end of a longer name
     * (`access_token`, `refreshToken`, `newPassword`, `client_secret`, `sessiontoken`): hiding too much is harmless, showing a
     * secret is not, so `errorcode=503` is hidden as well. An optional `Bearer`/`Basic` scheme belongs to the value, so
     * `Authorization: Bearer abc` ends as `Authorization: REDACTED`.
     */
    private val SECRET_KEY = Regex(
        "(?i)([\"']?[A-Za-z0-9_.-]*(?:password|secret|token_id|tokenid|token|code|authorization|vin)[\"']?\\s*[:=]\\s*[\"']?)" +
            "(?:(?:bearer|basic)\\s+)?[^\"'&,}\\s]+",
    )

    fun sanitize(text: String): String {
        val bearer = BEARER.replace(text) { "${it.groupValues[1]} REDACTED" }
        val redacted = SECRET_KEY.replace(bearer) { "${it.groupValues[1]}REDACTED" }
        val single = redacted.replace('\n', ' ').replace('\r', ' ')
        return if (single.length <= MAX_LEN) single else single.take(MAX_LEN) + "…"
    }

    /**
     * Masks every occurrence of a bare VIN in [text], keeping its last four characters (docs/SECURITY.md,
     * docs/SPEC.md §45-§47). A blank [vin] leaves the text untouched; a value of four characters or fewer
     * is masked completely, so a short value never slips through unmasked. [sanitize] only redacts
     * `key=value` forms, so a message like `VIN1234567890 not found` needs this pass too.
     */
    fun maskVin(text: String, vin: String?): String {
        if (vin.isNullOrBlank() || !text.contains(vin)) return text
        val masked = if (vin.length <= 4) "*".repeat(vin.length) else "*".repeat(vin.length - 4) + vin.takeLast(4)
        return text.replace(vin, masked)
    }
}
