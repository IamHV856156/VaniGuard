
package com.vanigaurd.app.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CommunityReputationResponse(
    @SerializedName("phone_number")
    val phoneNumber: String,
    val reputation: String,
    @SerializedName("accepted_reports")
    val acceptedReports: Int,
    @SerializedName("pending_reports")
    val pendingReports: Int,
    val message: String
)

data class CommunityReportSubmissionResponse(
    val message: String,
    @SerializedName("report_id")
    val reportId: Int,
    @SerializedName("phone_number")
    val phoneNumber: String,
    val category: String,
    val status: String,
    @SerializedName("evidence_score")
    val evidenceScore: Double,
    @SerializedName("qualifying_installations")
    val qualifyingInstallations: Int,
    @SerializedName("auto_moderation_triggered")
    val autoModerationTriggered: Boolean
)

object CommunityReputationApi {

    private const val BASE_URL = "http://192.168.0.102:8000"
    private const val PREFS_NAME = "vaniguard_identity"
    private const val INSTALLATION_ID_KEY = "reporter_installation_id"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun getInstallationId(context: Context): String {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val existingId = preferences.getString(INSTALLATION_ID_KEY, null)

        if (existingId != null) {
            return existingId
        }

        val newId = UUID.randomUUID().toString()

        preferences.edit()
            .putString(INSTALLATION_ID_KEY, newId)
            .apply()

        return newId
    }

    suspend fun fetchReputation(
        phoneNumber: String
    ): CommunityReputationResponse? = withContext(Dispatchers.IO) {
        try {
            val encodedNumber = Uri.encode(phoneNumber)

            val request = Request.Builder()
                .url("$BASE_URL/reputation/$encodedNumber")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }

                val body = response.body?.string()
                    ?: return@withContext null

                gson.fromJson(body, CommunityReputationResponse::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun submitReport(
        context: Context,
        phoneNumber: String,
        category: String,
        description: String?,
        evidenceText: String
    ): CommunityReportSubmissionResponse? = withContext(Dispatchers.IO) {
        try {
            val payload = mapOf(
                "phone_number" to phoneNumber,
                "category" to category,
                "description" to description,
                "reporter_id" to getInstallationId(context),
                "evidence_text" to evidenceText
            )

            val requestBody = gson.toJson(payload)
                .toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$BASE_URL/reports")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }

                val body = response.body?.string()
                    ?: return@withContext null

                gson.fromJson(
                    body,
                    CommunityReportSubmissionResponse::class.java
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
