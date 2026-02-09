package com.traceback.data

import android.content.Context
import androidx.room.*

/**
 * Network fingerprint types
 */
enum class NetworkType {
    UNKNOWN,    // Not yet classified
    STATIC,     // Home, Office - doesn't move
    DYNAMIC     // Car, Ship - moves with user
}

/**
 * Learned network fingerprint
 */
@Entity(tableName = "network_fingerprints")
data class NetworkFingerprint(
    @PrimaryKey val identifier: String,  // SSID or BT address
    val name: String,                     // Display name
    val isBluetooth: Boolean,
    var type: NetworkType = NetworkType.UNKNOWN,
    var learnedLatitude: Double? = null,
    var learnedLongitude: Double? = null,
    var confidenceScore: Float = 0f,      // 0-1, how sure we are about the type
    var sampleCount: Int = 0,             // How many GPS samples we have
    var lastSeen: Long = System.currentTimeMillis(),
    var createdAt: Long = System.currentTimeMillis()
)

@Dao
interface NetworkFingerprintDao {
    @Query("SELECT * FROM network_fingerprints WHERE identifier = :id")
    suspend fun getById(id: String): NetworkFingerprint?
    
    @Query("SELECT * FROM network_fingerprints WHERE identifier IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<NetworkFingerprint>
    
    @Query("SELECT * FROM network_fingerprints WHERE type = :type")
    suspend fun getByType(type: NetworkType): List<NetworkFingerprint>
    
    @Query("SELECT * FROM network_fingerprints ORDER BY lastSeen DESC")
    suspend fun getAll(): List<NetworkFingerprint>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fingerprint: NetworkFingerprint)
    
    @Update
    suspend fun update(fingerprint: NetworkFingerprint)
    
    @Delete
    suspend fun delete(fingerprint: NetworkFingerprint)
    
    @Query("DELETE FROM network_fingerprints WHERE identifier = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [NetworkFingerprint::class], version = 1, exportSchema = false)
abstract class TraceBackDatabase : RoomDatabase() {
    abstract fun networkFingerprintDao(): NetworkFingerprintDao
    
    companion object {
        @Volatile
        private var INSTANCE: TraceBackDatabase? = null
        
        fun getDatabase(context: Context): TraceBackDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TraceBackDatabase::class.java,
                    "traceback_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
