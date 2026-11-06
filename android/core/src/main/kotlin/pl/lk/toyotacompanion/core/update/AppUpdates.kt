package pl.lk.toyotacompanion.core.update

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The app version as "MAJOR.MINOR.PATCH" and the versionCode derived from it (docs/SPEC.md §37):
 * MAJOR * 10000 + MINOR * 100 + PATCH, MINOR and PATCH 0..99. The same formula is in app/build.gradle.kts,
 * so a release tag alone says whether it is newer than the installed app.
 */
object AppVersion {
    private val PATTERN = Regex("""v?(\d{1,3})\.(\d{1,2})\.(\d{1,2})""")

    fun codeOf(version: String): Long? {
        val match = PATTERN.matchEntire(version.trim()) ?: return null
        val (major, minor, patch) = match.destructured
        return major.toLong() * 10_000 + minor.toLong() * 100 + patch.toLong()
    }

    /** "v0.2.0" -> "0.2.0"; `null` for a tag that is not a version. */
    fun nameOf(tag: String): String? = if (codeOf(tag) == null) null else tag.trim().removePrefix("v")
}

/**
 * The newest published release with its APK.
 * @param sha256 lowercase hex digest GitHub reports for the asset; `null` when the API does not give one
 */
data class AppRelease(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
)

/** STORAGE: the phone could not write the APK (full disk, no cache dir) – retrying the network does not help. */
enum class UpdateErrorKind { NETWORK, API, DATA, STORAGE }

/** A failed check or download. SECURITY: only the kind and the HTTP status, never the response body. */
class AppUpdateException(val kind: UpdateErrorKind, message: String) : RuntimeException(message)

/** Source of app updates; [GithubReleaseClient] is the only implementation, tests use a fake. */
interface AppUpdateGateway {
    /** The latest release, or `null` when the repository has none published yet. */
    suspend fun latest(): AppRelease?

    /** Downloads the APK of [release] into [target] and checks its size and digest; [target] is deleted on failure. */
    suspend fun download(release: AppRelease, target: File, onProgress: (Long) -> Unit = {})
}

/**
 * Releases of the public repository on GitHub (docs/SPEC.md §37). Two plain requests, both only after a tap:
 * `GET /repos/{repo}/releases/latest` and the APK asset. Nothing about the owner, the car or the account is sent;
 * GitHub sees the IP address and the "toyota-companion" User-Agent. The APK is accepted only from
 * `https://github.com/{repo}/releases/download/`; GitHub redirects it to its asset host, never to plain HTTP.
 */
