package com.vanigaurd.app.service

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.util.Log
import android.os.Build

import com.vanigaurd.app.data.CommunityReputationApi
import com.vanigaurd.app.data.CallerReputationDatabase
import com.vanigaurd.app.data.CallerReputationRepository
import com.vanigaurd.app.domain.EvidenceSource
import com.vanigaurd.app.domain.EvidenceType
import com.vanigaurd.app.domain.ReputationEvidence
import com.vanigaurd.app.utils.PhoneNumberIntelligence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VaniGuardCallScreeningService : CallScreeningService() {

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {

        Log.d(
            "VaniGuardScreening",
            "Screening callback received. Direction: ${callDetails.callDirection}"
        )

        // We only process incoming calls.
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            return
        }

        // Allow immediately. Never wait for database or network work.
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .build()

        respondToCall(callDetails, response)

        // Number may be unavailable or restricted.
        val handle = callDetails.handle ?: return

        if (handle.scheme != "tel") return

        val incomingNumber = handle.schemeSpecificPart
            ?.takeIf { it.isNotBlank() }
            ?: return

        // Collect reputation evidence asynchronously.
        serviceScope.launch {
            processCaller(incomingNumber, callDetails)
        }
    }

    private suspend fun processCaller(
        incomingNumber: String,
        callDetails: Call.Details
    ) {
        try {
            val metadata = PhoneNumberIntelligence.analyze(
                input = incomingNumber,
                defaultRegion = "IN"
            )

            val normalizedNumber =
                metadata.normalizedNumber ?: return

            val database = CallerReputationDatabase.getInstance(
                applicationContext
            )

            val repository = CallerReputationRepository(
                database.callerReputationDao()
            )

            // Save caller metadata even when no network verdict exists.
            repository.saveCallerProfile(metadata)

            // Android 11+ exposes the network verification verdict.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                when (callDetails.callerNumberVerificationStatus) {

                    Connection.VERIFICATION_STATUS_FAILED -> {
                        repository.addEvidence(
                            metadata = metadata,
                            evidence = ReputationEvidence(
                                source = EvidenceSource.ANDROID_NETWORK,
                                type = EvidenceType.NETWORK_VERIFICATION_FAILED,
                                description = "Carrier number verification failed.",
                                isVerified = true
                            )
                        )
                    }

                    Connection.VERIFICATION_STATUS_PASSED -> {
                        repository.addEvidence(
                            metadata = metadata,
                            evidence = ReputationEvidence(
                                source = EvidenceSource.ANDROID_NETWORK,
                                type = EvidenceType.NETWORK_VERIFICATION_PASSED,
                                description = "Carrier number verification passed.",
                                isVerified = true
                            )
                        )
                    }

                    else -> {
                        // Unknown / unavailable is not suspicious.
                    }
                }
            }

            // Fetch community reputation from the backend asynchronously.
            val backendReputation =
                CommunityReputationApi.fetchReputation(normalizedNumber)

            if (backendReputation != null) {

                // Sync accepted reports only. Pending reports do not affect risk.
                repository.syncBackendCommunityReputation(
                    metadata = metadata,
                    acceptedReports = backendReputation.acceptedReports,
                    message = backendReputation.message
                )

                Log.d(
                    "VaniGuardScreening",
                    "Backend community reputation synced."
                )

            } else {

                // Backend unavailable: continue with locally available evidence.
                repository.assessCaller(normalizedNumber)

                Log.w(
                    "VaniGuardScreening",
                    "Backend reputation unavailable. Using local evidence."
                )
            }

        } catch (exception: Exception) {
            // Avoid logging phone numbers or sensitive call information.
            Log.e(
                "VaniGuardScreening",
                "Unable to process caller reputation",
                exception
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
