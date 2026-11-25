
package com.vanigaurd.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType

data class LiveAnalysis(
    val prediction: String = "UNKNOWN",
    val fraudScore: Double = 0.0,
    val riskLevel: String = "UNKNOWN",
    val semanticScore: Double? = null,
    val recommendedAction: String = "CONTINUE",
    val indicators: List<String> = emptyList(),
    val explanation: String = "",
    val warning: String = ""
)

data class CallSession(
    val sessionId: String,
    val status: String
)

data class ConversationEntry(
    val segmentNumber: Int = 0,
    val chunkId: String = "",
    val speaker: String = "UNKNOWN",
    val text: String = "",
    val startSeconds: Double? = null,
    val endSeconds: Double? = null,
    val callOffsetMs: Long? = null,
    val receivedAt: String? = null
)

data class SessionSegmentResult(
    val sessionId: String,
    val segmentNumber: Int,
    val segmentTranscript: String,
    val fullTranscript: String,
    val analysis: LiveAnalysis,
    val status: String,
    val conversationSegments: List<ConversationEntry> = emptyList(),
    val duplicate: Boolean = false
) {
    val recommendedAction: String
        get() = analysis.recommendedAction

    val indicators: List<String>
        get() = analysis.indicators
}

data class FinishedCallReport(
    val sessionId: String,
    val status: String,
    val segmentCount: Int,
    val fullTranscript: String,
    val finalAnalysis: LiveAnalysis,
    val conversationSegments: List<ConversationEntry> = emptyList(),
    val indicators: List<String> = emptyList()
)

object LiveAudioApi {

    private const val BASE_URL = "http://192.168.2.214:8000"
    private const val TIMEOUT_SECONDS = 45L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val wavMediaType = "audio/wav".toMediaType()

    suspend fun createSession(): CallSession = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/fraud/sessions")
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        val json = execute(request)
        CallSession(
            sessionId = json.getString("session_id"),
            status = json.optString("status", "ACTIVE")
        )
    }

    suspend fun uploadSessionSegment(
        sessionId: String,
        wavBytes: ByteArray,
        chunkId: String = UUID.randomUUID().toString(),
        callOffsetMs: Long? = null
    ): SessionSegmentResult = withContext(Dispatchers.IO) {

        require(wavBytes.isNotEmpty()) { "Audio chunk is empty." }

        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "$chunkId.wav",
                RequestBody.create(wavMediaType, wavBytes)
            )
            .addFormDataPart("chunk_id", chunkId)
            .apply {
                callOffsetMs?.let {
                    addFormDataPart("call_offset_ms", it.toString())
                }
            }
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/fraud/sessions/$sessionId/segments")
            .post(form)
            .build()

        val json = execute(request)
        val analysisJson = json.optJSONObject("analysis") ?: JSONObject()

        SessionSegmentResult(
            sessionId = json.optString("session_id", sessionId),
            segmentNumber = json.optInt("segment_number", 0),
            segmentTranscript = json.optString("segment_transcript", ""),
            fullTranscript = json.optString("full_transcript", ""),
            analysis = parseAnalysis(analysisJson),
            status = json.optString("status", "ACTIVE"),
            conversationSegments = parseConversationEntries(
                json.optJSONArray("conversation_segments")
            ),
            duplicate = json.optBoolean("duplicate", false)
        )
    }

    suspend fun finishSession(sessionId: String): FinishedCallReport =
        withContext(Dispatchers.IO) {

            val request = Request.Builder()
                .url("$BASE_URL/fraud/sessions/$sessionId/finish")
                .post(RequestBody.create(null, ByteArray(0)))
                .build()

            val json = execute(request)
            val analysisJson =
                json.optJSONObject("final_analysis") ?: JSONObject()

            FinishedCallReport(
                sessionId = json.optString("session_id", sessionId),
                status = json.optString("status", "COMPLETED"),
                segmentCount = json.optInt("segment_count", 0),
                fullTranscript = json.optString("full_transcript", ""),
                finalAnalysis = parseAnalysis(analysisJson),
                conversationSegments = parseConversationEntries(
                    json.optJSONArray("conversation_segments")
                ),
                indicators = parseStringArray(
                    analysisJson.optJSONArray("indicators")
                )
            )
        }

    suspend fun analyzeWav(wavBytes: ByteArray): LiveAnalysis =
        withContext(Dispatchers.IO) {
            val form = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "recording.wav",
                    RequestBody.create(wavMediaType, wavBytes)
                )
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/fraud/analyze-audio")
                .post(form)
                .build()

            val json = execute(request)
            val finalAnalysis =
                json.optJSONObject("final_analysis") ?: JSONObject()

            parseAnalysis(finalAnalysis)
        }

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "VaniGuard API error ${response.code}: $body"
                )
            }

            if (body.isBlank()) {
                throw IllegalStateException("Backend returned an empty response.")
            }

            return JSONObject(body)
        }
    }

    private fun parseAnalysis(json: JSONObject): LiveAnalysis {
        return LiveAnalysis(
            prediction = json.optString("prediction", "UNKNOWN"),
            fraudScore = json.optDouble("fraud_score", 0.0),
            riskLevel = json.optString("risk_level", "UNKNOWN"),
            semanticScore = if (json.isNull("semantic_score")) {
                null
            } else {
                json.optDouble("semantic_score")
            },
            recommendedAction = json.optString(
                "recommended_action",
                "CONTINUE"
            ),
            indicators = parseStringArray(
                json.optJSONArray("indicators")
            ),
            explanation = json.optString("explanation", ""),
            warning = json.optString("warning", "")
        )
    }

    private fun parseConversationEntries(
        array: JSONArray?
    ): List<ConversationEntry> {
        if (array == null) return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue

                add(
                    ConversationEntry(
                        segmentNumber = item.optInt("segment_number", 0),
                        chunkId = item.optString("chunk_id", ""),
                        speaker = item.optString("speaker", "UNKNOWN"),
                        text = item.optString("text", ""),
                        startSeconds = nullableDouble(item, "start_seconds"),
                        endSeconds = nullableDouble(item, "end_seconds"),
                        callOffsetMs = nullableLong(item, "call_offset_ms"),
                        receivedAt = item.optString("received_at").takeIf {
                            it.isNotBlank()
                        }
                    )
                )
            }
        }
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun nullableDouble(json: JSONObject, key: String): Double? {
        return if (json.isNull(key)) null else json.optDouble(key)
    }

    private fun nullableLong(json: JSONObject, key: String): Long? {
        return if (json.isNull(key)) null else json.optLong(key)
    }

    fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)

        fun writeIntLE(value: Int) {
            data.writeByte(value and 0xff)
            data.writeByte((value shr 8) and 0xff)
            data.writeByte((value shr 16) and 0xff)
            data.writeByte((value shr 24) and 0xff)
        }

        fun writeShortLE(value: Int) {
            data.writeByte(value and 0xff)
            data.writeByte((value shr 8) and 0xff)
        }

        data.writeBytes("RIFF")
        writeIntLE(36 + pcmData.size)
        data.writeBytes("WAVE")
        data.writeBytes("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(channels)
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(bitsPerSample)
        data.writeBytes("data")
        writeIntLE(pcmData.size)
        data.write(pcmData)
        data.flush()

        return output.toByteArray()
    }
}