class GithubReleaseClient(
    private val http: OkHttpClient,
    private val repo: String = DEFAULT_REPO,
    private val apiBase: String = API_BASE,
    private val downloadBase: String = "https://github.com/$repo/releases/download/",
) : AppUpdateGateway {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latest(): AppRelease? {
        val request = Request.Builder()
            .url("$apiBase/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val (status, body) = try {
            withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.code to it.body.string() } }
        } catch (e: IOException) {
            throw AppUpdateException(UpdateErrorKind.NETWORK, "Release check failed: ${e.javaClass.simpleName}")
        }
        if (status == HTTP_NOT_FOUND) return null
        if (status !in 200..299) throw AppUpdateException(UpdateErrorKind.API, "Release check failed (HTTP $status)")
        return parse(body)
    }

    /** Pure, so the response shape can be tested on a fixture. */
    fun parse(body: String): AppRelease {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw AppUpdateException(UpdateErrorKind.DATA, "Release response is not a JSON object")
        val tag = (root["tag_name"] as? JsonPrimitive)?.content
            ?: throw AppUpdateException(UpdateErrorKind.DATA, "Release has no tag")
        val name = AppVersion.nameOf(tag) ?: throw AppUpdateException(UpdateErrorKind.DATA, "Release tag is not a version")
        val apk = (root["assets"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it["name"] as? JsonPrimitive)?.content?.endsWith(".apk") == true }
            .singleOrNull()
            ?: throw AppUpdateException(UpdateErrorKind.DATA, "Release needs exactly one APK asset")
        val url = (apk["browser_download_url"] as? JsonPrimitive)?.content
        if (url == null || !isReleaseAsset(url)) {
            throw AppUpdateException(UpdateErrorKind.DATA, "APK asset is not served from the repository releases")
        }
        val size = (apk["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..MAX_APK_BYTES }
            ?: throw AppUpdateException(UpdateErrorKind.DATA, "APK asset has no plausible size")
        val digest = (apk["digest"] as? JsonPrimitive)?.content
            ?.takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")
            ?.lowercase()
            ?.takeIf { SHA256_HEX.matches(it) }
        return AppRelease(name, requireNotNull(AppVersion.codeOf(name)), url, size, digest)
    }

    /**
     * Downloads the APK of [release] into [target], checks its size and digest and reports progress.
     *
     * Progress is reported at most once per **1% of the announced size** (not once per 64 KiB buffer: the card
     * shows whole percent, so a 200 MB APK would otherwise emit ~3200 updates for at most 100 values), plus one
     * last call with the full size. A failure of the phone's storage (no cache directory, full disk) is
     * [UpdateErrorKind.STORAGE]; a failure of the connection stays [UpdateErrorKind.NETWORK].
     */
    override suspend fun download(release: AppRelease, target: File, onProgress: (Long) -> Unit) {
        if (!isReleaseAsset(release.apkUrl)) {
            throw AppUpdateException(UpdateErrorKind.DATA, "APK asset is not served from the repository releases")
        }
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", USER_AGENT).get().build()
        try {
            withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                var reported = 0L
                val step = (release.apkSize / 100).coerceAtLeast(1L)
                http.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        throw AppUpdateException(UpdateErrorKind.API, "APK download failed (HTTP ${response.code})")
                    }
                    val output = openTarget(target)
                    try {
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                if (written > release.apkSize) {
                                    throw AppUpdateException(UpdateErrorKind.DATA, "APK is larger than announced")
                                }
                                digest.update(buffer, 0, read)
                                writeToDisk(output, buffer, read)
                                if (written - reported >= step) {
                                    reported = written
                                    onProgress(written)
                                }
                            }
                        }
                    } finally {
                        closeQuietly(output)
                    }
                }
                if (written != release.apkSize) throw AppUpdateException(UpdateErrorKind.DATA, "APK is shorter than announced")
                if (reported != written) onProgress(written)
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (release.sha256 != null && actual != release.sha256) {
                    throw AppUpdateException(UpdateErrorKind.DATA, "APK digest does not match the release")
                }
            }
        } catch (e: AppUpdateException) {
            target.delete()
            throw e
        } catch (e: IOException) {
            target.delete()
            throw AppUpdateException(UpdateErrorKind.NETWORK, "APK download failed: ${e.javaClass.simpleName}")
        }
    }

    /** Creates the cache directory and the file; a phone that cannot do it is [UpdateErrorKind.STORAGE], not a network error. */
    private fun openTarget(target: File): OutputStream = try {
        target.parentFile?.mkdirs()
        target.outputStream()
    } catch (e: IOException) {
        throw AppUpdateException(UpdateErrorKind.STORAGE, "APK file cannot be created: ${e.javaClass.simpleName}")
    }

    private fun writeToDisk(output: OutputStream, buffer: ByteArray, count: Int) {
        try {
            output.write(buffer, 0, count)
        } catch (e: IOException) {
            throw AppUpdateException(UpdateErrorKind.STORAGE, "APK file cannot be written: ${e.javaClass.simpleName}")
        }
    }

    /**
     * `FileOutputStream` is unbuffered: after [writeToDisk] every byte is already handed to the system, so a close
     * failure loses nothing and must not replace the real error (a size or digest check, or a network failure).
     */
    private fun closeQuietly(output: OutputStream) {
        try {
            output.close()
        } catch (e: IOException) {
            // Nothing is left to write; the file is complete or about to be deleted.
        }
    }

    /**
     * Compared as a parsed URL, not as text: OkHttp resolves `..` in the path, so `…/releases/download/../../x`
     * starts with [downloadBase] but points elsewhere. Same scheme, host and port, and the path under the releases.
     */
    private fun isReleaseAsset(url: String): Boolean {
        val asset = url.toHttpUrlOrNull() ?: return false
        val base = downloadBase.toHttpUrlOrNull() ?: return false
        val prefix = base.pathSegments.filter { it.isNotEmpty() }
        return asset.scheme == base.scheme && asset.host == base.host && asset.port == base.port &&
            asset.pathSegments.size > prefix.size && asset.pathSegments.take(prefix.size) == prefix
    }

    companion object {
        const val DEFAULT_REPO = "lukaszkosminski/toyota-companion"
        const val API_BASE = "https://api.github.com"
        const val USER_AGENT = "toyota-companion"
        const val MAX_APK_BYTES = 200L * 1024 * 1024

        private const val HTTP_NOT_FOUND = 404
        private const val BUFFER_BYTES = 64 * 1024
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/** Builds the client; OkHttp stays inside `core`. Redirects only HTTPS to HTTPS: GitHub sends the APK to its asset host. */
object AppUpdateGatewayFactory {
    fun create(): AppUpdateGateway = GithubReleaseClient(
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .build(),
    )
}
