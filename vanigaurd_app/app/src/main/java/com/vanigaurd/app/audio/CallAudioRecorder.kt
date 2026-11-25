
package com.vanigaurd.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.vanigaurd.app.LiveAudioApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.sqrt

class CallAudioRecorder(
    private val context: Context,
    private val sessionId: String,
    private val onAnalysis: (suspend (com.vanigaurd.app.SessionSegmentResult) -> Unit)? = null,
    private val onStatus: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "VaniGuardRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val PRE_ROLL_MS = 300
        private const val SILENCE_END_MS = 1200
        private const val MAX_CHUNK_MS = 12000

        private const val SPEECH_RMS_THRESHOLD = 650.0
        private const val READ_SIZE = 1024
    }

    private data class PendingChunk(
        val id: String,
        val offsetMs: Long,
        val wav: ByteArray
    )

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val queue = Channel<PendingChunk>(capacity = 8)

    @Volatile
    private var running = false

    @Volatile
    private var recorder: AudioRecord? = null

    private var captureThread: Thread? = null
    private var uploadJob: Job? = null

    private var startedAtElapsedMs = 0L

    fun start(): Boolean {
        if (running) return true

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("Microphone permission is not granted.")
            return false
        }

        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        if (minimumBuffer <= 0) {
            onStatus("Microphone configuration is unavailable.")
            return false
        }

        try {
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(minimumBuffer * 2, READ_SIZE * 2)
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                onStatus("Microphone could not be initialized.")
                return false
            }

            audioRecord.startRecording()
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

            Log.d(
                TAG,
                "CALL AUDIO STATE: " +
                        "mode=${audioManager.mode}, " +
                        "micMute=${audioManager.isMicrophoneMute}, " +
                        "recordingState=${audioRecord.recordingState}, " +
                        "audioSessionId=${audioRecord.audioSessionId}"
            )
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val configs = audioManager.activeRecordingConfigurations

                for (config in configs) {
                    Log.d(
                        TAG,
                        "RECORDING CONFIG: " +
                                "sessionId=${config.clientAudioSessionId}, " +
                                "source=${config.clientAudioSource}, " +
                                "silenced=${config.isClientSilenced}, " +
                                "sampleRate=${config.clientFormat.sampleRate}, " +
                                "channelMask=${config.clientFormat.channelMask}, " +
                                "device=${config.audioDevice}"
                    )
                }
            }

            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.release()
                onStatus("Microphone did not enter recording state.")
                return false
            }

            recorder = audioRecord
            running = true
            startedAtElapsedMs = SystemClock.elapsedRealtime()

            uploadJob = scope.launch {
                uploadLoop()
            }

            captureThread = Thread(
                { captureLoop(audioRecord) },
                "VaniGuardAudioCapture"
            ).apply {
                start()
            }

            onStatus(
                "Microphone capture active. Remote caller audio is not guaranteed."
            )

            Log.i(TAG, "Microphone capture started for session $sessionId")
            return true

        } catch (security: SecurityException) {
            Log.e(TAG, "Microphone permission/security restriction", security)
            onStatus("Android denied microphone access.")
            running = false
            return false

        } catch (error: Exception) {
            Log.e(TAG, "Unable to start audio capture", error)
            onStatus("Unable to start microphone capture.")
            running = false
            return false
        }
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val buffer = ShortArray(READ_SIZE)
        val preRoll = ArrayDeque<Short>()

        val maxPreRollSamples = SAMPLE_RATE * PRE_ROLL_MS / 1000
        val silenceSamplesRequired = SAMPLE_RATE * SILENCE_END_MS / 1000
        val maxChunkSamples = SAMPLE_RATE * MAX_CHUNK_MS / 1000

        val speechSamples = ArrayList<Short>()

        var speechStarted = false
        var silenceSamples = 0
        var chunkStartElapsedMs = 0L
        var lastRmsLogTime = 0L
        var peakRms = 0.0

        try {
            while (running) {
                val count = audioRecord.read(
                    buffer,
                    0,
                    buffer.size,
                    AudioRecord.READ_BLOCKING
                )

                if (count <= 0) {
                    if (count < 0) {
                        Log.e(TAG, "AudioRecord read failed: $count")
                        onStatus("Microphone read failed: $count")
                        break
                    }
                    continue
                }

                val rms = calculateRms(buffer, count)
                peakRms = maxOf(peakRms, rms)

                val now = SystemClock.elapsedRealtime()

                if (now - lastRmsLogTime >= 2000) {
                    Log.d(
                        TAG,
                        "Audio level: RMS=$rms, peakRMS=$peakRms, " +
                                "threshold=$SPEECH_RMS_THRESHOLD"
                    )

                    lastRmsLogTime = now
                    peakRms = 0.0
                }
                val speechDetected = rms >= SPEECH_RMS_THRESHOLD

                if (!speechStarted) {
                    for (i in 0 until count) {
                        preRoll.addLast(buffer[i])
                        while (preRoll.size > maxPreRollSamples) {
                            preRoll.removeFirst()
                        }
                    }

                    if (speechDetected) {
                        speechStarted = true
                        silenceSamples = 0
                        chunkStartElapsedMs = SystemClock.elapsedRealtime()

                        speechSamples.addAll(preRoll)
                        preRoll.clear()

                        Log.d(TAG, "Speech started. RMS=$rms")
                    }

                    continue
                }

                for (i in 0 until count) {
                    speechSamples.add(buffer[i])
                }

                if (speechDetected) {
                    silenceSamples = 0
                } else {
                    silenceSamples += count
                }

                val chunkDurationReached =
                    speechSamples.size >= maxChunkSamples

                val silenceReached =
                    silenceSamples >= silenceSamplesRequired

                if (chunkDurationReached || silenceReached) {
                    emitChunk(speechSamples, chunkStartElapsedMs)

                    speechSamples.clear()
                    preRoll.clear()
                    speechStarted = false
                    silenceSamples = 0
                }
            }

        } catch (error: Exception) {
            Log.e(TAG, "Capture loop failed", error)
            onStatus("Audio capture stopped unexpectedly.")

        } finally {
            // Preserve any speech already buffered when the call ends.
            if (speechSamples.isNotEmpty()) {
                emitChunk(speechSamples, chunkStartElapsedMs)
            }

            try {
                if (audioRecord.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    audioRecord.stop()
                }
            } catch (_: Exception) {
            }

            audioRecord.release()
            recorder = null

            queue.close()
            Log.i(TAG, "Capture loop ended.")
        }
    }

    private fun emitChunk(
        samples: List<Short>,
        chunkStartElapsedMs: Long
    ) {
        if (samples.isEmpty()) return

        val pcm = ShortArray(samples.size) { samples[it] }
        val pcmBytes = ByteArray(pcm.size * 2)

        for (i in pcm.indices) {
            val sample = pcm[i].toInt()
            pcmBytes[i * 2] = (sample and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        val wav = LiveAudioApi.pcmToWav(pcmBytes)

        val offset = (
                chunkStartElapsedMs - startedAtElapsedMs
                ).coerceAtLeast(0)

        val chunk = PendingChunk(
            id = UUID.randomUUID().toString(),
            offsetMs = offset,
            wav = wav
        )

        // This runs on the capture thread. A bounded queue applies
        // backpressure instead of allowing unlimited memory growth.
        val result = queue.trySend(chunk)

        if (result.isFailure) {
            Log.e(TAG, "Chunk queue full or closed; chunk was not queued.")
            onStatus("Audio upload queue is full. A speech chunk may be lost.")
        } else {
            Log.i(
                TAG,
                "Queued speech chunk ${chunk.id}, " +
                        "${samples.size} samples, offset=${offset}ms"
            )
        }
    }

    private suspend fun uploadLoop() {
        for (chunk in queue) {
            var attempt = 0
            var uploaded = false

            while (!uploaded) {
                try {
                    val result = LiveAudioApi.uploadSessionSegment(
                        sessionId = sessionId,
                        wavBytes = chunk.wav,
                        chunkId = chunk.id,
                        callOffsetMs = chunk.offsetMs
                    )

                    onAnalysis?.invoke(result)

                    Log.i(
                        TAG,
                        "Chunk uploaded: ${chunk.id}, " +
                                "risk=${result.analysis.riskLevel}, " +
                                "score=${result.analysis.fraudScore}"
                    )

                    uploaded = true

                } catch (error: Exception) {
                    attempt++

                    Log.e(
                        TAG,
                        "Chunk upload failed. " +
                                "Chunk=${chunk.id}, attempt=$attempt",
                        error
                    )

                    onStatus("Speech upload failed. Retrying.")

                    // Same chunk ID on every retry prevents duplicate
                    // transcript entries if the server already processed it.
                    val retryDelay = when (attempt) {
                        1 -> 1000L
                        2 -> 2000L
                        3 -> 4000L
                        4 -> 8000L
                        else -> 15000L
                    }

                    delay(retryDelay)
                }
            }
        }
    }

    suspend fun stopAndFlush() {
        if (!running && recorder == null) {
            uploadJob?.join()
            return
        }

        running = false

        try {
            recorder?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Recorder stop returned an error", error)
        }

        withContext(Dispatchers.IO) {
            captureThread?.join(5000)
        }

        // The capture loop closes the queue after emitting its final chunk.
        uploadJob?.join()

        captureThread = null
        uploadJob = null

        onStatus("Audio capture and queued uploads completed.")
        Log.i(TAG, "Recorder stopped and upload queue drained.")
    }

    private fun calculateRms(
        samples: ShortArray,
        count: Int
    ): Double {
        if (count <= 0) return 0.0

        var sum = 0.0

        for (i in 0 until count) {
            val value = samples[i].toDouble()
            sum += value * value
        }

        return sqrt(sum / count)
    }
}
