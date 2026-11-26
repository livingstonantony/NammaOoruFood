package dev.livin.nammaoorufood.owner.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.owner.data.ImageKitUploadRepository
import dev.livin.nammaoorufood.owner.data.ImageUploadFolder
import dev.livin.nammaoorufood.owner.data.MAX_IMAGE_UPLOAD_BYTES
import io.github.vinceglb.filekit.ImageFormat
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.util.encodeToByteArray
import io.github.vinceglb.filekit.dialogs.compose.util.toImageBitmap
import io.github.vinceglb.filekit.readBytes
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class CropAspect(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null), SQUARE("1:1", 1f), FOUR_THREE("4:3", 4f / 3), WIDE("16:9", 16f / 9),
}

data class ImageEditorState(
    val source: ImageBitmap? = null,
    val draft: ImageBitmap? = null,
    val preview: ImageBitmap? = null,
    val bytes: ByteArray? = null,
    val contentType: ContentType? = null,
    val selectedSize: Int? = null,
    val url: String? = null,
    val aspect: CropAspect = CropAspect.SQUARE,
    val zoom: Float = 1f,
    val centerX: Float = .5f,
    val centerY: Float = .5f,
    val busy: Boolean = false,
    val message: String? = null,
)

/** Source-image coordinates represented by the displayed crop area. */
internal fun cropBounds(image: ImageBitmap, state: ImageEditorState): Rect = cropBounds(image.width, image.height, state)

internal fun cropBounds(imageWidth: Int, imageHeight: Int, state: ImageEditorState): Rect {
    val width = imageWidth.toFloat()
    val height = imageHeight.toFloat()
    val ratio = state.aspect.ratio ?: width / height
    val cropWidth = min(width, height * ratio) / state.zoom.coerceIn(1f, 8f)
    val cropHeight = cropWidth / ratio
    val x = (state.centerX * width - cropWidth / 2).coerceIn(0f, width - cropWidth)
    val y = (state.centerY * height - cropHeight / 2).coerceIn(0f, height - cropHeight)
    return Rect(x, y, x + cropWidth, y + cropHeight)
}

internal fun pixelCropBounds(image: ImageBitmap, rect: Rect): Pair<IntOffset, IntSize> = pixelCropBounds(image.width, image.height, rect)

internal fun pixelCropBounds(width: Int, height: Int, rect: Rect): Pair<IntOffset, IntSize> {
    val left = rect.left.roundToInt().coerceIn(0, width - 1)
    val top = rect.top.roundToInt().coerceIn(0, height - 1)
    val right = rect.right.roundToInt().coerceIn(left + 1, width)
    val bottom = rect.bottom.roundToInt().coerceIn(top + 1, height)
    return IntOffset(left, top) to IntSize(right - left, bottom - top)
}

