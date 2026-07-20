package com.vortex.downloader.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONTokener

/**
 * yt-dlp bir URL'i doğrudan tanıyamadığında (ör. bir film/haber sitesi gibi
 * "genel" bir sayfa — kendisi video barındırmıyor ama içine bir oynatıcı
 * gömülü) son çare olarak kullanılan sayfa tarayıcı.
 *
 * Sayfayı görünmez bir WebView'de (JavaScript render dahil) açar, yüklenmesini
 * bekler, ardından sayfadaki `<video>`/`<source>`/`<iframe>` etiketlerini ve
 * ham HTML içindeki `.mp4`/`.m3u8` bağlantılarını toplar. Bulunanlar kullanıcıya
 * bir liste olarak sunulur; kullanıcı birini seçtiğinde o URL normal
 * analiz/indirme akışına (yt-dlp) tekrar verilir.
 */
object PageVideoScanner {

    data class FoundVideo(val url: String, val kind: String) {
        val label: String get() = when (kind) {
            "video" -> "🎞 Video dosyası"
            "image" -> "🖼 Fotoğraf"
            else    -> "▶ Gömülü oynatıcı"
        }
    }

    // Sayfalarda sıkça bulunan, video olmayan iframe'leri (reklam/analitik/widget) elemek için.
    private val IGNORED_HOST_FRAGMENTS = listOf(
        "doubleclick.", "googlesyndication", "google.com/recaptcha", "recaptcha",
        "facebook.com/plugins", "twitter.com/widgets", "disqus.com",
        "googletagmanager", "google-analytics", "adservice.", "/ads/", "googleads",
    )

    private const val SCAN_JS = """
        (function() {
            try {
                var results = [];
                document.querySelectorAll('video').forEach(function(v) {
                    if (v.src) results.push({url: v.src, kind: 'video'});
                    v.querySelectorAll('source').forEach(function(s) {
                        if (s.src) results.push({url: s.src, kind: 'video'});
                    });
                });
                document.querySelectorAll('iframe').forEach(function(f) {
                    if (f.src) results.push({url: f.src, kind: 'iframe'});
                });
                var html = document.documentElement.outerHTML;
                var re = /https?:\/\/[^"'\s<>]+\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?/g;
                var m;
                while ((m = re.exec(html)) !== null) results.push({url: m[0], kind: 'video'});

                // Fotoğraflar: küçük ikon/simgeleri elemek için minimum boyut şartı.
                document.querySelectorAll('img').forEach(function(im) {
                    if (im.src && im.src.indexOf('http') === 0 &&
                        im.naturalWidth >= 180 && im.naturalHeight >= 180) {
                        results.push({url: im.src, kind: 'image'});
                    }
                });
                var ogImage = document.querySelector('meta[property="og:image"]');
                if (ogImage && ogImage.content) results.push({url: ogImage.content, kind: 'image'});

                var seen = {};
                var unique = [];
                results.forEach(function(r) {
                    if (r.url && !seen[r.url]) { seen[r.url] = true; unique.push(r); }
                });
                return JSON.stringify(unique.slice(0, 60));
            } catch (e) { return '[]'; }
        })();
    """

    /** Callback ana thread'de çağrılır. */
    @SuppressLint("SetJavaScriptEnabled")
    fun scan(context: Context, url: String, onResult: (List<FoundVideo>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var finished = false
        var webView: WebView? = null

        fun finish(results: List<FoundVideo>) {
            if (finished) return
            finished = true
            webView?.let {
                it.stopLoading()
                it.destroy()
            }
            onResult(results)
        }

        main.post {
            val wv = WebView(context.applicationContext)
            webView = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0 Mobile Safari/537.36"

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    // Bazı sayfalar oynatıcıyı JS ile geç yüklüyor; kısa bir bekleme sonrası tara.
                    main.postDelayed({
                        if (finished) return@postDelayed
                        wv.evaluateJavascript(SCAN_JS) { raw -> finish(parseResults(raw)) }
                    }, 1200)
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    finish(emptyList())
                }
            }

            // Sayfa hiç yüklenmez/tamamlanmazsa diye zaman aşımı.
            main.postDelayed({ finish(emptyList()) }, 12_000)

            wv.loadUrl(url)
        }
    }

    private fun parseResults(raw: String?): List<FoundVideo> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return try {
            // evaluateJavascript sonucu bir JS string literali olarak (tırnaklı, escape'li) gelir.
            val jsonString = if (raw.startsWith("\"")) {
                JSONTokener(raw).nextValue().toString()
            } else raw
            val arr = JSONArray(jsonString)
            val list = mutableListOf<FoundVideo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val u = obj.optString("url")
                val kind = obj.optString("kind", "iframe")
                if (u.isBlank() || !u.startsWith("http")) continue
                if (kind == "iframe" && IGNORED_HOST_FRAGMENTS.any { u.contains(it, ignoreCase = true) }) continue
                list.add(FoundVideo(u, kind))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
