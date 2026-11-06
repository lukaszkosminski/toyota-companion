package pl.lk.toyotacompanion.core.update

import java.io.File
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
import okhttp3.OkHttpClient

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
            val request = server.takeRequest()
            assertEquals("/api/repos/$REPO/releases/latest", request.url.encodedPath)
            assertEquals("toyota-companion", request.headers["User-Agent"])
        }
    }

    @Test
    fun `no release yet is not an error`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())
            assertNull(client(server).latest())
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
}
