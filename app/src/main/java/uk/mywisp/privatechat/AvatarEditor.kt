package uk.mywisp.privatechat

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AvatarEditor(uri: Uri, close: () -> Unit, save: (ByteArray) -> Unit, shape: String = "circle") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var x by remember(uri) { mutableFloatStateOf(0f) }
    var y by remember(uri) { mutableFloatStateOf(0f) }
    var saving by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        try { bitmap = withContext(Dispatchers.IO) { loadAvatarBitmap(context, uri) } }
        catch (_: Exception) { error = "Не удалось открыть фото. Выберите изображение до 20 МБ." }
    }
    Dialog(onDismissRequest = { if (!saving) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Фото профиля", style = MaterialTheme.typography.headlineMedium)
                Text("Приближайте двумя пальцами и двигайте фото. Рамка показывает выбранную форму аватара.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val image = bitmap
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (image == null) {
                        if (error == null) CircularProgressIndicator() else Text(error!!)
                    } else {
                        val picture = remember(image) { image.asImageBitmap() }
                        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black)
                            .semantics { contentDescription = "Область обрезки аватара" }
                            .pointerInput(image, saving) {
                                if (!saving) detectTransformGestures { _, pan, scale, _ ->
                                    zoom = (zoom * scale).coerceIn(1f, 5f)
                                    val crop = avatarCrop(image.width, image.height, zoom, x, y)
                                    val pixelsPerScreenPixel = crop.side / size.width
                                    val travelX = (image.width - crop.side) / 2f
                                    val travelY = (image.height - crop.side) / 2f
                                    x = if (travelX > 0) (x - pan.x * pixelsPerScreenPixel / travelX).coerceIn(-1f, 1f) else 0f
                                    y = if (travelY > 0) (y - pan.y * pixelsPerScreenPixel / travelY).coerceIn(-1f, 1f) else 0f
                                }
                            }) {
                            val crop = avatarCrop(image.width, image.height, zoom, x, y)
                            val scale = size.width / crop.side
                            drawContext.canvas.save()
                            drawContext.canvas.translate(-crop.left * scale, -crop.top * scale)
                            drawContext.canvas.scale(scale, scale)
                            drawImage(picture)
                            drawContext.canvas.restore()
                            val outline = leanAvatarShape(shape).createOutline(size, layoutDirection, this)
                            val border = when (outline) {
                                is Outline.Generic -> outline.path
                                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                            }
                            val mask = Path().apply {
                                fillType = PathFillType.EvenOdd
                                addRect(Rect(Offset.Zero, size))
                                addPath(border)
                            }
                            drawPath(mask, Color.Black.copy(alpha = .65f))
                            drawPath(border, Color.White,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        }
                    }
                }
                Text("Масштаб", style = MaterialTheme.typography.labelLarge)
                Slider(modifier = Modifier.semantics { contentDescription = "Масштаб аватара" }, value = zoom, onValueChange = { zoom = it }, valueRange = 1f..5f, enabled = image != null && !saving)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = close, enabled = !saving) { Text("Отмена") }
                    TextButton(onClick = { zoom = 1f; x = 0f; y = 0f }, enabled = image != null && !saving) { Text("Сбросить") }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = image != null && !saving, onClick = {
                        val source = image ?: return@Button
                        saving = true
                        val crop = avatarCrop(source.width, source.height, zoom, x, y)
                        scope.launch {
                            try { val jpeg = withContext(Dispatchers.Default) { cropAvatarJpeg(source, crop) }; save(jpeg); close() }
                            catch (_: Exception) { error = "Не удалось подготовить фото"; saving = false }
                        }
                    }) { Text(if (saving) "Подготовка…" else "Сохранить") }
                }
            }
        }
    }
}
