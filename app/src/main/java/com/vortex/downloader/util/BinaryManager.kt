package com.vortex.downloader.util

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * İlk açılışta yt-dlp ve ffmpeg'i hazırlar.
 *
 * ÖNEMLİ: Eski implementasyon GitHub'dan genel Linux (glibc) statik binary'leri
 * indirip ProcessBuilder ile çalıştırmaya çalışıyordu. Android bionic libc
 * kullandığından bu binary'ler cihazda çalışmıyordu (linker hatası / "not
 * executable"). Bunun yerine youtubedl-android + ffmpeg-kit kütüphaneleri
 * kullanılıyor: yt-dlp + gömülü Python yorumlayıcısı ve ffmpeg, APK içine
 * Android için gerçekten derlenmiş native (.so) binary olarak gömülüyor ve
 * hiçbir ağ indirmesi gerektirmeden `init()` ile açılıyor (unzip).
 */
class BinaryManager(private val context: Context) {

    companion object {
        private const val TAG = "BinaryManager"

        @Volatile
        private var ready = false
    }

    sealed class SetupResult {
        object Ready : SetupResult()
        data class Error(val message: String) : SetupResult()
    }

    /**
     * İlk kurulum. Progress callback: 0.0 - 1.0.
     * Kütüphane çağrıları idempotent'tir (zaten kuruluysa hemen döner),
     * bu yüzden her `checkAndSetup()` çağrısında güvenle tekrar çağrılabilir.
     */
    suspend fun ensureReady(
        onProgress: (label: String, progress: Float) -> Unit
    ): SetupResult = withContext(Dispatchers.IO) {
        try {
            onProgress("yt-dlp hazırlanıyor…", 0.2f)
            YoutubeDL.getInstance().init(context.applicationContext)

            onProgress("ffmpeg hazırlanıyor…", 0.7f)
            FFmpeg.getInstance().init(context.applicationContext)

            onProgress("Hazır!", 1f)
            ready = true
            SetupResult.Ready
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "Kurulum hatası", e)
            ready = false
            SetupResult.Error(e.message ?: "yt-dlp/ffmpeg kurulamadı")
        } catch (e: Exception) {
            Log.e(TAG, "Beklenmeyen kurulum hatası", e)
            ready = false
            SetupResult.Error(e.message ?: "Bilinmeyen hata")
        }
    }

    fun isReady(): Boolean = ready

    /** yt-dlp'yi en son sürüme günceller (yt-dlp'nin kendi GitHub API'sinden). */
    suspend fun updateYtDlp(): Boolean = withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Güncelleme hatası", e)
            false
        }
    }
}
