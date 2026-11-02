package hrln.interactive.sidekit.apps.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Platform Camera2 keeps Camera dependency-free: CameraX would add camera-core, camera-camera2,
 * camera-lifecycle and camera-video to a launcher where every MB and background thread matters.
 * One camera, one capture session and one 480p recorder are open only while Camera is started.
 */
class CameraController(private val context: Context) {
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()
    private val manager = context.getSystemService(CameraManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var texture: TextureView? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recordingValues: ContentValues? = null
    private var recordingUri: Uri? = null
    private var cameraId: String? = null
    private var sensorOrientation = 90
    private var timer: java.util.Timer? = null

    @RequiresPermission(Manifest.permission.CAMERA)
    fun start(view: TextureView) {
        texture = view
        if (device != null) return
        if (view.isAvailable) open(view.surfaceTexture!!, view.width, view.height)
    }

    fun surfaceReady(view: TextureView, width: Int, height: Int) {
        texture = view
        open(view.surfaceTexture!!, width, height)
    }

    /** Camera and recorder are closed synchronously on STOP; no preview survives in the background. */
    fun stop() {
        if (_state.value.recording) stopRecording()
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
        recorder?.release(); recorder = null
        texture = null
        thread?.quitSafely(); thread = null; handler = null
    }

    @SuppressLint("MissingPermission")
    private fun open(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (device != null) return
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: run { fail("No camera available"); return }
        cameraId = id
        sensorOrientation = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        surfaceTexture.setDefaultBufferSize(1280, 720)
        val hThread = HandlerThread("SideKitCamera").also { it.start() }
        thread = hThread
        handler = Handler(hThread.looper)
        reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1).apply {
            setOnImageAvailableListener({ input ->
                val image = input.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bytes = image.planes[0].buffer.let { ByteArray(it.remaining()).also(it::get) }
                image.close()
                savePhoto(bytes)
            }, handler)
        }
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; preview() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); device = null; fail("Camera unavailable") }
            })
        } catch (e: Exception) { fail(e.message ?: "Camera unavailable") }
    }

    private fun preview() {
        if (_state.value.recording) return
        val view = texture ?: return
        val surface = Surface(view.surfaceTexture)
        createSession(listOf(surface, reader!!.surface)) { request ->
            request.addTarget(surface)
            applyFlash(request)
        }
    }

    private fun createSession(surfaces: List<Surface>, configure: (CaptureRequest.Builder) -> Unit) {
        val camera = device ?: return
        session?.close()
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(capture: CameraCaptureSession) {
                session = capture
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        configure(this)
                    }.build()
                    capture.setRepeatingRequest(request, null, handler)
                } catch (e: Exception) { fail(e.message ?: "Camera unavailable") }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) = fail("Couldn't start camera")
        }, handler)
    }

    fun toggleFlash() {
        _state.value = _state.value.copy(flashOn = !_state.value.flashOn)
        if (!_state.value.recording) preview()
    }

    private fun applyFlash(request: CaptureRequest.Builder) {
        if (_state.value.flashOn) request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
    }

    fun takePhoto() {
        val camera = device ?: return
        val capture = session ?: return
        if (_state.value.recording || _state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader!!.surface)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                applyFlash(this)
            }.build()
            capture.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    _state.value = _state.value.copy(busy = false); fail("Photo failed")
                }
            }, handler)
        } catch (e: Exception) { _state.value = _state.value.copy(busy = false); fail("Photo failed") }
    }

    private fun savePhoto(bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SideKit-${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        try {
            if (uri == null) throw IOException("Insert failed")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Write failed")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            _state.value = _state.value.copy(busy = false, lastCapture = uri, error = null)
        } catch (e: Exception) {
            uri?.let { resolver.delete(it, null, null) }
            _state.value = _state.value.copy(busy = false)
            fail("Couldn't save photo")
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleRecording() { if (_state.value.recording) stopRecording() else startRecording() }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        val camera = device ?: return
        val view = texture ?: return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "SideKit-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SideKit")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return fail("Couldn't create video")
        val mediaRecorder = MediaRecorder()
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Open failed")
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fd.fileDescriptor)
                setVideoEncodingBitRate(2_000_000)
                setVideoFrameRate(30)
                setVideoSize(1280, 720)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOrientationHint(sensorOrientation)
                prepare()
            }
            fd.close()
            recorder = mediaRecorder; recordingUri = uri; recordingValues = values
            val previewSurface = Surface(view.surfaceTexture)
            createSession(listOf(previewSurface, mediaRecorder.surface)) { request ->
                request.addTarget(previewSurface); request.addTarget(mediaRecorder.surface)
            }
            // Start after the recorder's surface is part of an active session.
            handler?.postDelayed({
                try {
                    mediaRecorder.start()
                    _state.value = _state.value.copy(recording = true, elapsedSeconds = 0, error = null)
                    startTimer()
                } catch (e: Exception) { abortRecording(uri) }
            }, 150)
        } catch (e: Exception) {
            mediaRecorder.release(); recorder = null
            context.contentResolver.delete(uri, null, null)
            fail("Couldn't record video")
        }
    }

    private fun stopRecording() {
        timer?.cancel(); timer = null
        try { recorder?.stop() } catch (_: Exception) { }
        recorder?.reset(); recorder?.release(); recorder = null
        val uri = recordingUri
        if (uri != null) {
            val values = recordingValues ?: ContentValues()
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            _state.value = _state.value.copy(recording = false, elapsedSeconds = 0, lastCapture = uri)
        } else _state.value = _state.value.copy(recording = false, elapsedSeconds = 0)
        recordingUri = null; recordingValues = null
        preview()
    }

    private fun abortRecording(uri: Uri) {
        recorder?.release(); recorder = null
        context.contentResolver.delete(uri, null, null)
        recordingUri = null; recordingValues = null
        _state.value = _state.value.copy(recording = false)
        fail("Couldn't record video"); preview()
    }

    private fun startTimer() {
        timer = java.util.Timer("CameraTimer", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() { if (_state.value.recording) _state.value = _state.value.tick() }
            }, 1000, 1000)
        }
    }

    private fun fail(message: String) { _state.value = _state.value.copy(busy = false, error = message) }
}
