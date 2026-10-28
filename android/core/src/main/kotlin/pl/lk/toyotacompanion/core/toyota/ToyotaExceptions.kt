package pl.lk.toyotacompanion.core.toyota

/**
 * Exception hierarchy mirroring pytoyoda `exceptions.py:5-25`.
 *
 * SECURITY: messages must never contain passwords, tokens, VIN, tokenId or
 * authorization codes (docs/SECURITY.md). Use [ToyotaSecrets.sanitize] before
 * embedding any server body text.
 */
open class ToyotaLoginException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class ToyotaInvalidUsernameException(message: String, cause: Throwable? = null) :
    ToyotaLoginException(message, cause)

class ToyotaApiException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("Toyota API request failed (HTTP $statusCode): $message", cause)

class ToyotaNetworkException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class ToyotaDataException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
