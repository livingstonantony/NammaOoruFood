package dev.livin.nammaoorufood.owner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livin.nammaoorufood.customer.ui.RemoteImage
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OwnerImageControls(editor: OwnerImageEditor, enabled: Boolean) {
    val state by editor.state.collectAsStateWithLifecycle()
    val currentState by rememberUpdatedState(state)
    val picker = rememberFilePickerLauncher(
        type = FileKitType.Image,
        onError = { editor.pickerFailed() },
        onResult = { editor.select(it) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Image (optional)", style = MaterialTheme.typography.titleSmall)
        val preview = state.preview
        if (preview == null) {
            RemoteImage(state.url, "Saved image", Modifier.fillMaxWidth().height(160.dp))
        } else {
            Image(preview, contentDescription = "Selected cropped image", modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp)))
        }
        state.bytes?.let { Text("Upload size: ${it.size} bytes", style = MaterialTheme.typography.bodySmall) }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled && !state.busy, onClick = { picker.launch() }) {
                Text(if (state.preview == null && state.url.isNullOrBlank()) "Select image" else "Change image")
            }
            if (state.source != null) OutlinedButton(enabled = enabled && !state.busy, onClick = editor::edit) { Text("Edit crop") }
            if (state.preview != null || !state.url.isNullOrBlank()) OutlinedButton(enabled = enabled && !state.busy, onClick = editor::remove) { Text("Remove") }
        }
    }

    val image = state.draft
    if (image != null) {
        val rect = cropBounds(image, state)
        val ratio = rect.width / rect.height
        AlertDialog(
            onDismissRequest = { if (!state.busy) editor.cancelCrop() },
            title = { Text("Crop image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Canvas(
                        Modifier.fillMaxWidth().heightIn(max = 300.dp).aspectRatio(ratio)
                            .pointerInput(image) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    if (!currentState.busy) {
                                        editor.pan(pan.x / size.width, pan.y / size.height)
                                        editor.zoomBy(zoom)
                                    }
                                }
                            },
                    ) {
                        val (srcOffset, srcSize) = pixelCropBounds(image, rect)
                        drawImage(
                            image,
                            srcOffset = srcOffset,
                            srcSize = srcSize,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        )
                    }
                    state.selectedSize?.let { Text("Selected file: $it bytes", style = MaterialTheme.typography.bodySmall) }
                    Text("Drag to reposition; pinch or use the controls to zoom.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CropAspect.entries.forEach { aspect ->
                            FilterChip(selected = state.aspect == aspect, enabled = !state.busy, onClick = { editor.aspect(aspect) }, label = { Text(aspect.label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !state.busy, onClick = { editor.zoom(state.zoom / 1.25f) }) { Text("−") }
                        OutlinedButton(enabled = !state.busy, onClick = { editor.zoom(state.zoom * 1.25f) }) { Text("+") }
                        OutlinedButton(enabled = !state.busy, onClick = editor::reset) { Text("Reset") }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = !state.busy, onClick = editor::confirm) { Text("Apply crop") } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = editor::cancelCrop) { Text("Cancel") } },
        )
    }
}
