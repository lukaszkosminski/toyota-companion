package pl.lk.toyotacompanion.receipts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The pictures of the app folder: size, metadata and failures (PRD F59, docs/SPEC.md §59). */
@RunWith(RobolectricTestRunner::class)
// Real decoding and encoding: the default graphics shadow accepts any bytes as a picture, so the checks below would prove nothing.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var folder: File
    private lateinit var store: ReceiptStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        folder = File(tmp.root, "receipts")
        store = ReceiptStore(folder, context.contentResolver)
    }

    /** A JPEG from the camera: the same picture with a fake EXIF block (GPS text) right after the start marker. */
    private fun cameraPhoto(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val plain = File(tmp.root, "plain-$width-$height.jpg")
        plain.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val bytes = plain.readBytes()
        val payload = "Exif\u0000\u0000GPS-TEST-LOCATION".toByteArray(Charsets.US_ASCII)
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        val camera = File(tmp.root, "camera-$width-$height.jpg")
        camera.writeBytes(bytes.copyOfRange(0, 2) + segment + bytes.copyOfRange(2, bytes.size))
        return camera
    }

    private fun uri(file: File): Uri = Uri.fromFile(file)

    @Test
    fun `an imported photo is a jpeg under a receipt name, at most the limit, with its metadata removed`() {
        val source = cameraPhoto(3000, 2000)
        // Sanity: the source really carries the block, so the test below proves the removal and not an absent block.
        assertTrue(String(source.readBytes(), Charsets.ISO_8859_1).contains("GPS-TEST-LOCATION"))

        val name = store.importPhoto(uri(source))

        assertTrue(isReceiptFileName(name))
        val stored = assertNotNull(store.file(name))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(stored.path, bounds)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= RECEIPT_MAX_SIDE, "${bounds.outWidth}x${bounds.outHeight}")
        val bytes = stored.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("GPS-TEST-LOCATION" in text, "the GPS block is gone")
        assertFalse("Exif" in text, "no EXIF block at all")
    }

    @Test
    fun `a picture is scaled down to the limit before it is written`() {
        val name = store.importPhoto(uri(cameraPhoto(4000, 3000)))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(assertNotNull(store.file(name)).path, bounds)
        assertEquals(1600 to 1200, bounds.outWidth to bounds.outHeight)
    }

    @Test
    fun `a file that is not a picture is refused and leaves nothing in the folder`() {
        val notes = File(tmp.root, "notes.txt").apply { writeText("not a picture") }
        assertFailsWith<ReceiptException> { store.importPhoto(uri(notes)) }
        assertTrue(store.files().isEmpty())
    }

    @Test
    fun `a photo for the screen is decoded smaller and a missing one reads as none`() {
        val name = store.importPhoto(uri(cameraPhoto(1200, 800)))
        val small = assertNotNull(store.decode(name, maxSide = 320))
        // The sample size keeps the side at least the limit and below twice the limit (1200 px, limit 320: 600 px).
        assertTrue(maxOf(small.width, small.height) in 320..640, "${small.width}x${small.height}")
        assertNull(store.decode("2b1e6c3a-1111-4222-8333-944455556666.jpg", maxSide = 320))
        assertNull(store.decode("../etc/passwd", maxSide = 320))
    }

    /** A plain JPEG without the fake block, for the orientation test: ExifInterface must be able to rewrite its EXIF. */
    private fun plainPhoto(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val file = File(tmp.root, "plain-$width-$height-rot.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return file
    }

    @Test
    fun `the camera orientation becomes a clockwise turn`() {
        // EXIF orientation: 6 = rotate 90, 3 = 180, 8 = 270, 1 = as is, 2 = mirrored (no turn).
        assertEquals(90, ReceiptStore.rotationDegreesFor(6))
        assertEquals(180, ReceiptStore.rotationDegreesFor(3))
        assertEquals(270, ReceiptStore.rotationDegreesFor(8))
        assertEquals(0, ReceiptStore.rotationDegreesFor(1))
        assertEquals(0, ReceiptStore.rotationDegreesFor(2))
        assertEquals(0, ReceiptStore.rotationDegreesFor(0))
    }

    @Test
    fun `a photo the camera marked as turned comes out turned, not sideways`() {
        val source = plainPhoto(4000, 3000)
        android.media.ExifInterface(source.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6")
            saveAttributes()
        }
        val name = store.importPhoto(uri(source))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(assertNotNull(store.file(name)).path, bounds)
        // 4000x3000 scaled to 1600x1200, then turned 90 degrees: the sides swap.
        assertEquals(1200 to 1600, bounds.outWidth to bounds.outHeight)
    }

    @Test
    fun `a delete removes only a receipt file`() {
        val name = store.importPhoto(uri(cameraPhoto(800, 600)))
        store.delete("../outside.jpg")
        store.delete(name)
        assertFalse(assertNotNull(store.file(name)).exists())
    }
}
