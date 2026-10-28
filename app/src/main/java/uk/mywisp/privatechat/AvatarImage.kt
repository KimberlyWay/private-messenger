package uk.mywisp.privatechat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** Bounds memory use, applies camera orientation and strips EXIF before upload. */
internal fun avatarJpeg(context: Context, uri: Uri): ByteArray {
    val file = File.createTempFile("avatar-source-", ".img", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 20L * 1024 * 1024) { "Выберите фото до 20 МБ" }
                    output.write(buffer, 0, n)
                }
            }
        } ?: error("Не удалось открыть фото")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать изображение" }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val original = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Выберите поддерживаемое фото")
        val matrix = Matrix()
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        val side = minOf(rotated.width, rotated.height)
        val square = Bitmap.createBitmap(rotated, (rotated.width - side) / 2, (rotated.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, minOf(side, 512), minOf(side, 512), true)
        return try {
            ByteArrayOutputStream().use { out ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)) { "Не удалось подготовить фото" }
                out.toByteArray()
            }
        } finally { listOf(original, rotated, square, scaled).distinct().forEach { it.recycle() } }
    } finally { file.delete() }
}
