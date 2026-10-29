package uk.mywisp.privatechat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CustomizationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun photoViewerAppliesCameraOrientation() {
        val file = File(ui.activity.cacheDir, "camera-orientation.jpg")
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            file.outputStream().use { check(source.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            android.media.ExifInterface(file.path).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val decoded = requireNotNull(decodeProfile(file.path))
            try { assertEquals(40, decoded.width); assertEquals(80, decoded.height) }
            finally { decoded.recycle() }
        } finally { source.recycle(); file.delete() }
    }

    @Test fun chosenCropMatchesTheUploadedSquare() {
        // Two halves make off-center / incorrect transform regressions visible.
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(source)
        canvas.drawColor(Color.RED)
        canvas.drawRect(200f, 0f, 400f, 200f, android.graphics.Paint().apply { color = Color.BLUE })
        try {
            val crop = avatarCrop(400, 200, 2f, 1f, 0f)
            assertEquals(300f, crop.left, .01f)
            assertEquals(50f, crop.top, .01f)
            val bytes = cropAvatarJpeg(source, crop)
            val result = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertEquals(512, result.width); assertEquals(512, result.height)
                for (point in listOf(20 to 20, 256 to 256, 490 to 490)) {
                    val color = result.getPixel(point.first, point.second)
                    assertTrue(Color.blue(color) > 240 && Color.red(color) < 15)
                }
            } finally { result.recycle() }
            for (zoom in listOf(1f, 2f, 5f)) for (x in listOf(-1f, 0f, 1f)) for (y in listOf(-1f, 0f, 1f)) {
                val c = avatarCrop(200, 400, zoom, x, y)
                assertTrue(c.left >= 0 && c.top >= 0 && c.left + c.side <= 200.01f && c.top + c.side <= 400.01f)
            }
        } finally { source.recycle() }
    }

    @Test fun editorAllowsZoomAndSavesOnlyAfterConfirmation() {
        val file = File(ui.activity.cacheDir, "editor-test.png")
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val saved = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ui.runOnUiThread {
                ui.activity.setContent { PrivateTheme { AvatarEditor(Uri.fromFile(file), {}, { saved.set(it) }) } }
            }
            ui.waitUntil(10_000) { ui.onAllNodesWithText("Сохранить").fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            } }
            ui.onNodeWithContentDescription("Область обрезки аватара").assertExists()
            ui.onNodeWithContentDescription("Масштаб аватара").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(3f) }
            assertNull(saved.get())
            ui.onNodeWithText("Сохранить").performClick()
            ui.waitUntil(10_000) { saved.get() != null }
            val jpeg = requireNotNull(saved.get())
            val result = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            assertEquals(512, result.width); assertEquals(512, result.height); result.recycle()
        } finally { source.recycle(); file.delete() }
    }

    @Test fun fullPaletteAndExtendedPreferencesSurviveReopening() {
        val prefs = AppearancePreferences(ui.activity)
        try {
            val custom = Appearance("dark", "custom", 150f, .5f, true, 20f, 12f, "gradient", true)
            prefs.set(custom)
            assertEquals(custom, AppearancePreferences(ui.activity).state.value)
            for (dark in listOf(false, true)) {
                val ocean = tonalScheme(seedColor(Appearance(accent = "ocean")), dark, 0f)
                val forest = tonalScheme(seedColor(Appearance(accent = "forest")), dark, 0f)
                assertNotEquals(ocean.primary, forest.primary)
                assertNotEquals(ocean.secondaryContainer, forest.secondaryContainer)
                assertNotEquals(ocean.surfaceContainerLow, forest.surfaceContainerLow)
            }
        } finally { prefs.reset() }
    }
}
