package pl.lk.toyotacompanion.core.toyota

import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Injectable endpoint URLs (defaults are the real Toyota EU constants; tests
 * point them at a mock server).
 */
data class ToyotaUrls(
    val apiBase: String = ToyotaConst.API_BASE_URL,
    val authenticate: String = ToyotaConst.AUTHENTICATE_URL,
    val authorize: String = ToyotaConst.AUTHORIZE_URL,
    val accessToken: String = ToyotaConst.ACCESS_TOKEN_URL,
)

/**
 * Toyota OAuth controller: login (authenticate -> authorize -> tokens),
 * refresh-token flow and gateway requests with limited retry/backoff.
 *
 * Port of pytoyoda `controller.py` (MIT; see NOTICE). No logging anywhere in
 * core; exception messages are sanitized of secrets (SECURITY.md).
 *
 * Deliberately NOT ported: refresh_status / vehicle wake, Subaru realm,
 * hishel response caching, SSL-context executor plumbing, global token cache
 * (replaced by [TokenStore]).
 */
class ToyotaController(
    private val username: String,
    private val password: String,
    private val client: OkHttpClient,
    private val urls: ToyotaUrls = ToyotaUrls(),
    private val tokenStore: TokenStore = InMemoryTokenStore(),
    private val clock: () -> Instant = { Instant.now() },
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it * 1000L) },
    private val brand: String = "T",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private var tokenInfo: TokenInfo? = null

    /** Full login unless a valid token is cached. pytoyoda controller.py:169-172 */
    suspend fun login() {
        if (!currentTokenValid()) {
            updateToken()
        }
    }

    private fun currentTokenValid(): Boolean {
        if (tokenInfo == null) {
            tokenInfo = tokenStore.load(username)
        }
        return tokenInfo?.isValidAt(clock()) == true
    }

    /** Refresh first when possible, else full authentication. pytoyoda :174-190 */
    private suspend fun updateToken(): TokenInfo {
        val current = tokenInfo ?: tokenStore.load(username)
        if (current != null && current.isValidAt(clock())) {
            tokenInfo = current
            return current
        }
        if (!current?.refreshToken.isNullOrEmpty()) {
            try {
                return refreshTokens(requireNotNull(current).refreshToken)
            } catch (e: ToyotaLoginException) {
                // fall through to full authentication (pytoyoda controller.py:181-185)
            }
        }
        return authenticate()
    }

    /** Full browserless OAuth login. pytoyoda controller.py:215-230 */
    private suspend fun authenticate(): TokenInfo {
        val tokenId = performAuthentication()
        val code = performAuthorization(tokenId)
        val data = postFormJson(
            url = urls.accessToken,
            form = mapOf(
                "client_id" to ToyotaConst.CLIENT_ID,
                "code" to code,
                "redirect_uri" to ToyotaConst.REDIRECT_URI,
                "grant_type" to "authorization_code",
                "code_verifier" to "plain",
            ),
            authorizationHeader = ToyotaConst.BASIC_AUTH,
        )
        return updateTokens(data)
    }

    /**
     * Loop the AMI callback dance until `tokenId`.
     * pytoyoda controller.py:232-266 — up to 10 iterations, fills
     * NameCallback("User Name") and PasswordCallback; "User Not Found"
     * raises [ToyotaInvalidUsernameException].
     */
    private suspend fun performAuthentication(): String {
        var data: JsonObject = buildJsonObject {}
        repeat(MAX_AUTH_ITERATIONS) {
            val (status, body) = postJson(urls.authenticate, data)
            if (status != HTTP_OK) {
                throw ToyotaLoginException(
                    "Authentication failed. status=$status, body=${ToyotaSecrets.sanitize(body)}",
                )
            }
            val step = parseObject(body)
            val tokenId = step["tokenId"].asContentStringOrNull()
            if (tokenId != null) {
                return tokenId
            }
            data = fillCallbacks(step)
        }
        throw ToyotaLoginException(
            "Authentication failed. Token ID not received after $MAX_AUTH_ITERATIONS attempts.",
        )
    }

    private suspend fun fillCallbacks(step: JsonObject): JsonObject {
        val callbacks = step["callbacks"] as? JsonArray ?: return step
        val filled = callbacks.map { element ->
            val cb = element as? JsonObject ?: return@map element
            when (cb["type"].asContentStringOrNull()) {
                "NameCallback" ->
                    if (cb.callbackOutputValue(0) == "User Name") {
                        cb.withCallbackInput(0, username)
                    } else {
                        cb
                    }
                "PasswordCallback" -> cb.withCallbackInput(0, password)
                "TextOutputCallback" -> {
                    if (cb.callbackOutputValue(0) == "User Not Found") {
                        throw ToyotaInvalidUsernameException("Authentication Failed. User Not Found.")
                    }
                    cb
                }
                else -> cb
            }
        }
        return JsonObject(step + ("callbacks" to JsonArray(filled)))
    }

    /**
     * GET authorize with the session cookie; redirects are disabled on a
     * derived client; the authorization code is extracted from the Location
     * URL's query (the nonstandard scheme "com.toyota.oneapp:" is not
     * parseable by HttpUrl) with percent-decoding.
     * pytoyoda controller.py:269-293
     */
    private suspend fun performAuthorization(tokenId: String): String {
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url(urls.authorize)
            .header("cookie", "iPlanetDirectoryPro=$tokenId")
            .get()
            .build()
        execute(noRedirect, request).use { response ->
            if (response.code != HTTP_FOUND) {
                val body = response.body?.string().orEmpty()
                throw ToyotaLoginException(
                    "Authorization failed. status=${response.code}, body=${ToyotaSecrets.sanitize(body)}",
                )
            }
            val location = response.header("location")
                ?: throw ToyotaLoginException("Authorization failed. Missing Location header.")
            return extractQueryParam(location, "code")
                ?: throw ToyotaLoginException("Authorization failed. No code in Location.")
        }
    }

    /** pytoyoda controller.py:328-349 */
    private suspend fun refreshTokens(refreshToken: String): TokenInfo {
        val data = postFormJson(
            url = urls.accessToken,
            form = mapOf(
                "client_id" to ToyotaConst.CLIENT_ID,
                "redirect_uri" to ToyotaConst.REDIRECT_URI,
                "grant_type" to "refresh_token",
                "code_verifier" to "plain",
                "refresh_token" to refreshToken,
            ),
            authorizationHeader = ToyotaConst.BASIC_AUTH,
        )
        return updateTokens(data)
    }

    /**
     * Validate + store token payload. pytoyoda controller.py:352-390:
     * required fields, uuid via [JwtUuid], expiration = now + expires_in.
     */
    private fun updateTokens(data: JsonObject): TokenInfo {
        val missing = TOKEN_REQUIRED_FIELDS.filter { data[it].asContentStringOrNull() == null }
        if (missing.isNotEmpty()) {
            throw ToyotaLoginException(
                "Token retrieval failed. Missing fields: ${missing.joinToString(", ")}",
            )
        }
        val expiresIn = requireNotNull(data["expires_in"].asContentStringOrNull()).toLongOrNull()
            ?: throw ToyotaLoginException("Token retrieval failed. expires_in is not a number")
        val uuid = JwtUuid.extractUuid(
            requireNotNull(data["id_token"].asContentStringOrNull()),
        )
        val token = TokenInfo(
            accessToken = requireNotNull(data["access_token"].asContentStringOrNull()),
            refreshToken = requireNotNull(data["refresh_token"].asContentStringOrNull()),
            uuid = uuid,
            expiration = clock().plusSeconds(expiresIn),
        )
        tokenInfo = token
        tokenStore.save(username, token)
        return token
    }

    /**
     * Authorized data request with retry on 429/5xx: backoffs (2,4,8)s,
     * max 4 attempts total; accepts 200/202.
     * pytoyoda controller.py:394-487 (hishel caching and body/params not needed here).
     */
    suspend fun request(
        method: String = "GET",
        endpoint: String,
        vin: String? = null,
    ): String {
        val token = if (currentTokenValid()) requireNotNull(tokenInfo) else updateToken()
        val headers = ToyotaHeaders.dataHeaders(
            uuid = token.uuid,
            accessToken = token.accessToken,
            brand = brand,
            vin = vin,
        )
        val url = urls.apiBase + endpoint
        for (attempt in 0..BACKOFFS_SECONDS.size) {
            val builder = Request.Builder().url(url).method(method, requestBody(method))
            headers.forEach { (name, value) -> builder.header(name, value) }
            execute(client, builder.build()).use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == HTTP_OK || response.code == HTTP_ACCEPTED) {
                    return body
                }
                val transient = response.code == HTTP_TOO_MANY_REQUESTS ||
                    response.code >= HTTP_INTERNAL_ERROR
                if (!transient || attempt >= BACKOFFS_SECONDS.size) {
                    throw ToyotaApiException(
                        statusCode = response.code,
                        message = ToyotaSecrets.sanitize(body),
                    )
                }
                if (attempt < BACKOFFS_SECONDS.size) {
                    sleeper(BACKOFFS_SECONDS[attempt])
                }
            }
        }
        throw ToyotaApiException(statusCode = -1, message = "Request failed without response")
    }

    // ---------------- HTTP plumbing helpers ----------------

    private suspend fun postJson(url: String, body: JsonObject): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return execute(client, request).use { it.code to it.body?.string().orEmpty() }
    }

    private suspend fun postFormJson(
        url: String,
        form: Map<String, String>,
        authorizationHeader: String,
    ): JsonObject {
        val formBody = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("authorization", authorizationHeader)
            .post(formBody)
            .build()
        val (status, body) = execute(client, request).use { it.code to it.body?.string().orEmpty() }
        if (status != HTTP_OK) {
            throw ToyotaLoginException(
                "Token retrieval failed. status=$status, body=${ToyotaSecrets.sanitize(body)}",
            )
        }
        return parseObject(body)
    }

    /**
     * Blocking OkHttp call moved onto intellij dispatchers so suspend callers
     * are never blocked.
     */
    private suspend fun execute(
        http: OkHttpClient,
        request: okhttp3.Request,
    ): okhttp3.Response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            http.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw ToyotaNetworkException("Network error calling Toyota API", e)
        }
    }

    private fun requestBody(method: String): okhttp3.RequestBody? =
        if (method == "GET" || method == "DELETE") null else "".toRequestBody(null)

    private fun parseObject(body: String): JsonObject =
        try {
            json.parseToJsonElement(body) as? JsonObject
                ?: throw ToyotaDataException("Expected JSON object from Toyota API")
        } catch (e: ToyotaDataException) {
            throw e
        } catch (e: Exception) {
            throw ToyotaDataException("Invalid JSON from Toyota API", e)
        }

    companion object {
        private const val MAX_AUTH_ITERATIONS = 10
        private val BACKOFFS_SECONDS = listOf(2L, 4L, 8L)

        private val TOKEN_REQUIRED_FIELDS =
            listOf("access_token", "id_token", "refresh_token", "expires_in")

        private val JSON_MEDIA = "application/json".toMediaType()
    }
}

