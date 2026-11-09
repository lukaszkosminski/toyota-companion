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
