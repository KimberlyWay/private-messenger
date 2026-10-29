package uk.mywisp.privatechat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
internal fun ZoomablePhoto(bitmap: Bitmap, modifier: Modifier = Modifier.fillMaxWidth().height(440.dp)) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(modifier.clipToBounds()
        .pointerInput(bitmap) {
            detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
        }
        .pointerInput(bitmap) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                val limitX = size.width * (scale - 1f) / 2f
                val limitY = size.height * (scale - 1f) / 2f
                offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX),
                    (offset.y + pan.y).coerceIn(-limitY, limitY))
            }
        }) {
        Image(image, "Фото: увеличение двумя пальцами или двойным нажатием", Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
        })
    }
}
