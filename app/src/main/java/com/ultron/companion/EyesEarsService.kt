package com.ultron.companion

import android.Manifest
import android.app.*
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * "Eyes and ears": while running, periodically grabs a still frame from
 * the phone's camera and streams short chunks of raw mic audio to
 * whichever PC ULTRON is paired with, over the same WebSocket
 * UltronClient already keeps open for commands (see
 * UltronClient.sendVisionFrame / sendAudioChunk, and
 * cross_device/live_sense.py on the PC side for how these are consumed).
 *
 * Deliberately simple: a still JPEG every FRAME_INTERVAL_MS, not a live
 * video stream - a paired PC on a home LAN/Tailscale link doesn't need
 * (and this app doesn't try to do) full video streaming. Same idea for
 * audio: short PCM16 chunks, not a continuous codec stream.
 *
 * Starts/stops from the Settings tab (see MainActivity.kt's toggle) -
 * never launches itself; both CAMERA and RECORD_AUDIO must already be
 * granted or this refuses to start.
 */
class EyesEarsService : LifecycleService() {

    companion object {
        private const val TAG = "EyesEarsService"
        private const val NOTIF_ID = 43
        private const val CHANNEL_ID = "ultron_eyes_ears"
        private const val FRAME_INTERVAL_MS = 5000L
        private const val AUDIO_SAMPLE_RATE = 16000
        private const val AUDIO_CHUNK_MS = 2000L
    }

    private lateinit var client: UltronClient
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var frameTimer: java.util.Timer? = null
    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        client = UltronClient.get(applicationContext)
        createChannel()
        startForeground(NOTIF_ID, notification("starting"))

        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (!hasCamera && !hasMic) {
            Log.w(TAG, "Neither CAMERA nor RECORD_AUDIO granted - stopping")
            update("no permissions granted"); stopSelf(); return
        }
        if (hasCamera) startCamera() else Log.w(TAG, "CAMERA not granted - eyes disabled, ears only")
        if (hasMic) startAudio() else Log.w(TAG, "RECORD_AUDIO not granted - ears disabled, eyes only")
        update("live")
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    // ---------------------------------------------------------------- camera
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                // Back camera by default - matches "what's in front of the
                // phone", i.e. the user's environment, not the user's face.
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
                scheduleFrameCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scheduleFrameCapture() {
        frameTimer = java.util.Timer().apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() { captureOneFrame() }
            }, 0, FRAME_INTERVAL_MS)
        }
    }

    private fun captureOneFrame() {
        val capture = imageCapture ?: return
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    client.sendVisionFrame(b64)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame encode failed: ${e.message}")
                } finally {
                    image.close()
                }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed: ${exception.message}")
            }
        })
    }

    // ----------------------------------------------------------------- audio
    private fun startAudio() {
        val minBuf = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { Log.e(TAG, "Bad AudioRecord buffer size"); return }
        val chunkBytes = (AUDIO_SAMPLE_RATE * (AUDIO_CHUNK_MS / 1000.0) * 2).toInt() // 16-bit = 2 bytes/sample

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunkBytes)
            )
        } catch (e: SecurityException) { Log.e(TAG, "No mic permission: ${e.message}"); return }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "AudioRecord init failed"); return }

        audioRunning = true
        recorder.startRecording()
        audioThread = thread(name = "ultron-mic") {
            val buf = ByteArray(chunkBytes)
            while (audioRunning) {
                var offset = 0
                while (audioRunning && offset < buf.size) {
                    val n = recorder.read(buf, offset, buf.size - offset)
                    if (n > 0) offset += n else break
                }
                if (offset > 0) {
                    val b64 = Base64.encodeToString(buf, 0, offset, Base64.NO_WRAP)
                    client.sendAudioChunk(b64, AUDIO_SAMPLE_RATE)
                }
            }
            recorder.stop(); recorder.release()
        }
    }

    // --------------------------------------------------------------- service
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ULTRON Eyes & Ears", NotificationManager.IMPORTANCE_LOW)
        )
    }
    private fun notification(state: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("ULTRON is watching/listening")
        .setContentText("Live sense: $state")
        .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
    private fun update(state: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(state))
    }

    override fun onDestroy() {
        frameTimer?.cancel(); frameTimer = null
        audioRunning = false
        audioThread?.join(500)
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
