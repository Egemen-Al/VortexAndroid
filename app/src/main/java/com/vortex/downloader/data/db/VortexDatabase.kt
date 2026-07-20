package com.vortex.downloader.data.db

import androidx.lifecycle.LiveData
import androidx.room.*

// ─── Entity ──────────────────────────────────────────────────────────────────

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val format: String,       // "1080p", "MP3" vb.
    val ext: String,          // "mp4", "mp3"
    val filePath: String?,
    val fileSizeBytes: Long = 0L,
    val status: String,       // "completed" | "failed" | "cancelled"
    val createdAt: Long = System.currentTimeMillis(),
    val durationSec: Long = 0L,
    val uploader: String?,
    // Dosyanın MediaStore'a yayınlandığı content:// URI'si (ya da eski Android'lerde
    // dosya yolu). Geçmişten silerken gerçek dosyayı da silebilmek için saklanır.
    val contentUri: String? = null,
)

// ─── DAO ─────────────────────────────────────────────────────────────────────

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAll(): LiveData<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC LIMIT 50")
    suspend fun getAllOnce(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity): Long

    @Delete
    suspend fun delete(entity: DownloadEntity)

    @Query("DELETE FROM downloads")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'completed'")
    suspend fun completedCount(): Int
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(entities = [DownloadEntity::class], version = 2, exportSchema = false)
abstract class VortexDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile private var INSTANCE: VortexDatabase? = null

        fun getInstance(context: android.content.Context): VortexDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    VortexDatabase::class.java,
                    "vortex_db"
                )
                    // Uygulama henüz yayınlanmadı / geçmiş kaydı kritik değil,
                    // bu yüzden şema değişikliğinde basitçe yeniden oluşturuyoruz.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
