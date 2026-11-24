package com.vanigaurd.app.domain

/**
 * Reputation status is an assessment of available evidence,
 * not a guarantee that a caller is safe or fraudulent.
 */
enum class ReputationLevel {
    LOW_RISK,
    UNVERIFIED,
    SUSPICIOUS,
    HIGH_RISK
}

/**
 * Where the reputation evidence originated.
 */
enum class EvidenceSource {
    LOCAL_COMMUNITY,
    EXTERNAL_PROVIDER,
    ANDROID_NETWORK,
    NUMBER_METADATA,
    USER
}

/**
 * Types of evidence accepted by the reputation engine.
 */
enum class EvidenceType {
    COMMUNITY_SPAM_REPORT,
    PROVIDER_SPAM_REPORT,
    PROVIDER_CONFIRMED_FRAUD,
    PROVIDER_LOW_RISK_SIGNAL,
    NETWORK_VERIFICATION_FAILED,
    NETWORK_VERIFICATION_PASSED,
    NUMBER_METADATA_VALID
}

/**
 * Confidence describes how reliable the available evidence is.
 * It is NOT the probability that a caller is fraudulent.
 */
enum class EvidenceConfidence {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * One aggregated piece of reputation evidence.
 *
 * reportCount:
 * Number of reports represented by this evidence.
 *
 * distinctReporterCount:
 * Number of distinct reporters, when the source provides it.
 */
data class ReputationEvidence(
    val source: EvidenceSource,
    val type: EvidenceType,
    val description: String,
    val reportCount: Int = 0,
    val distinctReporterCount: Int = 0,
    val observedAtEpochMillis: Long = 0L,
    val isVerified: Boolean = false
)

/**
 * Complete assessment returned by CallerReputationEngine.
 *
 * riskScore is an internal evidence-based risk index (0-100).
 * It is NOT a fraud probability.
 */
data class CallerReputationResult(
    val phoneNumber: String,
    val level: ReputationLevel,
    val riskScore: Int,
    val confidence: EvidenceConfidence,
    val reportCount: Int,
    val evidence: List<ReputationEvidence>,
    val explanation: String,
    val checkedAtEpochMillis: Long,
    val isCached: Boolean = false
)

/**
 * Offline-first caller reputation assessment.
 *
 * This engine does not perform network requests or database access.
 * It only evaluates evidence supplied to it.
 */
object CallerReputationEngine {

    fun assess(
        phoneNumber: String,
        evidence: List<ReputationEvidence>,
        nowEpochMillis: Long = System.currentTimeMillis(),
        isCached: Boolean = false
    ): CallerReputationResult {

        val validEvidence = evidence.filter {
            it.description.isNotBlank() &&
                    it.reportCount >= 0 &&
                    it.distinctReporterCount >= 0
        }

        val riskEvidence = validEvidence.filter {
            it.type == EvidenceType.COMMUNITY_SPAM_REPORT ||
                    it.type == EvidenceType.PROVIDER_SPAM_REPORT ||
                    it.type == EvidenceType.PROVIDER_CONFIRMED_FRAUD ||
                    it.type == EvidenceType.NETWORK_VERIFICATION_FAILED
        }

        val hasConfirmedFraud = riskEvidence.any {
            it.type == EvidenceType.PROVIDER_CONFIRMED_FRAUD &&
                    it.isVerified
        }

        val riskScore = calculateRiskScore(riskEvidence)

        val hasTrustedLowRiskSignal = validEvidence.any {
            it.type == EvidenceType.PROVIDER_LOW_RISK_SIGNAL &&
                    it.source == EvidenceSource.EXTERNAL_PROVIDER &&
                    it.isVerified
        }

        val level = when {
            hasConfirmedFraud -> ReputationLevel.HIGH_RISK
            riskScore >= 60 -> ReputationLevel.HIGH_RISK
            riskScore >= 25 -> ReputationLevel.SUSPICIOUS

            hasTrustedLowRiskSignal && riskEvidence.isEmpty() ->
                ReputationLevel.LOW_RISK

            else -> ReputationLevel.UNVERIFIED
        }

        val confidence = calculateConfidence(
            riskEvidence = riskEvidence,
            allEvidence = validEvidence
        )

        val reportCount = riskEvidence
            .filter {
                it.type == EvidenceType.COMMUNITY_SPAM_REPORT ||
                        it.type == EvidenceType.PROVIDER_SPAM_REPORT
            }
            .sumOf { it.reportCount }

        val explanation = when (level) {
            ReputationLevel.HIGH_RISK ->
                "Strong fraud indicators were found. Treat this caller with caution."

            ReputationLevel.SUSPICIOUS ->
                "Some suspicious reputation evidence was found. Verify the caller before sharing sensitive information."

            ReputationLevel.LOW_RISK ->
                "A trusted provider supplied a low-risk signal. This does not guarantee caller safety."

            ReputationLevel.UNVERIFIED ->
                "There is not enough reliable reputation evidence to classify this caller."
        }

        return CallerReputationResult(
            phoneNumber = phoneNumber,
            level = level,
            riskScore = riskScore,
            confidence = confidence,
            reportCount = reportCount,
            evidence = validEvidence,
            explanation = explanation,
            checkedAtEpochMillis = nowEpochMillis,
            isCached = isCached
        )
    }

    private fun calculateRiskScore(
        evidence: List<ReputationEvidence>
    ): Int {

        var score = 0

        evidence.forEach { item ->
            when (item.type) {

                EvidenceType.PROVIDER_CONFIRMED_FRAUD -> {
                    if (item.isVerified) {
                        score += 85
                    }
                }

                EvidenceType.PROVIDER_SPAM_REPORT -> {
                    score += when {
                        item.reportCount >= 5 -> 55
                        item.reportCount >= 2 -> 35
                        item.reportCount >= 1 -> 20
                        else -> 0
                    }
                }

                EvidenceType.COMMUNITY_SPAM_REPORT -> {
                    score += when {
                        item.reportCount >= 4 &&
                                item.distinctReporterCount >= 3 -> 40

                        item.reportCount >= 2 &&
                                item.distinctReporterCount >= 2 -> 25

                        item.reportCount >= 1 -> 10

                        else -> 0
                    }
                }

                EvidenceType.NETWORK_VERIFICATION_FAILED -> {
                    score += 25
                }

                else -> Unit
            }
        }

        return score.coerceIn(0, 100)
    }

    private fun calculateConfidence(
        riskEvidence: List<ReputationEvidence>,
        allEvidence: List<ReputationEvidence>
    ): EvidenceConfidence {

        if (allEvidence.isEmpty()) {
            return EvidenceConfidence.LOW
        }

        val independentSources = riskEvidence
            .map { it.source }
            .distinct()
            .size

        val verifiedEvidence = riskEvidence.count {
            it.isVerified
        }

        val hasMultipleReporters = riskEvidence.any {
            it.distinctReporterCount >= 3
        }

        return when {
            verifiedEvidence > 0 &&
                    independentSources >= 2 ->
                EvidenceConfidence.HIGH

            verifiedEvidence > 0 ||
                    hasMultipleReporters ||
                    independentSources >= 2 ->
                EvidenceConfidence.MEDIUM

            else -> EvidenceConfidence.LOW
        }
    }
}
