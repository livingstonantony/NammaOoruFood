package dev.livin.nammaoorufood.owner

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import dev.livin.nammaoorufood.owner.ui.CropAspect
import dev.livin.nammaoorufood.owner.ui.ImageEditorState
import dev.livin.nammaoorufood.owner.ui.cropBounds
import dev.livin.nammaoorufood.owner.ui.pixelCropBounds
import dev.livin.nammaoorufood.owner.ui.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnerImageCropTest {
    @Test
    fun rendersSelectedPixelsInsteadOfOriginalImage() {
        val image = ImageBitmap(4, 2)
        val canvas = Canvas(image)
        canvas.drawRect(Rect(0f, 0f, 2f, 2f), Paint().apply { color = Color.Red })
        canvas.drawRect(Rect(2f, 0f, 4f, 2f), Paint().apply { color = Color.Green })
        val cropped = render(image, Rect(2f, 0f, 4f, 2f), 2, 2)
        assertEquals(2, cropped.width)
        assertEquals(2, cropped.height)
        assertEquals(Color.Green, cropped.toPixelMap()[0, 0])
    }

    @Test
    fun aspectRatiosZoomAndEdgesStayWithinImage() {
        val width = 1000
        val height = 600
        for (aspect in CropAspect.entries) {
            for (zoom in listOf(1f, 2f, 8f)) {
                for (center in listOf(0f, .5f, 1f)) {
                    val bounds = cropBounds(width, height, ImageEditorState(aspect = aspect, zoom = zoom, centerX = center, centerY = center))
                    val expected = aspect.ratio ?: 1000f / 600f
                    assertTrue(kotlin.math.abs(bounds.width / bounds.height - expected) < .001f)
                    val (offset, size) = pixelCropBounds(width, height, bounds)
                    assertTrue(offset.x >= 0 && offset.y >= 0)
                    assertTrue(offset.x + size.width <= width && offset.y + size.height <= height)
                }
            }
        }
        val (offset, size) = pixelCropBounds(width, height, cropBounds(width, height, ImageEditorState(aspect = CropAspect.SQUARE)))
        assertEquals(200, offset.x)
        assertEquals(600, size.width)
        assertEquals(600, size.height)
    }
}
