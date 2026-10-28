package uk.mywisp.privatechat

import android.content.Context
import android.media.MediaRecorder
import java.io.File
import java.util.UUID

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L

    fun start(limit: () -> Unit) {
        cancel()
        val directory = File(context.cacheDir, "uploads").apply { mkdirs() }
        file = File(directory, "voice_${UUID.randomUUID()}.m4a")
        val created = MediaRecorder()
        recorder = created
        created.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64000)
            setAudioSamplingRate(44100)
            setOutputFile(file!!.path)
            setMaxDuration(180_000)
            setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) limit() }
            prepare(); start()
        }
        started = android.os.SystemClock.elapsedRealtime()
    }

    fun stop(): Pair<File, Long>? {
        val current = recorder ?: return null
        recorder = null
        val duration = android.os.SystemClock.elapsedRealtime() - started
        val result = runCatching { current.stop(); file?.takeIf { it.length() > 0 && duration >= 500 }?.let { it to duration } }.getOrNull()
        current.release()
        if (result == null) file?.delete()
        file = null
        return result
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete(); file = null
    }
}
