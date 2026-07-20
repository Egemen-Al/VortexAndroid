package com.vortex.downloader.ui.home

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.*
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vortex.downloader.R
import com.vortex.downloader.databinding.FragmentHomeBinding
import com.vortex.downloader.ui.MainViewModel
import com.vortex.downloader.util.CookieManager
import com.vortex.downloader.util.PageVideoScanner
import com.vortex.downloader.util.YtDlpRunner
import android.webkit.CookieManager as WebCookieManager

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var selectedFormat: YtDlpRunner.Format? = null
    private var currentUrl: String = ""

    // Çoklu seçimle indirilecek öğeler tek tek sıraya alınır (aynı anda tek bir
    // indirme servisi çalıştığından, ardışık olarak işlenir).
    private val downloadQueue = ArrayDeque<PageVideoScanner.FoundVideo>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requestNotifPermission()

        // Tarayıcıdan gelen URL
        parentFragmentManager.setFragmentResultListener("shared_url", viewLifecycleOwner) { _, bundle ->
            val url = bundle.getString("url") ?: return@setFragmentResultListener
            binding.etUrl.setText(url)
            fetchInfo(url)
        }

        // Ara butonu / keyboard done
        binding.btnSearch.setOnClickListener { fetchInfo(binding.etUrl.text.toString()) }
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                fetchInfo(binding.etUrl.text.toString()); true
            } else false
        }

        // Paste & Fetch butonu
        binding.btnPaste.setOnClickListener {
            val clipboard = requireContext().getSystemService(android.content.ClipboardManager::class.java)
            val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.startsWith("http")) {
                binding.etUrl.setText(text)
                fetchInfo(text)
            } else {
                Toast.makeText(requireContext(), "Panoda geçerli bir URL yok", Toast.LENGTH_SHORT).show()
            }
        }

        // İndir butonu
        binding.btnDownload.setOnClickListener { startDownload() }

        // İptal
        binding.btnCancel.setOnClickListener {
            viewModel.cancelDownload(requireContext())
            resetDownloadUI()
        }

        // Çerez ile giriş (Instagram/TikTok gibi girişte kimlik doğrulama
        // isteyen sitelerdeki içeriklerin yt-dlp tarafından doğrudan tanınması için).
        // Kullanıcı dosyayla uğraşmaz: uygulama içinde açılan bir tarayıcıda
        // normal şekilde giriş yapar, çerezler otomatik olarak yakalanıp kaydedilir.
        binding.btnCookies.setOnClickListener { showCookieLoginDialog() }

        // Observers
        viewModel.infoLoading.observe(viewLifecycleOwner) { loading ->
            binding.progressInfo.visibility = if (loading) View.VISIBLE else View.GONE
            binding.btnSearch.isEnabled = !loading
        }

        viewModel.videoInfo.observe(viewLifecycleOwner) { info ->
            if (info == null) {
                binding.cardInfo.visibility = View.GONE
                binding.cardFormats.visibility = View.GONE
                binding.btnDownload.visibility = View.GONE
            } else {
                showVideoInfo(info)
            }
        }

        viewModel.dlProgress.observe(viewLifecycleOwner) { prog ->
            if (prog == null) return@observe
            // setProgressCompat(.., true): sert bir sıçrama yerine akıcı bir dolum animasyonu.
            binding.progressDownload.setProgressCompat(prog.pct, true)
            binding.tvPct.text = "${prog.pct}%"
            binding.tvSpeed.text = if (prog.speed.isNotBlank()) "⚡ ${prog.speed}" else ""
            binding.tvEta.text = if (prog.eta.isNotBlank()) "⏱ ${prog.eta}" else ""
        }

        viewModel.isDownloading.observe(viewLifecycleOwner) { dl ->
            if (dl) {
                revealView(binding.cardProgress)
                binding.btnDownload.visibility = View.GONE
                binding.btnCancel.visibility   = View.VISIBLE
            } else {
                binding.cardProgress.visibility = View.GONE
                binding.btnCancel.visibility     = View.GONE
                if (selectedFormat != null) binding.btnDownload.visibility = View.VISIBLE
            }
        }

        viewModel.dlDone.observe(viewLifecycleOwner) { done ->
            if (done == true) {
                playSuccessPop()
                Toast.makeText(requireContext(), "✅ İndirme tamamlandı!", Toast.LENGTH_SHORT).show()
                resetDownloadUI()
                viewModel.consumeDone()
                processNextInQueue()
            }
        }

        viewModel.dlError.observe(viewLifecycleOwner) { err ->
            if (err != null) {
                Toast.makeText(requireContext(), "❌ Hata: $err", Toast.LENGTH_LONG).show()
                resetDownloadUI()
                viewModel.consumeError()
                processNextInQueue()
            }
        }

        // yt-dlp URL'i doğrudan tanıyamadıysa (genel bir sayfa olabilir) —
        // sayfayı tarayıp içindeki video/oynatıcıları bulmayı teklif et.
        viewModel.analyzeFailedUrl.observe(viewLifecycleOwner) { failedUrl ->
            if (failedUrl != null) {
                offerPageScan(failedUrl)
                viewModel.consumeAnalyzeFailed()
            }
        }
    }

    /** Uygulama içinde bir WebView açıp kullanıcının Instagram/TikTok gibi bir
     * sitede normal şekilde giriş yapmasını sağlar. Giriş tamamlanınca "Kaydet"e
     * basılması yeterli — çerezler otomatik olarak okunup cookies.txt'ye yazılır,
     * kullanıcının hiçbir dosya seçmesi/aktarması gerekmez. */
    private fun showCookieLoginDialog() {
        val ctx = requireContext()
        val startUrl = "https://www.instagram.com/accounts/login/"

        WebCookieManager.getInstance().setAcceptCookie(true)

        val webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        WebCookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val toolbar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 32, 24, 16)
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.bg_card))
        }
        val title = TextView(ctx).apply {
            text = "Giriş yapın, bitince Kaydet'e basın"
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }
        val saveBtn = MaterialButton(ctx).apply {
            text = "Kaydet"
            setTextColor(ContextCompat.getColor(ctx, R.color.accent))
        }
        val closeBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "✕"
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
        toolbar.addView(title)
        toolbar.addView(saveBtn)
        toolbar.addView(closeBtn)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.bg_dark))
            addView(toolbar)
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        saveBtn.setOnClickListener {
            WebCookieManager.getInstance().flush()
            val currentUrlInWebView = webView.url ?: startUrl
            val ok = CookieManager.saveFromWebView(ctx, currentUrlInWebView)
            Toast.makeText(
                ctx,
                if (ok) "✅ Giriş kaydedildi, artık bu tür siteler doğrudan çalışacak"
                else "❌ Çerez bulunamadı — önce sitede giriş yaptığınızdan emin olun",
                Toast.LENGTH_LONG
            ).show()
            if (ok) dialog.dismiss()
        }
        closeBtn.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(container)
        webView.loadUrl(startUrl)
        dialog.show()
    }

    /** yt-dlp'nin doğrudan tanımadığı bir URL için sayfa taramayı teklif eder. */
    private fun offerPageScan(url: String) {
        if (_binding == null) return
        Snackbar.make(binding.root, "Bu bağlantıda video bulunamadı", Snackbar.LENGTH_LONG)
            .setAction("Sayfayı Tara") { scanPageForVideos(url) }
            .show()
    }

    private fun scanPageForVideos(url: String) {
        if (_binding == null) return
        binding.progressInfo.visibility = View.VISIBLE
        Toast.makeText(requireContext(), "Sayfa taranıyor…", Toast.LENGTH_SHORT).show()

        PageVideoScanner.scan(requireContext(), url) { results ->
            if (_binding == null) return@scan
            binding.progressInfo.visibility = View.GONE

            if (results.isEmpty()) {
                Toast.makeText(requireContext(), "Sayfada indirilebilir içerik bulunamadı", Toast.LENGTH_LONG).show()
                return@scan
            }

            if (results.size == 1) {
                val only = results.first()
                Toast.makeText(requireContext(), "${only.label} bulundu, indiriliyor…", Toast.LENGTH_SHORT).show()
                pickScannedVideo(only)
                return@scan
            }

            // Başlığı bulunanların gerçek türüne göre yaz (hepsi fotoğrafsa "video"
            // demek yanıltıcı oluyordu).
            val videoCount = results.count { it.kind != "image" }
            val imageCount = results.count { it.kind == "image" }
            val title = when {
                videoCount > 0 && imageCount > 0 -> "Sayfada $videoCount video, $imageCount fotoğraf bulundu"
                videoCount > 0 -> "Sayfada $videoCount video bulundu"
                else -> "Sayfada $imageCount fotoğraf bulundu"
            }

            // Çoklu seçim: kullanıcı birden fazla öğe işaretleyip hepsini
            // birden indirebilir (sırayla, kuyruğa alınarak).
            val checked = BooleanArray(results.size)
            val labels = results.mapIndexed { i, r -> "${r.label}\n${shorten(r.url)}" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("İndir") { _, _ ->
                    val selected = results.filterIndexed { i, _ -> checked[i] }
                    if (selected.isEmpty()) {
                        Toast.makeText(requireContext(), "Hiçbir öğe seçilmedi", Toast.LENGTH_SHORT).show()
                    } else {
                        startBulkDownload(selected)
                    }
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
    }

    /** Tek bir sonuç bulunduğunda (tarama tek öğe döndüğünde) kullanılan akış:
     * video ise mevcut format-seçim ekranını gösterir, foto ise doğrudan indirir. */
    private fun pickScannedVideo(found: PageVideoScanner.FoundVideo) {
        if (found.kind == "image") {
            val fileName = found.url.substringAfterLast('/').substringBefore('?')
                .ifBlank { "fotoğraf" }
            viewModel.downloadImage(requireContext(), found.url, fileName)
        } else {
            binding.etUrl.setText(found.url)
            fetchInfo(found.url)
        }
    }

    /** Çoklu seçimde her öğeyi kuyruğa alır ve sırayla işler — video ise format
     * seçim ekranını atlayıp otomatik en iyi kaliteyle indirir. */
    private fun startBulkDownload(items: List<PageVideoScanner.FoundVideo>) {
        downloadQueue.clear()
        downloadQueue.addAll(items)
        Toast.makeText(requireContext(), "${items.size} öğe indirme kuyruğuna eklendi", Toast.LENGTH_SHORT).show()
        processNextInQueue()
    }

    private fun processNextInQueue() {
        if (downloadQueue.isEmpty()) return
        if (viewModel.isDownloading.value == true) return
        val next = downloadQueue.removeFirst()
        if (next.kind == "image") {
            val fileName = next.url.substringAfterLast('/').substringBefore('?').ifBlank { "fotoğraf" }
            viewModel.downloadImage(requireContext(), next.url, fileName)
        } else {
            viewModel.startBestDownload(requireContext(), next.url)
        }
    }

    private fun shorten(url: String): String =
        if (url.length > 60) url.take(57) + "…" else url

    private fun fetchInfo(url: String) {
        if (url.isBlank() || !url.startsWith("http")) {
            Toast.makeText(requireContext(), "Geçerli bir URL girin", Toast.LENGTH_SHORT).show()
            return
        }
        currentUrl = url.trim()
        hideKeyboard()
        viewModel.fetchInfo(currentUrl)
    }

    private fun showVideoInfo(info: YtDlpRunner.VideoInfo) {
        // Thumbnail
        revealView(binding.cardInfo)
        Glide.with(this).load(info.thumbnail).placeholder(R.drawable.ic_video_placeholder)
            .into(binding.ivThumbnail)
        binding.tvTitle.text = info.title
        binding.tvUploader.text = info.uploader ?: ""
        val mins = info.duration / 60
        val secs = info.duration % 60
        binding.tvDuration.text = if (info.duration > 0) "%d:%02d".format(mins, secs) else ""

        // Format chip'leri
        revealView(binding.cardFormats)
        binding.chipGroupFormats.removeAllViews()
        selectedFormat = null

        // Kategoriler: Video / Audio
        var firstChip = true
        info.formats.forEach { format ->
            val chip = Chip(requireContext()).apply {
                text = format.label
                isCheckable = true
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    if (format.isAudioOnly)
                        ContextCompat.getColor(requireContext(), R.color.chip_audio_bg)
                    else
                        ContextCompat.getColor(requireContext(), R.color.chip_video_bg)
                )
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                chipStrokeWidth = 0f
            }
            chip.setOnClickListener {
                selectedFormat = format
                bounceView(chip)
                binding.btnDownload.visibility = View.VISIBLE
                binding.btnDownload.text = "⬇  ${format.label} İndir"
            }
            binding.chipGroupFormats.addView(chip)
            // İlk chip'i otomatik seç (en yüksek kalite video)
            if (firstChip && !format.isAudioOnly) {
                chip.isChecked = true
                selectedFormat = format
                binding.btnDownload.visibility = View.VISIBLE
                binding.btnDownload.text = "⬇  ${format.label} İndir"
                firstChip = false
            }
        }
    }

    private fun startDownload() {
        val format = selectedFormat ?: run {
            Toast.makeText(requireContext(), "Bir format seçin", Toast.LENGTH_SHORT).show()
            return
        }
        val info = viewModel.videoInfo.value ?: return
        viewModel.startDownload(
            ctx    = requireContext(),
            url    = currentUrl,
            format = format,
            title  = info.title,
            thumb  = info.thumbnail,
        )
    }

    private fun resetDownloadUI() {
        binding.progressDownload.setProgressCompat(0, false)
        binding.tvPct.text = "0%"
        binding.tvSpeed.text = ""
        binding.tvEta.text = ""
    }

    /** Bir kartı sert bir şekilde göstermek yerine hafifçe yukarıdan kayarak/soluklaşarak belirtir. */
    private fun revealView(view: View) {
        if (view.visibility == View.VISIBLE && view.alpha > 0.9f) return
        view.alpha = 0f
        view.translationY = 32f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /** Bir chip/butona dokununca ufak bir "zıplama" — dokunuşun hissedilmesini sağlar. */
    private fun bounceView(view: View) {
        view.animate().cancel()
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        view.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    /** İndirme bitince kısaca beliren yeşil onay ikonu. */
    private fun playSuccessPop() {
        if (_binding == null) return
        val icon = binding.ivSuccess
        icon.animate().cancel()
        icon.scaleX = 0.4f
        icon.scaleY = 0.4f
        icon.alpha = 0f
        icon.visibility = View.VISIBLE
        icon.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                if (_binding == null) return@withEndAction
                icon.animate()
                    .setStartDelay(500)
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction { if (_binding != null) icon.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
