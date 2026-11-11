package pl.lk.toyotacompanion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.net.Uri
import pl.lk.toyotacompanion.receipts.ReceiptOwner
import pl.lk.toyotacompanion.receipts.ReceiptRepository
import pl.lk.toyotacompanion.receipts.RECEIPT_MAX_SIDE

/**
 * Opens the system photo picker, images only: the app asks for no permission and sees only the photo the owner picks (PRD F59).
 * [onPicked] gets the content address; the repository copies the photo, the address is not kept.
 */
@Composable
fun rememberReceiptPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

private sealed interface Photo {
    data object Loading : Photo

    data object Missing : Photo

    data class Ready(val bitmap: ImageBitmap) : Photo
}

/** A photo of the app folder, decoded off the main thread; a text when the file is gone or unreadable. */
@Composable
fun ReceiptImage(name: String, repository: ReceiptRepository, maxSide: Int, modifier: Modifier = Modifier) {
    val photo by produceState<Photo>(Photo.Loading, name, maxSide) {
        value = repository.decode(name, maxSide)?.let { Photo.Ready(it.asImageBitmap()) } ?: Photo.Missing
    }
    when (val current = photo) {
        Photo.Loading -> CircularProgressIndicator(modifier = modifier)
        Photo.Missing -> Text(Texts.RECEIPT_MISSING, style = MaterialTheme.typography.bodyMedium)
        is Photo.Ready -> Image(
            bitmap = current.bitmap,
            contentDescription = Texts.RECEIPT_TITLE,
            // Fit, so a caller can cap the height (the forms do) without cropping the receipt.
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/**
 * The photo of a stored entry in a simple window inside the app; no sharing outside the app (PRD F59, docs/SECURITY.md). Scrolls for a
 * tall receipt; the window is closed with its button only.
 */
@Composable
fun ReceiptViewerDialog(owner: ReceiptOwner, id: String, repository: ReceiptRepository, onDismiss: () -> Unit) {
    val name by produceState<String?>(null, owner, id) { value = repository.nameOf(owner, id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Texts.RECEIPT_TITLE) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                name?.let { ReceiptImage(it, repository, RECEIPT_MAX_SIDE) } ?: Text(Texts.RECEIPT_MISSING)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Texts.RECEIPT_CLOSE) } },
    )
}
