package com.vortex.downloader.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.URI
import android.webkit.CookieManager as WebCookieManager

/**
 * cookies.txt (Netscape formatı) dosyasını uygulama içinde saklar. yt-dlp bu
 * dosyayı --cookies parametresiyle kullanarak Instagram/TikTok gibi girişte
 * kimlik doğrulama isteyen sitelerdeki içerikleri (ör. Reels) doğrudan çekebilir.
 *
 * NOT: Eskiden kullanıcının tarayıcısından elle "cookies.txt" dışa aktarıp bu
 * dosyayı bir dosya seçiciyle içe aktarması gerekiyordu — bu, sıradan bir
 * kullanıcı için çok karmaşık bir akıştı. Artık kullanıcı uygulama içindeki bir
 * WebView'da doğrudan giriş yapıyor, çerezler android.webkit.CookieManager
 * üzerinden otomatik olarak okunup kaydediliyor; kullanıcının hiçbir dosyayla
 * uğraşması gerekmiyor.
 */
object CookieManager {
    private const val FILE_NAME = "cookies.txt"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun hasCookies(context: Context): Boolean {
        val f = file(context)
        return f.exists() && f.length() > 0
    }

    /** Kullanıcının seçtiği cookies.txt dosyasını uygulama deposuna kopyalar
     * (dosya seçiciyle manuel içe aktarma — artık kullanılmıyor ama geriye
     * dönük uyumluluk için tutuluyor). */
    fun import(context: Context, source: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(source)?.use { input ->
                file(context).outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Uygulama içi WebView'da yapılan bir girişten sonra, o site için tarayıcı
     * çerezlerini (android.webkit.CookieManager) okuyup Netscape formatında
     * cookies.txt olarak kaydeder. Kullanıcı hiçbir dosya işlemiyle uğraşmaz —
     * sadece WebView içinde normal şekilde giriş yapar. */
    fun saveFromWebView(context: Context, url: String): Boolean {
        return try {
            val cookieStr = WebCookieManager.getInstance().getCookie(url)
            if (cookieStr.isNullOrBlank()) return false

            val host = try { URI(url).host } catch (e: Exception) { null } ?: return false
            val domain = if (host.startsWith(".")) host else ".$host"
            // 1 yıl geçerli say (yt-dlp sadece süresi geçmemiş olmasını kontrol eder)
            val expiry = (System.currentTimeMillis() / 1000) + 60L * 60 * 24 * 365

            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            var count = 0
            cookieStr.split(";").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (name.isNotEmpty()) {
                        sb.append(domain).append("\tTRUE\t/\tTRUE\t")
                            .append(expiry).append('\t')
                            .append(name).append('\t')
                            .append(value).append('\n')
                        count++
                    }
                }
            }
            if (count == 0) return false
            file(context).writeText(sb.toString())
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}
