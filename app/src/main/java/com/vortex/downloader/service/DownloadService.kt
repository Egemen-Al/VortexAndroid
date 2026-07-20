package com.vortex.downloader.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.vortex.downloader.R
import com.vortex.downloader.data.db.DownloadEntity
import com.vortex.downloader.data.db.VortexDatabase
import com.vortex.downloader.ui.MainActivity
import com.vortex.downloader.util.BinaryManager
import com.vortex.downloader.util.MediaStoreHelper
import com.vortex.downloader.util.YtDlpRunner
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadService : Service() {

    companion object {
        const val ACTION_DOWNLOAD       = "com.vortex.DOWNLOAD"
        const val ACTION_DOWNLOAD_IMAGE = "com.vortex.DOWNLOAD_IMAGE"
        const val ACTION_CANCEL         = "com.vortex.CANCEL"
        const val EXTRA_URL        = "url"
        const val EXTRA_FORMAT_ID  = "format_id"
        const val EXTRA_FORMAT_LBL = "format_label"
        const val EXTRA_EXT        = "ext"
        const val EXTRA_IS_AUDIO   = "is_audio"
        const val EXTRA_TITLE      = "title"
        const val EXTRA_THUMB      = "thumb"

        const val CHANNEL_ID   = "vortex_dl"
        const val NOTIF_ID     = 42

        // LocalBroadcast actions
        const val BROADCAST_PROGRESS  = "com.vortex.PROGRESS"
        const val BROADCAST_DONE      = "com.vortex.DONE"
        const val BROADCAST_ERROR     = "com.vortex.ERROR"
        const val EXTRA_PCT           = "pct"
        const val EXTRA_SPEED         = "speed"
        const val EXTRA_ETA           = "eta"
        const val EXTRA_SUCCESS       = "success"
        const val EXTRA_FILE          = "file"
        const val EXTRA_ERR_MSG       = "err_msg"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var binaryManager: BinaryManager
    private lateinit var runner: YtDlpRunner
    private lateinit var db: VortexDatabase
    private var currentJob: Job? = null
    private var currentProcessId: String? = null

    override fun onCreate() {
        super.onCreate()
        binaryManager = BinaryManager(this)
        runner = YtDlpRunner(binaryManager, this)
        db = VortexDatabase.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val url       = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val formatId  = intent.getStringExtra(EXTRA_FORMAT_ID) ?: "best"
                val formatLbl = intent.getStringExtra(EXTRA_FORMAT_LBL) ?: "best"
                val ext       = intent.getStringExtra(EXTRA_EXT) ?: "mp4"
                val isAudio   = intent.getBooleanExtra(EXTRA_IS_AUDIO, false)
                val title     = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
                val thumb     = intent.getStringExtra(EXTRA_THUMB)

                startForeground(NOTIF_ID, buildNotification("Hazırlanıyor…", 0))
                startDownload(url, formatId, formatLbl, ext, isAudio, title, thumb)
            }
            ACTION_DOWNLOAD_IMAGE -> {
                val url   = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Fotoğraf"

                startForeground(NOTIF_ID, buildNotification("Fotoğraf indiriliyor…", 0))
                startImageDownload(url, title)
            }
            ACTION_CANCEL -> {
                // Coroutine'i iptal etmek yeterli değil: altında çalışan yt-dlp
                // process'i de gerçekten öldürmemiz lazım, yoksa indirme arka
                // planda devam eder.
                currentProcessId?.let { runner.cancel(it) }
                currentJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ─── Video/Ses indirme (yt-dlp) ────────────────────────────────────────────

    private fun startDownload(
        url: String, formatId: String, formatLbl: String,
        ext: String, isAudio: Boolean, title: String, thumb: String?,
    ) {
        val processId = "vortex_dl_${System.currentTimeMillis()}"
        currentProcessId = processId

        currentJob = scope.launch {
            // Scoped storage (Android 10+) altında genel Movies/Music dizinlerine
            // doğrudan yazılamıyor. Önce uygulamaya özel (izin gerektirmeyen) bir
            // dizine indirip, ardından MediaStore üzerinden galeriye yayınlıyoruz.
            val stagingDir = getStagingDir(if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
            val format = YtDlpRunner.Format(
                formatId    = formatId,
                label       = formatLbl,
                height      = null,
                ext         = ext,
                isAudioOnly = isAudio,
                filesize    = null,
            )

            val result = runner.download(url, stagingDir, format, processId) { pct, speed, eta ->
                updateNotification(title, pct, speed)
                sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
                    putExtra(EXTRA_PCT, pct)
                    putExtra(EXTRA_SPEED, speed)
                    putExtra(EXTRA_ETA, eta)
                })
            }

            var publishedUri: String? = null
            var finalPath: String? = result.filePath

            if (result.success && result.filePath != null) {
                val mimeType = mimeTypeFor(ext, isAudio)
                val kind = if (isAudio) MediaStoreHelper.MediaKind.AUDIO else MediaStoreHelper.MediaKind.VIDEO
                publishedUri = MediaStoreHelper.publish(this@DownloadService, File(result.filePath), kind, mimeType)
                if (publishedUri == null) {
                    // Yayınlama başarısız oldu ama dosya staging dizininde duruyor;
                    // kullanıcıya en azından hata bilgisini verelim.
                    finalPath = result.filePath
                }
            }

            if (!result.cancelled) {
                db.downloadDao().insert(
                    DownloadEntity(
                        url        = url,
                        title      = title,
                        thumbnail  = thumb,
                        format     = formatLbl,
                        ext        = ext,
                        filePath   = finalPath,
                        status     = if (result.success) "completed" else "failed",
                        uploader   = null,
                        contentUri = publishedUri,
                    )
                )
            }

            if (!result.cancelled) {
                sendBroadcast(Intent(if (result.success) BROADCAST_DONE else BROADCAST_ERROR).apply {
                    putExtra(EXTRA_SUCCESS, result.success)
                    putExtra(EXTRA_FILE, finalPath)
                    putExtra(EXTRA_ERR_MSG, result.error)
                })
            }

            currentProcessId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ─── Fotoğraf indirme (düz HTTP) ────────────────────────────────────────────

    private data class ImageDownloadResult(
        val success: Boolean,
        val filePath: String? = null,
        val mime: String? = null,
        val ext: String = "jpg",
        val error: String? = null,
    )

    private fun startImageDownload(url: String, title: String) {
        currentProcessId = null
        currentJob = scope.launch {
            val result = downloadImageFile(url) { pct ->
                updateNotification(title, pct, "")
                sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
                    putExtra(EXTRA_PCT, pct)
                    putExtra(EXTRA_SPEED, "")
                    putExtra(EXTRA_ETA, "")
                })
            }

            var publishedUri: String? = null
            var finalPath: String? = result.filePath

            if (result.success && result.filePath != null) {
                publishedUri = MediaStoreHelper.publish(
                    this@DownloadService, File(result.filePath),
                    MediaStoreHelper.MediaKind.IMAGE, result.mime ?: "image/jpeg"
                )
                if (publishedUri == null) finalPath = result.filePath
            }

            db.downloadDao().insert(
                DownloadEntity(
                    url        = url,
                    title      = title,
                    thumbnail  = url,
                    format     = "Fotoğraf",
                    ext        = result.ext,
                    filePath   = finalPath,
                    status     = if (result.success) "completed" else "failed",
                    uploader   = null,
                    contentUri = publishedUri,
                )
            )

            sendBroadcast(Intent(if (result.success) BROADCAST_DONE else BROADCAST_ERROR).apply {
                putExtra(EXTRA_SUCCESS, result.success)
                putExtra(EXTRA_FILE, finalPath)
                putExtra(EXTRA_ERR_MSG, result.error)
            })

            currentJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun downloadImageFile(
        url: String, onProgress: (Int) -> Unit
    ): ImageDownloadResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) VortexAndroid/1.0")
            }
            connection.connect()

            if (connection.responseCode !in 200..299) {
                return@withContext ImageDownloadResult(false, error = "HTTP ${connection.responseCode}")
            }

            val mime = connection.contentType?.substringBefore(";")?.trim() ?: "image/jpeg"
            val ext = when {
                mime.contains("png")  -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("gif")  -> "gif"
                else -> "jpg"
            }

            val total = connection.contentLengthLong
            val stagingDir = getStagingDir(Environment.DIRECTORY_PICTURES)
            val dest = File(stagingDir, "vortex_${System.currentTimeMillis()}.$ext")

            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(16 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        ensureActive() // iptal edildiyse burada durur
                        out.write(buf, 0, n)
                        downloaded += n
                        val pct = if (total > 0) {
                            ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        } else {
                            // Sunucu Content-Length göndermediyse (chunked transfer —
                            // birçok CDN/fotoğraf bağlantısında olur) yüzde hesaplanamaz
                            // ve ilerleme çubuğu hiç kıpırdamadan takılı kalırdı. İndirilen
                            // veri miktarına göre artan, 95'te kilitlenip bitince 100'e
                            // atlayan "sahte" bir ilerleme üretiyoruz — en azından kullanıcı
                            // bir şeylerin ilerlediğini görür.
                            (downloaded / (50 * 1024)).toInt().coerceIn(0, 95)
                        }
                        onProgress(pct)
                    }
                }
            }

            ImageDownloadResult(true, dest.absolutePath, mime, ext)
        } catch (e: CancellationException) {
            ImageDownloadResult(false, error = "İptal edildi")
        } catch (e: Exception) {
            ImageDownloadResult(false, error = e.message ?: "Bilinmeyen hata")
        } finally {
            connection?.disconnect()
        }
    }

    // ─── Ortak yardımcılar ──────────────────────────────────────────────────────

    /** Uygulamaya özel, hiçbir izin gerektirmeyen geçici indirme dizini. */
    private fun getStagingDir(dirType: String): File {
        val base = getExternalFilesDir(dirType) ?: filesDir
        return File(base, "staging").also { it.mkdirs() }
    }

    private fun mimeTypeFor(ext: String, isAudio: Boolean): String = when {
        isAudio && ext == "mp3" -> "audio/mpeg"
        isAudio && ext == "m4a" -> "audio/mp4"
        isAudio -> "audio/*"
        else -> "video/mp4"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Vortex İndirme",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "İndirme bildirimleri" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, pct: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VORTEX")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_cancel, "İptal", cancelIntent)
            .build()
    }

    private fun updateNotification(title: String, pct: Int, speed: String) {
        val text = if (speed.isNotBlank()) "$pct%  •  $speed" else "$pct%"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, pct))
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
