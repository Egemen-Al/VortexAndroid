package com.vortex.downloader.util

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.yausername.youtubedl_android.mapper.VideoInfo as LibVideoInfo

/**
 * yt-dlp'yi youtubedl-android kütüphanesi üzerinden çalıştırır.
 *
 * Eski implementasyon ham `ProcessBuilder` ile bir yt-dlp binary'sini
 * çalıştırıyor ve format listesini videodan bağımsız, sabit bir yükseklik
 * listesinden (2160/1440/1080/...) üretiyordu — bu da videoda gerçekte
 * bulunmayan kaliteleri gösterip indirme sırasında hataya yol açabiliyordu.
 * Artık gerçek format listesi `--dump-json` çıktısından (kütüphanenin
 * `VideoInfo.formats` alanı) okunuyor.
 */
class YtDlpRunner(private val binaryManager: BinaryManager, private val context: Context) {

    companion object {
        private const val TAG = "YtDlpRunner"
        private val SPEED_REGEX = Regex("""at\s+([\d.]+\w+/s)""")
        private val DEST_REGEX = Regex("""(?:Destination|Merging formats into):\s*"?([^"\n]+)"?""")
    }

    data class VideoInfo(
        val title: String,
        val thumbnail: String?,
        val duration: Long,
        val uploader: String?,
        val formats: List<Format>,
    )

    data class Format(
        val formatId: String,
        val label: String,       // "1080p", "MP3" vb.
        val height: Int?,
        val ext: String,
        val isAudioOnly: Boolean,
        val filesize: Long?,
    )

    data class DownloadResult(
        val success: Boolean,
        val filePath: String? = null,
        val error: String? = null,
        val cancelled: Boolean = false,
    )

    // ─── URL Bilgisi Al ───────────────────────────────────────────────────────

    suspend fun getInfo(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            applyCookies(request)
            val info = YoutubeDL.getInstance().getInfo(request)
            mapInfo(info)
        } catch (e: Exception) {
            Log.e(TAG, "getInfo hatası", e)
            null
        }
    }

    private fun mapInfo(info: LibVideoInfo): VideoInfo {
        return VideoInfo(
            title     = info.title ?: info.fulltitle ?: "Video",
            thumbnail = info.thumbnail,
            duration  = info.duration.toLong(),
            uploader  = info.uploader,
            formats   = buildFormats(info),
        )
    }

    private fun buildFormats(info: LibVideoInfo): List<Format> {
        val result = mutableListOf<Format>()
        val seenHeights = mutableSetOf<Int>()

        // Videonun GERÇEKTEN sahip olduğu yükseklikleri kullan (sabit liste değil)
        info.formats.orEmpty()
            .filter { f -> (f.height ?: 0) > 0 && !f.vcodec.isNullOrBlank() && f.vcodec != "none" }
            .sortedByDescending { it.height }
            .forEach { f ->
                val h = f.height
                if (h != null && h !in seenHeights) {
                    seenHeights.add(h)
                    val label = when {
                        h >= 2160 -> "4K"
                        h >= 1440 -> "1440p"
                        else -> "${h}p"
                    }
                    val size = f.fileSize.takeIf { it > 0 }
                        ?: f.fileSizeApproximate.takeIf { it > 0 }
                    result.add(
                        Format(
                            formatId    = "bestvideo[height<=$h]+bestaudio/best[height<=$h]",
                            label       = label,
                            height      = h,
                            ext         = "mp4",
                            isAudioOnly = false,
                            filesize    = size,
                        )
                    )
                }
            }

        // Hiçbir format çözümlenemediyse (bazı çıkarıcılarda height gelmeyebilir)
        if (result.isEmpty()) {
            result.add(Format("best", "En iyi kalite", null, "mp4", false, null))
        }

        // Ses formatları
        result.add(Format("bestaudio/best", "MP3", null, "mp3", true, null))
        result.add(Format("bestaudio[ext=m4a]/bestaudio/best", "M4A", null, "m4a", true, null))

        return result
    }

    // ─── İndir ───────────────────────────────────────────────────────────────

    suspend fun download(
        url: String,
        outDir: File,
        format: Format,
        processId: String,
        onProgress: (pct: Int, speed: String, eta: String) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            outDir.mkdirs()
            // Başlıkları 150 karakterle sınırla (bazı dosya sistemlerinde uzun
            // dosya adları ENAMETOOLONG hatası verir)
            val outputTemplate = File(outDir, "%(title).150B.%(ext)s").absolutePath

            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("-o", outputTemplate)
            applyCookies(request)

            if (format.isAudioOnly) {
                request.addOption("-x")
                request.addOption("--audio-format", format.ext)
                request.addOption("--audio-quality", "0")
                request.addOption("-f", format.formatId)
            } else {
                request.addOption("-f", format.formatId)
                request.addOption("--merge-output-format", "mp4")
            }

            var lastSpeed = ""
            val response = YoutubeDL.getInstance().execute(request, processId) { progress, etaSeconds, line ->
                val pct = progress.toInt().coerceIn(0, 100)
                SPEED_REGEX.find(line)?.groupValues?.get(1)?.let { lastSpeed = it }
                onProgress(pct, lastSpeed, formatEta(etaSeconds))
            }

            val file = findDownloadedFile(outDir, response.out)
            DownloadResult(success = true, filePath = file?.absolutePath)

        } catch (e: YoutubeDL.CanceledException) {
            DownloadResult(success = false, error = "İptal edildi", cancelled = true)
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "İndirme hatası", e)
            DownloadResult(success = false, error = e.message ?: "İndirme başarısız")
        } catch (e: Exception) {
            Log.e(TAG, "İndirme hatası", e)
            DownloadResult(success = false, error = e.message ?: "Bilinmeyen hata")
        }
    }

    /** Kullanıcı bir cookies.txt içe aktardıysa isteğe --cookies parametresini ekler.
     * Instagram/TikTok gibi girişte kimlik doğrulama isteyen sitelerin (ör. Reels)
     * yt-dlp tarafından doğrudan tanınabilmesi için gerekir. */
    private fun applyCookies(request: YoutubeDLRequest) {
        if (CookieManager.hasCookies(context)) {
            request.addOption("--cookies", CookieManager.file(context).absolutePath)
        }
    }

    /** Devam eden bir indirmeyi gerçekten öldürür (alttaki process dahil). */
    fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }

    private fun findDownloadedFile(dir: File, stdout: String): File? {
        val path = DEST_REGEX.findAll(stdout).lastOrNull()?.groupValues?.get(1)?.trim()
        if (path != null) {
            val f = File(path)
            if (f.exists()) return f
        }
        // fallback: dizindeki en son değiştirilen dosya
        return dir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
    }
}
