@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package uk.mywisp.privatechat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

class CircleCaptureActivity : ComponentActivity() {
    private var active: Recording? = null
    private var outputFile: File? = null
    private var approved = false
    private var started = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            finish(); return
        }
        setContent {
            PrivateTheme {
                var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
                var recording by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var seconds by remember { mutableIntStateOf(0) }
                val preview = remember { PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
                DisposableEffect(Unit) {
                    val future = ProcessCameraProvider.getInstance(this@CircleCaptureActivity)
                    future.addListener({
                        try {
                            val provider = future.get()
                            val camera = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA
                                else CameraSelector.DEFAULT_BACK_CAMERA
                            val view = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                            val video = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build())
                            provider.unbindAll()
                            provider.bindToLifecycle(this@CircleCaptureActivity, camera, view, video)
                            capture = video
                        } catch (_: Exception) { error = "Камера недоступна" }
                    }, ContextCompat.getMainExecutor(this@CircleCaptureActivity))
                    onDispose { active?.close(); active = null }
                }
                LaunchedEffect(recording) {
                    while (recording) {
                        kotlinx.coroutines.delay(1000); seconds++
                        if (seconds >= 60) { approved = true; active?.stop(); recording = false }
                    }
                }
                Scaffold(topBar = { TopAppBar(title = { Text("Видеокружочек") }) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
                        AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape))
                        Text(if (recording) "${seconds} / 60 секунд" else "До 60 секунд")
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedButton(onClick = { approved = false; active?.close(); active = null; outputFile?.delete(); finish() }) { Text("Отмена") }
                            Button(enabled = capture != null && error == null, onClick = {
                                if (recording) {
                                    approved = true; active?.stop(); recording = false
                                } else {
                                    try {
                                        approved = false; seconds = 0
                                        val directory = File(cacheDir, "uploads").apply { mkdirs() }
                                        val file = File(directory, "circle_${UUID.randomUUID()}.mp4").also { outputFile = it }
                                        val output = FileOutputOptions.Builder(file).setDurationLimitMillis(60_000).build()
                                        started = android.os.SystemClock.elapsedRealtime()
                                        active = capture!!.output.prepareRecording(this@CircleCaptureActivity, output)
                                            .withAudioEnabled().start(ContextCompat.getMainExecutor(this@CircleCaptureActivity)) { event ->
                                                if (event is VideoRecordEvent.Finalize) {
                                                    active = null
                                                    val durationLimit = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                                                    if ((!event.hasError() || durationLimit) && (approved || durationLimit) && file.length() > 0) {
                                                        setResult(RESULT_OK, Intent().putExtra("file", file.path)
                                                            .putExtra("duration", android.os.SystemClock.elapsedRealtime() - started))
                                                        finish()
                                                    } else {
                                                        file.delete()
                                                        recording = false
                                                        if (!isFinishing) error = "Запись отменена или камера не смогла сохранить видео"
                                                    }
                                                }
                                            }
                                        recording = true
                                    } catch (_: Exception) { recording = false; error = "Не удалось начать запись" }
                                }
                            }) { Text(if (recording) "Отправить" else "Записать") }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        if (!approved) { active?.close(); active = null }
        super.onStop()
    }
}
