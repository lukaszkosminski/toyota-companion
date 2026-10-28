package pl.lk.toyotacompanion.core.toyota

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Builds the headers every data request to the Toyota OneAPI gateway needs.
 * Port of pytoyoda `controller.py:492-536` (`_prepare_headers`) and
 * `utils/helpers.py:41-52` (HMAC-SHA256 hex).
 *
 * Decision (verified in pytoyoda): the `datetime` / `Content-Type` headers from
 * `api.py:118-132` are attached only to the vehicle-alias PUT call
 * (`api.py:149`), NOT to the GET endpoints ported here — so they are omitted.
 */
object ToyotaHeaders {

    fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun dataHeaders(
        uuid: String,
        accessToken: String,
        brand: String = "T",
        vin: String? = null,
        correlationId: String = UUID.randomUUID().toString(),
    ): Map<String, String> {
        val headers = mutableMapOf(
            "x-api-key" to ToyotaConst.X_API_KEY,
            "API_KEY" to ToyotaConst.X_API_KEY,
            "x-guid" to uuid,
            "guid" to uuid,
            "x-client-ref" to hmacSha256Hex(ToyotaConst.CLIENT_VERSION, uuid),
            "x-correlationid" to correlationId,
            "x-appversion" to ToyotaConst.CLIENT_VERSION,
            "x-channel" to "ONEAPP",
            "x-brand" to brand,
            "x-region" to "EU",
            "x-user-region" to "EU",
            "authorization" to "Bearer $accessToken",
            "user-agent" to "okhttp/4.10.0",
        )
        if (vin != null) {
            headers["vin"] = vin
        }
        return headers
    }
}
