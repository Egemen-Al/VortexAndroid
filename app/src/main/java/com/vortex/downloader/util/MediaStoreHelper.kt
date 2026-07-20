package com.vortex.downloader.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * İndirilen dosyayı cihazın genel Movies/Vortex ya da Music/Vortex koleksiyonuna
 * yayınlar.
 *
 * Eski implementasyon `Environment.getExternalStoragePublicDirectory` ile
 * doğrudan dosya yazıp `MediaScannerConnection.scanFile` çağırıyordu. Android
 * 10+ (API 29+) scoped storage altında bu, WRITE_EXTERNAL_STORAGE izni
 * olmadan (ki manifest'te zaten API 29'dan sonrası için tanımlı değildi)
 * genelde başarısız olur. Bu sınıf, API 29+ için MediaStore API'sini
 * kullanarak dosyayı doğru şekilde yayınlar; API 28 ve altında ise eski
 * doğrudan dosya yazma yolunu (izin gerektirmeden, çünkü bu API'lerde
 * WRITE_EXTERNAL_STORAGE zaten tanımlıydı) kullanmaya devam eder.
 */
object MediaStoreHelper {

    private const val TAG = "MediaStoreHelper"

    enum class MediaKind { VIDEO, AUDIO, IMAGE }

    /**
     * [source] dosyasını genel koleksiyona kopyalar, kaynağı siler ve
     * yayınlanan içeriğin URI'sini (String olarak saklanabilir) döner.
     * Başarısız olursa null döner (dosya `source` konumunda kalır).
     */
    fun publish(context: Context, source: File, kind: MediaKind, mimeType: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(context, source, kind, mimeType)
            } else {
                publishLegacy(context, source, kind)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dosya yayınlama hatası", e)
            null
        }
    }

    private fun collectionAndDirFor(kind: MediaKind): Pair<Uri, String> = when (kind) {
        MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Vortex"
        MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Vortex"
        MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Vortex"
    }

    private fun publicDirFor(kind: MediaKind): String = when (kind) {
        MediaKind.AUDIO -> Environment.DIRECTORY_MUSIC
        MediaKind.IMAGE -> Environment.DIRECTORY_PICTURES
        MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
    }

    private fun publishViaMediaStore(
        context: Context, source: File, kind: MediaKind, mimeType: String
    ): String? {
        val (collection, relativeDir) = collectionAndDirFor(kind)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null

        val copied = resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
            true
        } ?: false

        if (!copied) {
            resolver.delete(uri, null, null)
            return null
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        source.delete()
        return uri.toString()
    }

    private fun publishLegacy(context: Context, source: File, kind: MediaKind): String? {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(publicDirFor(kind)),
            "Vortex"
        ).also { it.mkdirs() }

        val dest = File(publicDir, source.name)
        source.copyTo(dest, overwrite = true)
        source.delete()

        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(dest.absolutePath), null, null
        )
        return Uri.fromFile(dest).toString()
    }

    /** Daha önce yayınlanmış bir dosyayı/kaydı siler. */
    fun delete(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content") {
                context.contentResolver.delete(uri, null, null)
            } else {
                uri.path?.let { File(it).delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dosya silme hatası", e)
        }
    }
}
