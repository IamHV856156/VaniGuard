package com.vanigaurd.app.data

import com.vanigaurd.app.domain.CallerReputationEngine
import com.vanigaurd.app.domain.CallerReputationResult
import com.vanigaurd.app.domain.EvidenceConfidence
import com.vanigaurd.app.domain.EvidenceSource
import com.vanigaurd.app.domain.EvidenceType
import com.vanigaurd.app.domain.ReputationEvidence
import com.vanigaurd.app.domain.ReputationLevel
import com.vanigaurd.app.utils.PhoneNumberIntelligence
import com.vanigaurd.app.utils.PhoneNumberMetadata
import java.util.UUID

enum class CommunityReportStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    REMOVED
}

class CallerReputationRepository(
    private val dao: CallerReputationDao
) {

    // -----------------------------------------------------
    // 1. Save or update caller profile
    // -----------------------------------------------------

    suspend fun saveCallerProfile(
        metadata: PhoneNumberMetadata,
        displayName: String? = null
    ): String? {

        val number = metadata.normalizedNumber ?: return null
        val now = System.currentTimeMillis()

        val existing = dao.getCallerProfile(number)

        val profile = CallerProfileEntity(
            normalizedNumber = number,

            displayName = displayName
                ?.takeIf { it.isNotBlank() }
                ?: existing?.displayName,

            businessName = existing?.businessName,

            countryCode = metadata.countryCode,
            regionCode = metadata.regionCode,
            numberType = metadata.numberType,
            validationStatus = metadata.validationStatus.name,

            carrierName = existing?.carrierName,
            carrierSource = existing?.carrierSource,

            identityVerified = existing?.identityVerified ?: false,
            identityProvider = existing?.identityProvider,

            firstSeenEpochMillis =
                existing?.firstSeenEpochMillis ?: now,

            lastSeenEpochMillis = now,
            metadataUpdatedAtEpochMillis = now
        )

        dao.upsertCallerProfile(profile)

        return number
    }

    // -----------------------------------------------------
    // 2. Add reputation evidence
    // -----------------------------------------------------

    suspend fun addEvidence(
        metadata: PhoneNumberMetadata,
        evidence: ReputationEvidence
    ): Long? {

        val number = saveCallerProfile(metadata) ?: return null

        val entity = ReputationEvidenceEntity(
            phoneNumber = number,
            source = evidence.source.name,
            type = evidence.type.name,
            description = evidence.description,
            reportCount = evidence.reportCount,
            distinctReporterCount = evidence.distinctReporterCount,
            observedAtEpochMillis = evidence.observedAtEpochMillis,
            isVerified = evidence.isVerified
        )

        return dao.insertEvidence(entity)
    }

    // -----------------------------------------------------
    // 3. Submit a community report
    // -----------------------------------------------------

    suspend fun submitCommunityReport(
        metadata: PhoneNumberMetadata,
        category: String,
        description: String? = null
    ): String? {

        val number = saveCallerProfile(metadata) ?: return null

        val reportId = UUID.randomUUID().toString()

        val report = CommunityReportEntity(
            reportId = reportId,
            phoneNumber = number,
            category = category.trim().uppercase(),
            description = description?.takeIf { it.isNotBlank() },
            moderationStatus = CommunityReportStatus.PENDING.name
        )

        val inserted = dao.insertCommunityReport(report)

        return if (inserted == -1L) null else reportId
    }

    suspend fun syncBackendCommunityReputation(
        metadata: PhoneNumberMetadata,
        acceptedReports: Int,
        message: String
    ): CallerReputationResult? {

        val number = saveCallerProfile(metadata) ?: return null

        // Remove previous backend community evidence.
        dao.deleteBackendCommunityEvidence(number)

        // Only accepted reports contribute to risk.
        if (acceptedReports > 0) {
            dao.insertEvidence(
                ReputationEvidenceEntity(
                    phoneNumber = number,
                    source = EvidenceSource.LOCAL_COMMUNITY.name,
                    type = EvidenceType.COMMUNITY_SPAM_REPORT.name,
                    description = message,
                    reportCount = acceptedReports,
                    distinctReporterCount = 0,
                    observedAtEpochMillis = System.currentTimeMillis(),
                    isVerified = false
                )
            )
        }

        // Invalidate the old assessment.
        dao.deleteCachedReputation(number)

        // Recalculate using all locally available evidence.
        return assessCaller(number)
    }

    // -----------------------------------------------------
    // 4. Moderate a community report
    // -----------------------------------------------------

    suspend fun moderateCommunityReport(
        reportId: String,
        status: CommunityReportStatus
    ) {

        dao.updateReportStatus(
            reportId = reportId,
            status = status.name,
            reviewedAt = System.currentTimeMillis()
        )
    }

    // -----------------------------------------------------
    // 5. Calculate and cache caller reputation
    // -----------------------------------------------------

    suspend fun assessCaller(
        phoneNumber: String
    ): CallerReputationResult? {

        val normalizedNumber = normalize(phoneNumber) ?: return null

        val storedEvidence = dao.getEvidence(normalizedNumber)

        val evidence = storedEvidence.mapNotNull { entity ->
            entity.toDomainEvidence()
        }.toMutableList()

        // Only accepted community reports contribute.
        // Pending, rejected and removed reports are excluded.
        val acceptedReports = dao.getCommunityReports(normalizedNumber)
            .filter {
                it.moderationStatus == CommunityReportStatus.ACCEPTED.name
            }

        if (acceptedReports.isNotEmpty()) {
            evidence.add(
                ReputationEvidence(
                    source = EvidenceSource.LOCAL_COMMUNITY,
                    type = EvidenceType.COMMUNITY_SPAM_REPORT,
                    description = "${acceptedReports.size} accepted community report(s).",
                    reportCount = acceptedReports.size,

                    // Reporter identity and independent reporter
                    // counting will be implemented in Stage 5.
                    distinctReporterCount = 0,

                    observedAtEpochMillis = acceptedReports.maxOf {
                        it.submittedAtEpochMillis
                    },
                    isVerified = false
                )
            )
        }

        val result = CallerReputationEngine.assess(
            phoneNumber = normalizedNumber,
            evidence = evidence
        )

        dao.saveCachedReputation(
            CachedReputationEntity(
                phoneNumber = result.phoneNumber,
                level = result.level.name,
                riskScore = result.riskScore,
                confidence = result.confidence.name,
                reportCount = result.reportCount,
                explanation = result.explanation,
                checkedAtEpochMillis = result.checkedAtEpochMillis
            )
        )

        return result
    }

    // -----------------------------------------------------
    // 6. Retrieve cached assessment without network access
    // -----------------------------------------------------

    suspend fun getCachedAssessment(
        phoneNumber: String
    ): CallerReputationResult? {

        val number = normalize(phoneNumber) ?: return null

        val cached = dao.getCachedReputation(number) ?: return null

        val now = System.currentTimeMillis()

        if (cached.expiresAtEpochMillis != null &&
            cached.expiresAtEpochMillis < now
        ) {
            return null
        }

        val evidence = dao.getEvidence(number)
            .mapNotNull { it.toDomainEvidence() }
            .toMutableList()

        val acceptedReports = dao.getCommunityReports(number)
            .filter {
                it.moderationStatus == CommunityReportStatus.ACCEPTED.name
            }

        if (acceptedReports.isNotEmpty()) {
            evidence.add(
                ReputationEvidence(
                    source = EvidenceSource.LOCAL_COMMUNITY,
                    type = EvidenceType.COMMUNITY_SPAM_REPORT,
                    description = "${acceptedReports.size} accepted community report(s).",
                    reportCount = acceptedReports.size,
                    distinctReporterCount = 0,
                    observedAtEpochMillis = acceptedReports.maxOf {
                        it.submittedAtEpochMillis
                    }
                )
            )
        }

        return CallerReputationResult(
            phoneNumber = cached.phoneNumber,
            level = runCatching {
                ReputationLevel.valueOf(cached.level)
            }.getOrDefault(ReputationLevel.UNVERIFIED),

            riskScore = cached.riskScore.coerceIn(0, 100),

            confidence = runCatching {
                EvidenceConfidence.valueOf(cached.confidence)
            }.getOrDefault(EvidenceConfidence.LOW),

            reportCount = cached.reportCount,
            evidence = evidence,
            explanation = cached.explanation,
            checkedAtEpochMillis = cached.checkedAtEpochMillis,
            isCached = true
        )
    }

    // -----------------------------------------------------
    // 7. Retrieve caller profile
    // -----------------------------------------------------

    suspend fun getCallerProfile(
        phoneNumber: String
    ): CallerProfileEntity? {

        val number = normalize(phoneNumber) ?: return null
        return dao.getCallerProfile(number)
    }

    // -----------------------------------------------------
    // 8. Retrieve stored evidence
    // -----------------------------------------------------

    suspend fun getCallerEvidence(
        phoneNumber: String
    ): List<ReputationEvidence> {

        val number = normalize(phoneNumber) ?: return emptyList()

        return dao.getEvidence(number).mapNotNull {
            it.toDomainEvidence()
        }
    }

    // -----------------------------------------------------
    // Helpers
    // -----------------------------------------------------

    private fun normalize(phoneNumber: String): String? {
        return PhoneNumberIntelligence
            .analyze(phoneNumber)
            .normalizedNumber
    }

    private fun ReputationEvidenceEntity.toDomainEvidence():
            ReputationEvidence? {

        val parsedSource = runCatching {
            EvidenceSource.valueOf(source)
        }.getOrNull() ?: return null

        val parsedType = runCatching {
            EvidenceType.valueOf(type)
        }.getOrNull() ?: return null

        return ReputationEvidence(
            source = parsedSource,
            type = parsedType,
            description = description,
            reportCount = reportCount,
            distinctReporterCount = distinctReporterCount,
            observedAtEpochMillis = observedAtEpochMillis,
            isVerified = isVerified
        )
    }
}
