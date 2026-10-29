package uk.mywisp.privatechat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

internal val avatarShapeNames = linkedMapOf("circle" to "Круг", "squircle" to "Скруглённый", "flower" to "Цветок", "clover" to "Клевер", "pebble" to "Камешек")

internal fun leanAvatarShape(name: String): Shape = when (name) {
    "circle" -> CircleShape
    "squircle" -> RoundedCornerShape(32)
    else -> GenericShape { size, _ ->
        for (i in 0..180) {
            val t = i * 2.0 * PI / 180
            val r = when (name) {
                "flower" -> .455 + .035 * cos(8 * t)
                "clover" -> .425 + .065 * cos(4 * t)
                else -> .44 + .035 * sin(3 * t) + .02 * cos(2 * t)
            }
            val x = size.width * (.5 + r * cos(t)).toFloat()
            val y = size.height * (.5 + r * sin(t)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

@Composable
internal fun LeanMark(size: Int = 80) {
    Surface(Modifier.size(size.dp), shape = leanAvatarShape("flower"), color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(painterResource(R.drawable.lean_glass), "Lean", Modifier.padding((size * .17f).dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun ProfileAvatar(repo: MessengerRepository, user: String?, name: String, revision: Int, size: Int, shapeOverride: String? = null) {
    var profile by remember(user, revision) { mutableStateOf(UserProfile(name = name)) }
    val bitmap by produceState<Bitmap?>(null, user, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (user == null) return@runCatching null
                val p = repo.userProfile(user)
                profile = p
                p.avatar?.let { decodeProfile(repo.profilePhotoUrl(it).path, 768) }
            }.getOrNull()
        }
    }
    AvatarArtwork(bitmap, profile.name.ifBlank { name }, shapeOverride ?: profile.shape, size)
}

@Composable
internal fun AvatarArtwork(bitmap: Bitmap?, name: String, shape: String, size: Int) {
    Surface(Modifier.size(size.dp), shape = remember(shape) { leanAvatarShape(shape) }, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), "Аватар", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(name.trim().take(1).uppercase().ifBlank { "L" }, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = if (size > 80) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleLarge)
        }
    }
}

internal fun decodeProfile(path: String, limit: Int = 2048): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > limit || bounds.outHeight / sample > limit) sample *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val orientation = runCatching { android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
    val matrix = android.graphics.Matrix()
    when (orientation) {
        2 -> matrix.setScale(-1f, 1f)
        3 -> matrix.setRotate(180f)
        4 -> matrix.setScale(1f, -1f)
        5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        6 -> matrix.setRotate(90f)
        7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
        8 -> matrix.setRotate(270f)
    }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}
