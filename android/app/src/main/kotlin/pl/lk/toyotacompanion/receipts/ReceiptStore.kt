package pl.lk.toyotacompanion.receipts

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import java.io.File
import java.util.UUID

/** A photo that could not be read or stored; the message never contains the address of the file (docs/SECURITY.md). */
class ReceiptException(message: String) : Exception(message)

/**
 * The receipt photos in `filesDir/receipts` (PRD F59, docs/SPEC.md §59). Private to the app: not in Auto Backup, not in the archive,
 * not in the CSV. Blocking I/O, so the callers run it on a background dispatcher.
 */
class ReceiptStore(private val dir: File, private val resolver: ContentResolver) {
    /** Every file in the folder, the temporary ones of an interrupted import included. */
    fun files(): Set<String> = dir.list()?.toSet() ?: emptySet()

    /** The file of a receipt name, or `null` for a name the app did not give. */
    fun file(name: String): File? = if (isReceiptFileName(name)) File(dir, name) else null

    /**
     * Decodes [source] with a sample size, scales it to [RECEIPT_MAX_SIDE] and writes a JPEG without metadata under a new random name.
     * Returns the name; on any failure nothing is left in the folder and the message does not name the file.
     */
    fun importPhoto(source: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ReceiptException("not an image")

        val decode = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, decode) }
            ?: throw ReceiptException("unreadable")
        val (width, height) = scaledSize(decoded.width, decoded.height)
        val scaled = if (width == decoded.width && height == decoded.height) {
            decoded
        } else {
            decoded.scale(width, height).also { decoded.recycle() }
        }
        // The metadata is dropped with the re-encoding, so the rotation a camera only wrote into EXIF is applied to the pixels first.
        val degrees = rotationDegrees(source)
        val bitmap = if (degrees == 0) {
            scaled
        } else {
            val turn = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, turn, true).also { if (it !== scaled) scaled.recycle() }
        }

        dir.mkdirs()
        val temp = File(dir, "$TEMP_PREFIX${UUID.randomUUID()}")
        val name = "${UUID.randomUUID()}.jpg"
        try {
            val written = temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, RECEIPT_JPEG_QUALITY, it) }
            if (!written) throw ReceiptException("not stored")
            if (!temp.renameTo(File(dir, name))) throw ReceiptException("not stored")
        } catch (e: java.io.IOException) {
            throw ReceiptException("not stored")
        } finally {
            bitmap.recycle()
            temp.delete()
        }
        return name
    }

    /** The clockwise rotation the camera stored in EXIF (0, 90, 180 or 270); 0 when there is none or it cannot be read. */
    // The platform class is enough here: minSdk is 29, only the orientation tag is read, and the project adds no dependency for it.
    @SuppressLint("ExifInterface")
    private fun rotationDegrees(source: Uri): Int = try {
        resolver.openInputStream(source)?.use { stream ->
            rotationDegreesFor(
                android.media.ExifInterface(stream).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                ),
            )
        } ?: 0
    } catch (e: java.io.IOException) {
        0
    }

    /** A photo for the screen, decoded with a sample size for [maxSide]; `null` when the file is missing or unreadable. */
    fun decode(name: String, maxSide: Int): Bitmap? {
        val file = file(name) ?: return null
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        return BitmapFactory.decodeFile(file.path, options)
    }

    fun delete(name: String) {
        file(name)?.delete()
    }

    fun deleteTemporary(name: String) {
        if (name.startsWith(TEMP_PREFIX)) File(dir, name).delete()
    }

    companion object {
        /**
         * The clockwise rotation for an EXIF orientation value, the camera's way of saying "turn the pixels": 6 is 90°, 3 is 180°,
         * 8 is 270°; anything else, mirrored values included, means no turn. Pure, so the rule is tested without a picture (C41).
         */
        internal fun rotationDegreesFor(orientation: Int): Int = when (orientation) {
            6 -> 90
            3 -> 180
            8 -> 270
            else -> 0
        }


        const val TEMP_PREFIX = "tmp-"
    }
}