class OwnerImageEditor(
    private val uploader: ImageKitUploadRepository,
    private val scope: CoroutineScope,
    private val folder: ImageUploadFolder,
) {
    private val defaultAspect = if (folder == ImageUploadFolder.HOTEL) CropAspect.WIDE else CropAspect.SQUARE
    private val mutable = MutableStateFlow(ImageEditorState(aspect = defaultAspect))
    private var processing: Job? = null
    val state = mutable.asStateFlow()

    fun initialize(url: String?) {
        processing?.cancel()
        mutable.value = ImageEditorState(url = url, aspect = defaultAspect)
    }

    fun pickerFailed() {
        mutable.update { it.copy(message = "Could not open the image picker. Please try again.") }
    }

    fun select(file: PlatformFile?) {
        if (file == null || mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        processing = scope.launch {
            try {
                val bytes = file.readBytes()
                val isJpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
                val isPng = bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
                val isWebp = bytes.size >= 12 && bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WEBP"
                require(isJpeg || isPng || isWebp) { "Choose a JPEG, PNG or WebP image." }
                val image = file.toImageBitmap()
                require(image.width > 0 && image.height > 0) { "Could not decode this image." }
                mutable.update {
                    it.copy(
                        draft = image, selectedSize = bytes.size, aspect = defaultAspect,
                        zoom = 1f, centerX = .5f, centerY = .5f, busy = false,
                        message = if (bytes.size > MAX_IMAGE_UPLOAD_BYTES) {
                            "Selected image exceeds 2 MB. After cropping, it will be compressed if needed to fit the limit."
                        } else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, message = e.message ?: "Could not open this image.") }
            }
        }
    }

    fun edit() = mutable.update { it.copy(draft = it.source, message = null) }
    fun cancelCrop() = mutable.update { it.copy(draft = null, busy = false) }
    fun remove() {
        processing?.cancel()
        mutable.value = ImageEditorState(aspect = defaultAspect)
    }
    fun aspect(value: CropAspect) = mutable.update { it.copy(aspect = value, zoom = 1f, centerX = .5f, centerY = .5f) }
    fun reset() = mutable.update { it.copy(zoom = 1f, centerX = .5f, centerY = .5f) }
    fun zoom(value: Float) = mutable.update { it.copy(zoom = value.coerceIn(1f, 8f)) }
    fun zoomBy(factor: Float) = mutable.update { it.copy(zoom = (it.zoom * factor).coerceIn(1f, 8f)) }

    fun pan(dx: Float, dy: Float) {
        mutable.update { s ->
            val image = s.draft ?: return@update s
            val bounds = cropBounds(image, s)
            s.copy(
                centerX = (bounds.center.x / image.width - dx * bounds.width / image.width).coerceIn(0f, 1f),
                centerY = (bounds.center.y / image.height - dy * bounds.height / image.height).coerceIn(0f, 1f),
            )
        }
    }

    fun confirm() {
        val snapshot = mutable.value
        val source = snapshot.draft ?: return
        if (snapshot.busy) return
        mutable.update { it.copy(busy = true, message = "Preparing cropped image…") }
        processing = scope.launch {
            try {
                val rect = cropBounds(source, snapshot)
                val (_, cropSize) = pixelCropBounds(source, rect)
                val cropped = withContext(Dispatchers.Default) { render(source, rect, cropSize.width, cropSize.height) }
                var result = cropped.encodeToByteArray(ImageFormat.PNG)
                var type = ContentType.Image.PNG
                require(result.isNotEmpty()) { "Could not encode the cropped image." }
                val needsCompression = result.size > MAX_IMAGE_UPLOAD_BYTES
                if (needsCompression) {
                    mutable.update { it.copy(message = "Cropped image exceeds 2 MB and will be compressed.") }
                    yield()
                    var scaled = cropped
                    var best: ByteArray? = null
                    repeat(8) {
                        if (best == null) {
                            var low = 20
                            var high = 100
                            while (low <= high) {
                                val quality = (low + high) / 2
                                val encoded = scaled.encodeToByteArray(ImageFormat.JPEG, quality)
                                require(encoded.isNotEmpty()) { "Could not compress the image." }
                                if (encoded.size <= MAX_IMAGE_UPLOAD_BYTES) {
                                    best = encoded
                                    low = quality + 1
                                } else high = quality - 1
                            }
                            if (best == null) {
                                val w = max(1, (scaled.width * .8f).roundToInt())
                                val h = max(1, (scaled.height * .8f).roundToInt())
                                scaled = withContext(Dispatchers.Default) {
                                    render(scaled, Rect(0f, 0f, scaled.width.toFloat(), scaled.height.toFloat()), w, h)
                                }
                            }
                        }
                    }
                    result = best ?: error("The image could not be compressed below 2 MB.")
                    type = ContentType.Image.JPEG
                }
                check(result.size <= MAX_IMAGE_UPLOAD_BYTES)
                mutable.update {
                    it.copy(source = source, draft = null, preview = cropped, bytes = result, contentType = type,
                        url = null, busy = false, message = if (needsCompression) {
                            "Image compressed to ${result.size} bytes (maximum 2 MB)."
                        } else {
                            "Image ready (${result.size} bytes)."
                        })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, message = e.message ?: "Could not crop the image.") }
            }
        }
    }

    suspend fun resolveUrl(): ApiResult<String?> {
        val snapshot = mutable.value
        if (snapshot.busy || snapshot.draft != null) return ApiResult.Rejected(0, "Finish editing the image first.", false)
        if (snapshot.url != null || snapshot.bytes == null) return ApiResult.Success(snapshot.url)
        val type = snapshot.contentType ?: return ApiResult.Rejected(0, "Image format is missing.", false)
        mutable.update { it.copy(busy = true, message = "Uploading image…") }
        return try {
            when (val result = uploader.upload(snapshot.bytes, type, folder)) {
                is ApiResult.Success -> {
                    mutable.update { it.copy(url = result.value, busy = false, message = "Image uploaded.") }
                    ApiResult.Success(result.value)
                }
                is ApiResult.Rejected -> {
                    mutable.update { it.copy(busy = false, message = result.message) }
                    result
                }
                is ApiResult.NetworkError -> {
                    mutable.update { it.copy(busy = false, message = result.message) }
                    result
                }
            }
        } catch (e: CancellationException) {
            mutable.update { it.copy(busy = false) }
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(busy = false, message = "Image upload failed. Please try again.") }
            return ApiResult.NetworkError("Image upload failed. Please try again.")
        }
    }
}

internal fun render(source: ImageBitmap, rect: Rect, width: Int, height: Int): ImageBitmap {
    val output = ImageBitmap(width, height)
    val (offset, size) = pixelCropBounds(source, rect)
    Canvas(output).drawImageRect(
        source,
        offset,
        size,
        IntOffset.Zero,
        IntSize(width, height),
        Paint(),
    )
    return output
}
