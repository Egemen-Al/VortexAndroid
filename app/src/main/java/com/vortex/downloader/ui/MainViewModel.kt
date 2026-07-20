package com.vortex.downloader.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vortex.downloader.data.db.DownloadEntity
import com.vortex.downloader.data.db.VortexDatabase
import com.vortex.downloader.service.DownloadService
import com.vortex.downloader.util.BinaryManager
import com.vortex.downloader.util.YtDlpRunner
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ── State ────────────────────────────────────────────────────────────────

    enum class SetupState { IDLE, DOWNLOADING_BINS, READY, ERROR }
    data class DownloadProgress(val pct: Int, val speed: String, val eta: String)

    private val _setupState   = MutableLiveData(SetupState.IDLE)
    val setupState: LiveData<SetupState> = _setupState

    private val _setupLabel   = MutableLiveData("Başlatılıyor…")
    val setupLabel: LiveData<String> = _setupLabel

    private val _setupProgress = MutableLiveData(0f)
    val setupProgress: LiveData<Float> = _setupProgress

    private val _videoInfo    = MutableLiveData<YtDlpRunner.VideoInfo?>()
    val videoInfo: LiveData<YtDlpRunner.VideoInfo?> = _videoInfo

    private val _infoLoading  = MutableLiveData(false)
    val infoLoading: LiveData<Boolean> = _infoLoading

    // yt-dlp bir URL'i doğrudan tanıyamadığında (genel bir sayfa), bu URL'i
    // taşır — HomeFragment bunu görünce sayfa taramasını teklif eder.
    private val _analyzeFailedUrl = MutableLiveData<String?>()
    val analyzeFailedUrl: LiveData<String?> = _analyzeFailedUrl

    private val _dlProgress   = MutableLiveData<DownloadProgress?>()
    val dlProgress: LiveData<DownloadProgress?> = _dlProgress

    private val _dlDone       = MutableLiveData<Boolean?>()
    val dlDone: LiveData<Boolean?> = _dlDone

    private val _dlError      = MutableLiveData<String?>()
    val dlError: LiveData<String?> = _dlError

    private val _isDownloading = MutableLiveData(false)
    val isDownloading: LiveData<Boolean> = _isDownloading

    // ── Deps ─────────────────────────────────────────────────────────────────

    private val binaryManager = BinaryManager(app)
    private val runner        = YtDlpRunner(binaryManager, app)
    val history: LiveData<List<DownloadEntity>> =
        VortexDatabase.getInstance(app).downloadDao().getAll()

    // ── Broadcast receiver ────────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                DownloadService.BROADCAST_PROGRESS -> {
                    _dlProgress.postValue(DownloadProgress(
                        pct   = intent.getIntExtra(DownloadService.EXTRA_PCT, 0),
                        speed = intent.getStringExtra(DownloadService.EXTRA_SPEED) ?: "",
                        eta   = intent.getStringExtra(DownloadService.EXTRA_ETA) ?: "",
                    ))
                }
                DownloadService.BROADCAST_DONE -> {
                    _isDownloading.postValue(false)
                    _dlProgress.postValue(DownloadProgress(100, "", ""))
                    _dlDone.postValue(true)
                }
                DownloadService.BROADCAST_ERROR -> {
                    _isDownloading.postValue(false)
                    _dlError.postValue(
                        intent.getStringExtra(DownloadService.EXTRA_ERR_MSG) ?: "Bilinmeyen hata"
                    )
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(DownloadService.BROADCAST_PROGRESS)
            addAction(DownloadService.BROADCAST_DONE)
            addAction(DownloadService.BROADCAST_ERROR)
        }
        // NOT: Context.registerReceiver(receiver, filter, flags) 3 parametreli
        // overload'u API 33 (Tiramisu) gerektirir; minSdk 26 olduğundan API 26-32
        // arası cihazlarda bu doğrudan çağrı NoSuchMethodError/derleme hatasına
        // yol açardı. ContextCompat.registerReceiver tüm minSdk aralığında güvenli.
        ContextCompat.registerReceiver(
            app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        checkAndSetup()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    fun checkAndSetup() {
        if (binaryManager.isReady()) {
            _setupState.postValue(SetupState.READY)
            return
        }
        _setupState.postValue(SetupState.DOWNLOADING_BINS)
        viewModelScope.launch {
            val result = binaryManager.ensureReady { label, progress ->
                _setupLabel.postValue(label)
                _setupProgress.postValue(progress)
            }
            when (result) {
                is BinaryManager.SetupResult.Ready ->
                    _setupState.postValue(SetupState.READY)
                is BinaryManager.SetupResult.Error -> {
                    _setupState.postValue(SetupState.ERROR)
                    _setupLabel.postValue(result.message)
                }
            }
        }
    }

    // ── Info Fetch ─────────────────────────────────────────────────────────────

    fun fetchInfo(url: String) {
        _videoInfo.value = null
        _infoLoading.value = true
        _analyzeFailedUrl.value = null
        viewModelScope.launch {
            val trimmed = url.trim()
            val info = runner.getInfo(trimmed)
            _videoInfo.postValue(info)
            _infoLoading.postValue(false)
            if (info == null) _analyzeFailedUrl.postValue(trimmed)
        }
    }

    fun consumeAnalyzeFailed() { _analyzeFailedUrl.value = null }

    fun clearInfo() { _videoInfo.value = null }

    // ── Download ──────────────────────────────────────────────────────────────

    fun startDownload(
        ctx: Context,
        url: String,
        format: YtDlpRunner.Format,
        title: String,
        thumb: String?,
    ) {
        _isDownloading.value = true
        _dlProgress.value    = DownloadProgress(0, "", "")
        _dlDone.value        = null
        _dlError.value       = null

        val intent = Intent(ctx, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL,        url)
            putExtra(DownloadService.EXTRA_FORMAT_ID,  format.formatId)
            putExtra(DownloadService.EXTRA_FORMAT_LBL, format.label)
            putExtra(DownloadService.EXTRA_EXT,        format.ext)
            putExtra(DownloadService.EXTRA_IS_AUDIO,   format.isAudioOnly)
            putExtra(DownloadService.EXTRA_TITLE,      title)
            putExtra(DownloadService.EXTRA_THUMB,      thumb)
        }
        ctx.startForegroundService(intent)
    }

    /** Çoklu seçimle indirilen bir videoyu, format seçim ekranını atlayarak
     * otomatik olarak en iyi kalite ile indirir (toplu indirme kuyruğu için). */
    fun startBestDownload(ctx: Context, url: String) {
        viewModelScope.launch {
            val info = runner.getInfo(url)
            if (info == null) {
                _dlError.postValue("Video bilgisi alınamadı")
                return@launch
            }
            val format = info.formats.firstOrNull { !it.isAudioOnly } ?: info.formats.firstOrNull()
            if (format == null) {
                _dlError.postValue("Uygun format bulunamadı")
                return@launch
            }
            startDownload(ctx, url, format, info.title, info.thumbnail)
        }
    }

    /** Sayfa taramasında bulunan bir fotoğrafı doğrudan indirir (format seçimi gerekmez). */
    fun downloadImage(ctx: Context, url: String, title: String) {
        _isDownloading.value = true
        _dlProgress.value    = DownloadProgress(0, "", "")
        _dlDone.value        = null
        _dlError.value       = null

        val intent = Intent(ctx, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD_IMAGE
            putExtra(DownloadService.EXTRA_URL,   url)
            putExtra(DownloadService.EXTRA_TITLE, title)
        }
        ctx.startForegroundService(intent)
    }

    fun cancelDownload(ctx: Context) {
        ctx.startService(Intent(ctx, DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL
        })
        _isDownloading.value = false
    }

    fun consumeDone()  { _dlDone.value  = null }
    fun consumeError() { _dlError.value = null }

    override fun onCleared() {
        super.onCleared()
        try { getApplication<Application>().unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
