package pl.lk.toyotacompanion.core.update

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

private const val REPO = "owner/app"
private val APK = ByteArray(5000) { (it % 251).toByte() }
private val APK_SHA = MessageDigest.getInstance("SHA-256").digest(APK).joinToString("") { "%02x".format(it) }

private fun releaseJson(
    tag: String = "v0.2.0",
    url: String,
    size: Long = APK.size.toLong(),
    digest: String? = "sha256:$APK_SHA",
    extraAsset: String = "",
) = """
{"tag_name":"$tag","draft":false,"prerelease":false,"assets":[
  {"name":"toyota-companion-0.2.0.apk","size":$size,"browser_download_url":"$url"${if (digest == null) "" else ""","digest":"$digest""""}}
  $extraAsset
]}"""

class GithubReleaseClientTest {

    private fun client(server: MockWebServer) = GithubReleaseClient(
        OkHttpClient(),
        repo = REPO,
        apiBase = server.url("/api").toString().trimEnd('/'),
        downloadBase = server.url("/dl/").toString(),
    )

    private fun tempApk(): File = Files.createTempDirectory("update").resolve("update.apk").toFile()

    @Test
    fun `version code is major minor patch`() {
        assertEquals(100L, AppVersion.codeOf("0.1.0"))
        assertEquals(10_203L, AppVersion.codeOf("v1.2.3"))
        assertEquals(9_999L, AppVersion.codeOf("0.99.99"))
        assertNull(AppVersion.codeOf("0.100.0"))
        assertNull(AppVersion.codeOf("1.2"))
        assertNull(AppVersion.codeOf("v1.2.3-beta"))
        assertEquals("1.2.3", AppVersion.nameOf("v1.2.3"))
    }

    @Test
    fun `latest release is read with its apk and digest`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val url = server.url("/dl/v0.2.0/toyota-companion-0.2.0.apk").toString()
            server.enqueue(MockResponse.Builder().code(200).body(releaseJson(url = url)).build())

            val release = requireNotNull(client(server).latest())

