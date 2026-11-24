package com.vanigaurd.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------
// TABLE 1: Caller profiles
// ---------------------------------------------------------

@Entity(tableName = "caller_profiles")
data class CallerProfileEntity(

    @PrimaryKey
    val normalizedNumber: String,

    val displayName: String? = null,
    val businessName: String? = null,

    val countryCode: Int? = null,
    val regionCode: String? = null,
    val numberType: String? = null,
    val validationStatus: String? = null,

    val carrierName: String? = null,
    val carrierSource: String? = null,

    val identityVerified: Boolean = false,
    val identityProvider: String? = null,

    val firstSeenEpochMillis: Long = System.currentTimeMillis(),
    val lastSeenEpochMillis: Long = System.currentTimeMillis(),
    val metadataUpdatedAtEpochMillis: Long? = null
)

// ---------------------------------------------------------
// TABLE 2: Individual reputation evidence
// ---------------------------------------------------------

@Entity(
    tableName = "reputation_evidence",
    foreignKeys = [
        ForeignKey(
            entity = CallerProfileEntity::class,
            parentColumns = ["normalizedNumber"],
            childColumns = ["phoneNumber"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("phoneNumber"),
        Index("source"),
        Index("observedAtEpochMillis")
    ]
)
data class ReputationEvidenceEntity(

    @PrimaryKey(autoGenerate = true)
    val evidenceId: Long = 0,

    val phoneNumber: String,

    val source: String,
    val type: String,

    val description: String,

    val reportCount: Int = 0,
    val distinctReporterCount: Int = 0,

    val observedAtEpochMillis: Long = System.currentTimeMillis(),

    val isVerified: Boolean = false,

    val providerReference: String? = null
)

// ---------------------------------------------------------
// TABLE 3: Community reports
// ---------------------------------------------------------

@Entity(
    tableName = "community_reports",
    foreignKeys = [
        ForeignKey(
            entity = CallerProfileEntity::class,
            parentColumns = ["normalizedNumber"],
            childColumns = ["phoneNumber"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("phoneNumber"),
        Index("moderationStatus"),
        Index("submittedAtEpochMillis")
    ]
)
data class CommunityReportEntity(

    @PrimaryKey
    val reportId: String,

    val phoneNumber: String,

    // Example: FRAUD, IMPERSONATION, TELEMARKETING
    val category: String,

    val description: String? = null,

    // PENDING, ACCEPTED, REJECTED, REMOVED
    val moderationStatus: String = "PENDING",

    val submittedAtEpochMillis: Long = System.currentTimeMillis(),

    val reviewedAtEpochMillis: Long? = null
)

// ---------------------------------------------------------
// TABLE 4: Cached reputation assessment
// ---------------------------------------------------------

@Entity(tableName = "cached_reputations")
data class CachedReputationEntity(

    @PrimaryKey
    val phoneNumber: String,

    // LOW_RISK, UNVERIFIED, SUSPICIOUS, HIGH_RISK
    val level: String,

    val riskScore: Int,

    // LOW, MEDIUM, HIGH
    val confidence: String,

    val reportCount: Int,

    val explanation: String,

    val checkedAtEpochMillis: Long,

    val expiresAtEpochMillis: Long? = null
)

// ---------------------------------------------------------
// DAO: Database operations
// ---------------------------------------------------------

@Dao
interface CallerReputationDao {

    // Caller profiles

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCallerProfile(profile: CallerProfileEntity)

    @Query(
        "SELECT * FROM caller_profiles WHERE normalizedNumber = :number LIMIT 1"
    )
    suspend fun getCallerProfile(number: String): CallerProfileEntity?

    @Query(
        "SELECT * FROM caller_profiles WHERE normalizedNumber = :number LIMIT 1"
    )
    fun observeCallerProfile(number: String): Flow<CallerProfileEntity?>

    // Reputation evidence

    @Insert
    suspend fun insertEvidence(evidence: ReputationEvidenceEntity): Long

    @Query(
        """
    DELETE FROM reputation_evidence
    WHERE phoneNumber = :number
    AND source = 'LOCAL_COMMUNITY'
    AND type = 'COMMUNITY_SPAM_REPORT'
    """
    )
    suspend fun deleteBackendCommunityEvidence(number: String)

    @Query(
        """
        SELECT * FROM reputation_evidence
        WHERE phoneNumber = :number
        ORDER BY observedAtEpochMillis DESC
        """
    )
    suspend fun getEvidence(number: String): List<ReputationEvidenceEntity>

    @Query(
        """
        SELECT * FROM reputation_evidence
        WHERE phoneNumber = :number
        ORDER BY observedAtEpochMillis DESC
        """
    )
    fun observeEvidence(number: String): Flow<List<ReputationEvidenceEntity>>

    // Community reports

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCommunityReport(report: CommunityReportEntity): Long

    @Query(
        """
        SELECT * FROM community_reports
        WHERE phoneNumber = :number
        ORDER BY submittedAtEpochMillis DESC
        """
    )
    fun observeCommunityReports(number: String): Flow<List<CommunityReportEntity>>
    @Query(
        """
    SELECT * FROM community_reports
    WHERE phoneNumber = :number
    ORDER BY submittedAtEpochMillis DESC
    """
    )
    suspend fun getCommunityReports(number: String): List<CommunityReportEntity>

    @Query(
        """
        UPDATE community_reports
        SET moderationStatus = :status,
            reviewedAtEpochMillis = :reviewedAt
        WHERE reportId = :reportId
        """
    )
    suspend fun updateReportStatus(
        reportId: String,
        status: String,
        reviewedAt: Long
    )

    // Cached assessments

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCachedReputation(result: CachedReputationEntity)

    @Query(
        """
        SELECT * FROM cached_reputations
        WHERE phoneNumber = :number
        LIMIT 1
        """
    )
    suspend fun getCachedReputation(number: String): CachedReputationEntity?

    @Query(
        """
        SELECT * FROM cached_reputations
        WHERE phoneNumber = :number
        LIMIT 1
        """
    )
    fun observeCachedReputation(number: String): Flow<CachedReputationEntity?>

    @Query("DELETE FROM cached_reputations WHERE phoneNumber = :number")
    suspend fun deleteCachedReputation(number: String)
}

// ---------------------------------------------------------
// ROOM DATABASE
// ---------------------------------------------------------

@Database(
    entities = [
        CallerProfileEntity::class,
        ReputationEvidenceEntity::class,
        CommunityReportEntity::class,
        CachedReputationEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CallerReputationDatabase : RoomDatabase() {

    abstract fun callerReputationDao(): CallerReputationDao

    companion object {

        @Volatile
        private var INSTANCE: CallerReputationDatabase? = null

        fun getInstance(context: Context): CallerReputationDatabase {

            return INSTANCE ?: synchronized(this) {

                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CallerReputationDatabase::class.java,
                    "vaniguard_reputation.db"
                ).build().also {
                    INSTANCE = it
                }
            }
        }
    }
}
