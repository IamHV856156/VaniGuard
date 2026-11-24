
package com.vanigaurd.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LiveAnalysis(
    val transcript: String,
    val prediction: String,
    val fraudScore: Double,
    val riskLevel: String,
    val warning: String
)

data class CallSession(
    val sessionId: String,
    val status: String
)

data class SessionSegmentResult(
    val sessionId: String,
    val segmentNumber: Int,
    val segmentTranscript: String,
    val fullTranscript: String,
    val prediction: String,
    val fraudScore: Double,
    val riskLevel: String,
    val warning: String
)

data class FinishedCallReport(
    val sessionId: String,
    val status: String,
    val segmentCount: Int,
    val fullTranscript: String,
    val prediction: String,
    val fraudScore: Double,
    val riskLevel: String,
    val warning: String
)

object LiveAudioApi {

    private const val BASE_URL = "http://192.168.0.102:8000"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val wavType = "audio/wav".toMediaType()

    private suspend fun execute(request: Request): JSONObject =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw Exception(
                        "Server error ${response.code}: $body"
                    )
                }

                JSONObject(body)
            }
        }

    suspend fun createSession(): CallSession {
        val request = Request.Builder()
            .url("$BASE_URL/fraud/sessions")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        val json = execute(request)

        return CallSession(
            sessionId = json.getString("session_id"),
            status = json.optString("status", "ACTIVE")
        )
    }

    suspend fun uploadSessionSegment(
        sessionId: String,
        wavBytes: ByteArray
    ): SessionSegmentResult {

        val fileBody = wavBytes.toRequestBody(wavType)

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "segment.wav",
                fileBody
            )
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/fraud/sessions/$sessionId/segments")
            .post(multipart)
            .build()

        val json = execute(request)
        val analysis = json.optJSONObject("analysis") ?: JSONObject()

        return SessionSegmentResult(
            sessionId = json.optString("session_id", sessionId),
            segmentNumber = json.optInt("segment_number", 0),
            segmentTranscript = json.optString("segment_transcript", ""),
            fullTranscript = json.optString("full_transcript", ""),
            prediction = analysis.optString("prediction", "UNKNOWN"),
            fraudScore = analysis.optDouble("fraud_score", 0.0),
            riskLevel = analysis.optString("risk_level", "UNKNOWN"),
            warning = analysis.optString("warning", "")
        )
    }

    suspend fun finishSession(sessionId: String): FinishedCallReport {
        val request = Request.Builder()
            .url("$BASE_URL/fraud/sessions/$sessionId/finish")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        val json = execute(request)
        val analysis = json.optJSONObject("final_analysis") ?: JSONObject()

        return FinishedCallReport(
            sessionId = json.optString("session_id", sessionId),
            status = json.optString("status", "COMPLETED"),
            segmentCount = json.optInt("segment_count", 0),
            fullTranscript = json.optString("full_transcript", ""),
            prediction = analysis.optString("prediction", "UNKNOWN"),
            fraudScore = analysis.optDouble("fraud_score", 0.0),
            riskLevel = analysis.optString("risk_level", "UNKNOWN"),
            warning = analysis.optString("warning", "")
        )
    }

    // Retained for compatibility with the existing Live Shield code.
    suspend fun analyzeWav(wavBytes: ByteArray): LiveAnalysis {
        val fileBody = wavBytes.toRequestBody(wavType)

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "recording.wav", fileBody)
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/fraud/analyze-audio")
            .post(multipart)
            .build()

        val json = execute(request)
        val transcription = json.optJSONObject("transcription") ?: JSONObject()
        val analysis = json.optJSONObject("final_analysis") ?: JSONObject()

        return LiveAnalysis(
            transcript = transcription.optString("transcript", ""),
            prediction = analysis.optString("prediction", "UNKNOWN"),
            fraudScore = analysis.optDouble("fraud_score", 0.0),
            riskLevel = analysis.optString("risk_level", "UNKNOWN"),
            warning = analysis.optString("warning", "")
        )
    }

    fun pcmToWav(samples: ShortArray): ByteArray {
        val byteRate = 16000 * 1 * 16 / 8
        val dataSize = samples.size * 2
        val totalSize = 36 + dataSize

        val output = java.io.ByteArrayOutputStream()

        fun writeInt(value: Int) {
            output.write(value and 0xff)
            output.write((value shr 8) and 0xff)
            output.write((value shr 16) and 0xff)
            output.write((value shr 24) and 0xff)
        }

        fun writeShort(value: Int) {
            output.write(value and 0xff)
            output.write((value shr 8) and 0xff)
        }

        output.write("RIFF".toByteArray())
        writeInt(totalSize)
        output.write("WAVE".toByteArray())
        output.write("fmt ".toByteArray())
        writeInt(16)
        writeShort(1)
        writeShort(1)
        writeInt(16000)
        writeInt(byteRate)
        writeShort(2)
        writeShort(16)
        output.write("data".toByteArray())
        writeInt(dataSize)

        samples.forEach {
            writeShort(it.toInt())
        }

        return output.toByteArray()
    }
}
