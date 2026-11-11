package pl.lk.toyotacompanion.receipts

import kotlin.math.max

/** Longest side of a stored receipt photo, in pixels (PRD F59, docs/SPEC.md §59). */
const val RECEIPT_MAX_SIDE = 1600

/** The JPEG quality of a stored receipt; the re-encode also drops the metadata (EXIF with GPS) of the camera. */
const val RECEIPT_JPEG_QUALITY = 80

private val RECEIPT_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")

/** A name the app gives a receipt photo: a random UUID and `.jpg`. Anything else in the folder is not a receipt. */
fun isReceiptFileName(name: String): Boolean = RECEIPT_NAME.matches(name)

/** The power of two to decode a photo with, so the decoded side stays at least [maxSide] (`inSampleSize`, no upscaling). */
fun sampleSize(width: Int, height: Int, maxSide: Int = RECEIPT_MAX_SIDE): Int {
    var size = 1
    while (max(width, height) / (size * 2) >= maxSide) size *= 2
    return size
}

/** The size of the stored photo: the longer side at most [maxSide], the ratio kept, never larger than the source. */
fun scaledSize(width: Int, height: Int, maxSide: Int = RECEIPT_MAX_SIDE): Pair<Int, Int> {
    val longer = max(width, height)
    if (longer <= maxSide) return width to height
    val scale = maxSide.toDouble() / longer
    return max(1, Math.round(width * scale).toInt()) to max(1, Math.round(height * scale).toInt())
}

/** Files in the folder that no entry points to: left after a crash or a cancelled dialog. */
fun orphanedReceipts(files: Set<String>, referenced: Set<String>): Set<String> =
    files.filter { isReceiptFileName(it) && it !in referenced }.toSet()

/** Names an entry points to but the folder does not hold (a restore from another phone, a deleted folder): the link is cleared. */
fun missingReceipts(referenced: Set<String>, files: Set<String>): Set<String> = referenced.filter { it !in files }.toSet()
