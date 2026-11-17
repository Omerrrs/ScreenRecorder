package com.screenrecorder.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.screenrecorder.app.ACTION_START"
        const val ACTION_STOP = "com.screenrecorder.app.ACTION_STOP"
        const val ACTION_RECORDING_STOPPED = "com.screenrecorder.app.RECORDING_STOPPED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_FILE_PATH = "file_path"

        private const val TAG = "RecordingService"
        private const val NOTIFICATION_CHANNEL_ID = "screen_recorder_channel"
        private const val NOTIFICATION_ID = 1001

        // Video settings: 1080p, 60fps
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val VIDEO_FPS = 60
        private const val VIDEO_BITRATE = 8_000_000  // 8 Mbps
        private const val VIDEO_IFRAME_INTERVAL = 2   // keyframe every 2s

        // Audio settings: AAC, stereo, 44100 Hz
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNEL_COUNT = 2
        private const val AUDIO_BITRATE = 128_000
        private const val AUDIO_BUFFER_SIZE_MULTIPLIER = 2
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var mediaMuxer: MediaMuxer? = null
    private var outputFile: File? = null
    private var outputUri: Uri? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val isRecording = AtomicBoolean(false)

    private var audioThread: Thread? = null
    private var videoEncoderThread: Thread? = null

    private val muxerLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode != -1 && data != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    startRecording(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            // Register callback to handle projection stopping
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (isRecording.get()) {
                        stopRecording()
                    }
                }
            }, null)

            // Prepare output file
            prepareOutputFile()

            // Setup MediaMuxer
            mediaMuxer = MediaMuxer(
                outputFile!!.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            isRecording.set(true)

            // Start video encoder + virtual display
            setupVideoEncoder()

            // Start audio capture (internal/media audio)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setupAudioCapture()
            }

        } catch (e: Exception) {
            Log.e(TAG, "startRecording error", e)
            stopRecording()
        }
    }

    private fun prepareOutputFile() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ScreenRecord_$timestamp.mp4"

        outputFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: use MediaStore to put directly in Movies
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            moviesDir.mkdirs()
            File(moviesDir, fileName)
        } else {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            moviesDir.mkdirs()
            File(moviesDir, fileName)
        }
    }

    private fun setupVideoEncoder() {
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_COMPLEXITY, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder!!.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val surface = videoEncoder!!.createInputSurface()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width().let {
                resources.displayMetrics.densityDpi
            }
        } else {
            @Suppress("DEPRECATION")
            DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }.densityDpi
        }

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenRecorder",
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        videoEncoder!!.start()

        // Thread to drain video encoder output
        videoEncoderThread = Thread {
            drainVideoEncoder()
        }.also { it.start() }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupAudioCapture() {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, AUDIO_SAMPLE_RATE * 2) * AUDIO_BUFFER_SIZE_MULTIPLIER

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            // Setup AAC audio encoder
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder!!.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder!!.start()

            audioRecord!!.startRecording()

            audioThread = Thread {
                captureAudio(bufferSize)
            }.also { it.start() }

        } catch (e: Exception) {
            Log.e(TAG, "Audio capture setup failed", e)
            // Continue without audio if setup fails
        }
    }

    private fun drainVideoEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRecording.get()) {
            val outputIndex = videoEncoder?.dequeueOutputBuffer(bufferInfo, 10_000L) ?: break
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = videoEncoder!!.outputFormat
                    synchronized(muxerLock) {
                        videoTrackIndex = mediaMuxer!!.addTrack(newFormat)
                        checkAndStartMuxer()
                    }
                }
                outputIndex >= 0 -> {
                    val encodedData = videoEncoder!!.getOutputBuffer(outputIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        synchronized(muxerLock) {
                            if (muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                        }
                    }
                    videoEncoder?.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun captureAudio(bufferSize: Int) {
        val buffer = ByteArray(bufferSize / 2)
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording.get()) {
            // Read PCM from AudioRecord
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (bytesRead <= 0) continue

            // Feed PCM into audio encoder
            val inputIndex = audioEncoder?.dequeueInputBuffer(10_000L) ?: continue
            if (inputIndex >= 0) {
                val inputBuffer = audioEncoder?.getInputBuffer(inputIndex) ?: continue
                inputBuffer.clear()
                inputBuffer.put(buffer, 0, bytesRead)
                audioEncoder?.queueInputBuffer(inputIndex, 0, bytesRead, System.nanoTime() / 1000L, 0)
            }

            // Drain audio encoder output
            var outputIndex = audioEncoder?.dequeueOutputBuffer(bufferInfo, 0L) ?: continue
            while (outputIndex >= 0) {
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = audioEncoder!!.outputFormat
                        synchronized(muxerLock) {
                            audioTrackIndex = mediaMuxer!!.addTrack(newFormat)
                            checkAndStartMuxer()
                        }
                    }
                    outputIndex >= 0 -> {
                        val encodedData = audioEncoder?.getOutputBuffer(outputIndex) ?: break
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size > 0) {
                            synchronized(muxerLock) {
                                if (muxerStarted) {
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    mediaMuxer?.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                                }
                            }
                        }
                        audioEncoder?.releaseOutputBuffer(outputIndex, false)
                    }
                }
                outputIndex = audioEncoder?.dequeueOutputBuffer(bufferInfo, 0L) ?: break
            }
        }

        // Signal end of stream
        val inputIndex = audioEncoder?.dequeueInputBuffer(10_000L) ?: return
        if (inputIndex >= 0) {
            audioEncoder?.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    private fun checkAndStartMuxer() {
        // Start muxer when both tracks are ready
        // If no audio (Android < Q), start when video track is ready
        val audioReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || audioTrackIndex >= 0
        if (videoTrackIndex >= 0 && audioReady && !muxerStarted) {
            mediaMuxer?.start()
            muxerStarted = true
        }
    }

    private fun stopRecording() {
        if (!isRecording.getAndSet(false)) return

        try {
            // Stop audio capture
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // Wait for threads to finish
            audioThread?.join(2000)
            videoEncoderThread?.join(2000)

            // Signal end of stream to video encoder
            videoEncoder?.signalEndOfInputStream()

            // Stop encoders
            Thread.sleep(200)
            videoEncoder?.stop()
            videoEncoder?.release()
            videoEncoder = null

            audioEncoder?.stop()
            audioEncoder?.release()
            audioEncoder = null

            // Release virtual display and projection
            virtualDisplay?.release()
            virtualDisplay = null

            mediaProjection?.stop()
            mediaProjection = null

            // Stop muxer
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
            mediaMuxer = null

            // Add file to media store (for Gallery visibility)
            outputFile?.let { file ->
                addFileToMediaStore(file)
            }

            // Notify UI
            val broadcastIntent = Intent(ACTION_RECORDING_STOPPED).apply {
                putExtra(EXTRA_FILE_PATH, outputFile?.absolutePath ?: "")
                setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)

        } catch (e: Exception) {
            Log.e(TAG, "stopRecording error", e)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun addFileToMediaStore(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                }
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DATA, file.absolutePath)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                }
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            Log.e(TAG, "addFileToMediaStore error", e)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 1, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Ekran Kaydediliyor")
            .setContentText("Kaydı durdurmak için uygulamayı açın.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Ekran Kaydedici",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ekran kaydı devam ediyor"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording.get()) {
            stopRecording()
        }
    }
}
