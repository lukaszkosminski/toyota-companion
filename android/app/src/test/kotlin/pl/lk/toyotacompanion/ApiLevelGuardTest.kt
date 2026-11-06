package pl.lk.toyotacompanion

import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.sync.SyncEngine
import pl.lk.toyotacompanion.ui.WeatherController

/**
 * The app runs on API 29+ (`minSdk`) without core-library desugaring, so a `java.time` method added in
 * Android 14 (API 34) is a `NoSuchMethodError` on Android 10–13. That is an `Error`, not an `Exception`,
 * so every `catch (Exception)` in the code lets it through: the synchronization would fail silently and
 * the weather screen would stay on "loading" (docs/SPEC.md §8, §23).
 *
 * Robolectric cannot catch this: it runs `java.time` from the JDK, not from the Android image, so
 * `@Config(sdk = [29])` happily accepts `LocalDate.ofInstant`. The compiled classes are therefore checked
 * directly — a class file names every method it calls in its constant pool, and no class of `core` or the
 * app may name one of the API 34 additions.
 */
class ApiLevelGuardTest {
    /** `java.time` methods added in Android 14 (API 34); the project deliberately uses none of them. */
    private val postApi29 = listOf("ofInstant", "toSecondsPart", "toMillisPart", "toNanosPart")

    @Test
    fun `no class of core or the app calls a java_time method newer than API 29`() {
        val roots = listOf(SyncEngine::class.java, WeatherController::class.java).map(::codeLocation).distinct()
        var scanned = 0
        for (root in roots) {
            for ((name, bytes) in classFiles(root)) {
                scanned++
                val constantPool = String(bytes, Charsets.ISO_8859_1)
                for (method in postApi29) {
                    assertFalse(
                        constantPool.contains(method),
                        "$name calls java.time.$method, which needs Android API 34 (minSdk 29, no desugaring)",
                    )
                }
            }
        }
        assertTrue(scanned > 100, "expected the whole core and app output, scanned $scanned classes")
    }

    /** The directory or jar a class was loaded from; a project dependency is usually a jar, a module a directory. */
    private fun codeLocation(cls: Class<*>): File =
        File(requireNotNull(cls.protectionDomain?.codeSource?.location) { "${cls.name} has no code source" }.toURI())

    /** Every `*.class` under [root], name and bytes, in a stable order. */
    private fun classFiles(root: File): Sequence<Pair<String, ByteArray>> =
        if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .sortedBy { it.path }
                .map { it.relativeTo(root).path to it.readBytes() }
        } else {
            JarFile(root).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .sortedBy { it.name }
                    .map { it.name to jar.getInputStream(it).use { stream -> stream.readBytes() } }
                    .toList()
            }.asSequence()
        }
}