            assertEquals(AppRelease("0.2.0", 200L, url, APK.size.toLong(), APK_SHA), release)
            assertEquals(1, server.requestCount, "a found release is one request")
            val request = server.takeRequest()
            assertEquals("/api/repos/$REPO/releases/latest", request.url.encodedPath)
            assertEquals("toyota-companion", request.headers["User-Agent"])
        }
    }

    @Test
    fun `404 on the release list asks the repository once and means no release`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())
            server.enqueue(MockResponse.Builder().code(200).body("""{"full_name":"$REPO"}""").build())

            assertNull(client(server).latest())

            assertEquals(2, server.requestCount)
            assertEquals("/api/repos/$REPO/releases/latest", server.takeRequest().url.encodedPath)
            val repository = server.takeRequest()
            assertEquals("/api/repos/$REPO", repository.url.encodedPath)
            assertEquals("toyota-companion", repository.headers["User-Agent"])
            assertEquals("application/vnd.github+json", repository.headers["Accept"])
        }
    }

    @Test
    fun `404 on the release list and on the repository is a missing repository`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())
            server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())

            val e = assertFailsWith<AppUpdateException> { client(server).latest() }

            assertEquals(UpdateErrorKind.NO_REPOSITORY, e.kind)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `404 on the release list and a failing repository check is an api error`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())
            server.enqueue(MockResponse.Builder().code(500).body("boom").build())

            val e = assertFailsWith<AppUpdateException> { client(server).latest() }

            assertEquals(UpdateErrorKind.API, e.kind)
        }
    }

    @Test
    fun `rate limit is an api error without the body`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(403).body("SECRET-BODY").build())
            val e = assertFailsWith<AppUpdateException> { client(server).latest() }
            assertEquals(UpdateErrorKind.API, e.kind)
            assertFalse(e.message!!.contains("SECRET-BODY"))
        }
    }

    /** Answers the first call with 404 and the second with [second] (or drops it when [second] is null). */
    private fun scripted(second: Int?): OkHttpClient {
        var calls = 0
        return OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            when {
                calls == 1 -> response(chain, 404)
                second == null -> throw IOException("connection reset")
                else -> response(chain, second)
            }
        }.build()
    }

    private fun response(chain: Interceptor.Chain, code: Int): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("scripted")
        .body("{}".toResponseBody(null))
        .build()

    @Test
    fun `404 on the release list and a dropped repository check is a network error`() = runBlocking {
        val e = assertFailsWith<AppUpdateException> { GithubReleaseClient(scripted(null), repo = REPO).latest() }

        assertEquals(UpdateErrorKind.NETWORK, e.kind)
    }

    @Test
    fun `404 on the release list and a rate limit on the repository check is an api error`() = runBlocking {
        val e = assertFailsWith<AppUpdateException> { GithubReleaseClient(scripted(403), repo = REPO).latest() }

        assertEquals(UpdateErrorKind.API, e.kind)
    }

    @Test
    fun `apk outside the repository releases is rejected`() {
        MockWebServer().apply { start() }.use { server ->
            val e = assertFailsWith<AppUpdateException> {
                client(server).parse(releaseJson(url = "https://evil.example/app.apk"))
            }
            assertEquals(UpdateErrorKind.DATA, e.kind)
            // Starts with the releases prefix as text, but OkHttp resolves ".." to another path of the same host.
            val dotDot = server.url("/dl/").toString() + "../other/app.apk"
            assertEquals(UpdateErrorKind.DATA, assertFailsWith<AppUpdateException> { client(server).parse(releaseJson(url = dotDot)) }.kind)
        }
    }

    @Test
    fun `tag that is not a version, no apk or two apks are data errors`() {
        MockWebServer().apply { start() }.use { server ->
            val url = server.url("/dl/x.apk").toString()
            val c = client(server)
            assertEquals(UpdateErrorKind.DATA, assertFailsWith<AppUpdateException> { c.parse(releaseJson("nightly", url)) }.kind)
            assertEquals(UpdateErrorKind.DATA, assertFailsWith<AppUpdateException> { c.parse("""{"tag_name":"v1.0.0","assets":[]}""") }.kind)
            val second = ""","{"name":"b.apk","size":1,"browser_download_url":"$url"}"""
            assertEquals(UpdateErrorKind.DATA, assertFailsWith<AppUpdateException> { c.parse(releaseJson(url = url, extraAsset = second)) }.kind)
            assertEquals(UpdateErrorKind.DATA, assertFailsWith<AppUpdateException> { c.parse("not json") }.kind)
        }
    }

    @Test
    fun `missing digest is accepted as null`() {
        MockWebServer().apply { start() }.use { server ->
            val url = server.url("/dl/x.apk").toString()
            assertNull(client(server).parse(releaseJson(url = url, digest = null)).sha256)
        }
    }

    @Test
    fun `download writes the apk and checks the digest`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val url = server.url("/dl/v0.2.0/a.apk").toString()
            server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(APK)).build())
            val target = tempApk()
            var progress = 0L

            client(server).download(AppRelease("0.2.0", 200, url, APK.size.toLong(), APK_SHA), target) { progress = it }

            assertTrue(target.readBytes().contentEquals(APK))
            assertEquals(APK.size.toLong(), progress)
        }
    }

    @Test
    fun `wrong digest deletes the file`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val url = server.url("/dl/v0.2.0/a.apk").toString()
            server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(APK)).build())
            val target = tempApk()

            val e = assertFailsWith<AppUpdateException> {
                client(server).download(AppRelease("0.2.0", 200, url, APK.size.toLong(), "0".repeat(64)), target)
            }

            assertEquals(UpdateErrorKind.DATA, e.kind)
            assertFalse(target.exists())
        }
    }

    @Test
    fun `size other than announced deletes the file`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val url = server.url("/dl/v0.2.0/a.apk").toString()
            server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(APK)).build())
            server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(APK)).build())
            val longer = tempApk()
            val shorter = tempApk()

            assertFailsWith<AppUpdateException> {
                client(server).download(AppRelease("0.2.0", 200, url, APK.size + 1L, null), longer)
            }
            assertFailsWith<AppUpdateException> {
                client(server).download(AppRelease("0.2.0", 200, url, APK.size - 1L, null), shorter)
            }

            assertFalse(longer.exists())
            assertFalse(shorter.exists())
        }
    }

    @Test
    fun `download failure status is an api error`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val url = server.url("/dl/v0.2.0/a.apk").toString()
            server.enqueue(MockResponse.Builder().code(500).body("boom").build())
            val e = assertFailsWith<AppUpdateException> {
                client(server).download(AppRelease("0.2.0", 200, url, APK.size.toLong(), null), tempApk())
            }
            assertEquals(UpdateErrorKind.API, e.kind)
        }
    }

    @Test
    fun `a target that cannot be created is a storage error`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val url = server.url("/dl/v0.2.0/a.apk").toString()
            server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(APK)).build())
            // The parent of the target is an ordinary file, so the cache directory cannot be created and
            // FileOutputStream fails: that is the phone's storage, not the connection.
            val blocker = Files.createTempDirectory("update").resolve("blocked").toFile()
            blocker.writeText("not a directory")
            val target = File(blocker, "update.apk")

            val e = assertFailsWith<AppUpdateException> {
                client(server).download(AppRelease("0.2.0", 200, url, APK.size.toLong(), APK_SHA), target)
            }

            assertEquals(UpdateErrorKind.STORAGE, e.kind)
            assertFalse(target.exists())
        }
    }

    @Test
    fun `progress is reported about once per percent, with the full size at the end`() = runBlocking {
        val big = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        MockWebServer().apply { start() }.use { server ->
            val url = server.url("/dl/v0.2.0/big.apk").toString()
            server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(big)).build())
            val target = tempApk()
            val reported = mutableListOf<Long>()

            client(server).download(AppRelease("0.2.0", 200, url, big.size.toLong(), null), target) { reported += it }

            assertEquals(big.size.toLong(), target.length())
            assertEquals(big.size.toLong(), reported.last(), "the last report is the whole file")
            // One call per 1% of the announced size: at most 100 + the final one, not one per 64 KiB buffer (~128).
            assertTrue(reported.size <= 101, "reported ${reported.size} times")
            assertTrue(reported.size < big.size / 65_536, "still one call per buffer")
            assertEquals(reported.sorted(), reported, "progress never goes back")
        }
    }
}