// ---------- small helpers ----------

private const val HTTP_OK = 200
private const val HTTP_ACCEPTED = 202
private const val HTTP_FOUND = 302
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_INTERNAL_ERROR = 500

private fun JsonElement?.asContentStringOrNull(): String? =
    (this as? JsonPrimitive)?.content

/** AMI callback output value (cb["output"][i]["value"]). */
private fun JsonObject.callbackOutputValue(index: Int): String? =
    ((this["output"] as? JsonArray)?.getOrNull(index) as? JsonObject)
        ?.get("value").asContentStringOrNull()

/** AMI callback input value replacement (cb["input"][i]["value"] = value). */
private fun JsonObject.withCallbackInput(index: Int, value: String): JsonObject {
    val input = this["input"] as? JsonArray ?: return this
    val item = input.getOrNull(index) as? JsonObject ?: return this
    val replaced = JsonObject(item + ("value" to JsonPrimitive(value)))
    val newInput = JsonArray(input.mapIndexed { i, el -> if (i == index) replaced else el })
    return JsonObject(this + ("input" to newInput))
}

/**
 * Extract a query parameter from an arbitrary URI string (even a nonstandard
 * scheme like "com.toyota.oneapp:/oauth2Callback?code=x"), URL-decoding it.
 */
internal fun extractQueryParam(uri: String, name: String): String? {
    val query = uri.substringAfter('?', missingDelimiterValue = "")
    if (query.isEmpty()) {
        return null
    }
    return query.split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=')
        ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
}
