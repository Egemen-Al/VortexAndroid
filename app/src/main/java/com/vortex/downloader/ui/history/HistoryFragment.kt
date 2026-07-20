package com.vortex.downloader.ui.history

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vortex.downloader.R
import com.vortex.downloader.data.db.DownloadEntity
import com.vortex.downloader.data.db.VortexDatabase
import com.vortex.downloader.databinding.FragmentHistoryBinding
import com.vortex.downloader.databinding.ItemHistoryBinding
import com.vortex.downloader.ui.MainViewModel
import com.vortex.downloader.util.MediaStoreHelper
import com.vortex.downloader.util.RecyclerItemClickListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryFragment : Fragment() {

    companion object { private const val TAG = "HistoryFragment" }

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val appContext = requireContext().applicationContext
        val adapter = HistoryAdapter()

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter

        // NOT: item view'lere doğrudan setOnClickListener/setOnLongClickListener
        // koymak bu cihaz/CardView kombinasyonunda hiç tetiklenmiyordu (RecyclerView
        // dokunmayı görüyor ama item'a hiç düşmüyordu). Bunun yerine dokunmayı
        // RecyclerView seviyesinde yakalayan, çok daha güvenilir bir yöntem kullanıyoruz.
        binding.rvHistory.addOnItemTouchListener(
            RecyclerItemClickListener(
                context = requireContext(),
                recyclerView = binding.rvHistory,
                onItemClick = { _, position ->
                    adapter.getItem(position)?.let { openDownload(it) }
                },
                onItemLongClick = { _, position ->
                    adapter.getItem(position)?.let { showDeleteDialog(it, appContext) }
                }
            )
        )

        // Kaydırarak silme — sağdan sola kaydırınca kırmızı zemin + çöp kutusu
        // ikonu belirir, bırakınca aynı onay diyalogu açılır (iptal edilirse
        // satır eski yerine geri döner).
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val entity = adapter.getItem(position)
                // Önce görünümü eski konumuna döndür — silme sadece onaydan sonra gerçekleşecek.
                adapter.notifyItemChanged(position)
                if (entity != null) showDeleteDialog(entity, appContext)
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, rv, viewHolder, dX, dY, actionState, isCurrentlyActive)
                val itemView = viewHolder.itemView
                if (dX >= 0) return

                val bg = ColorDrawable(Color.parseColor("#FF5370"))
                bg.setBounds(
                    itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom
                )
                bg.draw(c)

                val icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete) ?: return
                val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                val iconTop = itemView.top + iconMargin
                val iconLeft = itemView.right - iconMargin - icon.intrinsicWidth
                val iconRight = itemView.right - iconMargin
                val iconBottom = iconTop + icon.intrinsicHeight
                icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                icon.draw(c)
            }
        }).attachToRecyclerView(binding.rvHistory)

        viewModel.history.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        // İlk açılışta liste öğeleri sırayla (staggered) belirir.
        binding.rvHistory.scheduleLayoutAnimation()

        binding.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Tüm geçmişi temizle?")
                .setMessage("İndirilen tüm dosyalar da cihazdan silinecek.")
                .setPositiveButton("Temizle") { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val dao = VortexDatabase.getInstance(appContext).downloadDao()
                        dao.getAllOnce().forEach { MediaStoreHelper.delete(appContext, it.contentUri) }
                        dao.clearAll()
                    }
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }

    /** Uzun basınca sil — hem geçmiş kaydını hem de diskteki/galerideki gerçek
     * dosyayı sil (eskiden sadece Room kaydı siliniyor, dosya cihazda öylece
     * kalıyordu). */
    private fun showDeleteDialog(entity: DownloadEntity, appContext: android.content.Context) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Kaydı sil?")
            .setMessage("${entity.title}\n\nDosya da cihazdan silinecek.")
            .setPositiveButton("Sil") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    MediaStoreHelper.delete(appContext, entity.contentUri)
                    VortexDatabase.getInstance(appContext).downloadDao().delete(entity)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    /** Kayıtlı dosyayı, cihazdaki uygun uygulama (galeri/oynatıcı) ile açar. */
    private fun openDownload(entity: DownloadEntity) {
        Log.d(TAG, "openDownload: status=${entity.status} contentUri=${entity.contentUri} filePath=${entity.filePath}")

        if (entity.status != "completed") {
            Toast.makeText(requireContext(), "İndirme başarısız olduğu için dosya yok", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = resolveUri(entity)
        if (uri == null) {
            Toast.makeText(requireContext(), "Dosya bulunamadı (silinmiş olabilir)", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeFor(entity.ext))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // createChooser: tek uygulama olsa bile bir seçim ekranı garanti eder,
            // bu da "hiçbir şey olmuyor" izlenimini önler ve teşhisi kolaylaştırır.
            startActivity(Intent.createChooser(intent, entity.title))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Açacak uygulama bulunamadı: uri=$uri", e)
            Toast.makeText(requireContext(), "Bu dosyayı açacak bir uygulama bulunamadı", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Dosya açma hatası: uri=$uri", e)
            Toast.makeText(requireContext(), "Dosya açılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Önce kayıtlı content:// URI'sini dener (normal yol). Bu yoksa ya da
     * geçersizse (ör. eski bir kayıt, ya da yayınlama sırasında bir sorun
     * olduysa) `filePath`'teki dosyayı FileProvider ile paylaşılabilir bir
     * URI'ye çevirip onu döner. Dosya hiç bulunamazsa null döner.
     */
    private fun resolveUri(entity: DownloadEntity): Uri? {
        entity.contentUri?.takeIf { it.isNotBlank() }?.let {
            return try {
                Uri.parse(it)
            } catch (e: Exception) {
                Log.e(TAG, "contentUri parse edilemedi: $it", e)
                null
            }
        }

        val path = entity.filePath ?: return null
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "Dosya diskte bulunamadı: $path")
            return null
        }
        return try {
            FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file
            )
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider URI oluşturulamadı: $path", e)
            null
        }
    }

    private fun mimeTypeFor(ext: String): String = when (ext.lowercase(Locale.ROOT)) {
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else  -> "*/*"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─── Adapter ─────────────────────────────────────────────────────────────────
//
// NOT: Tıklama/uzun basma artık burada değil, HistoryFragment'te RecyclerView
// seviyesinde (RecyclerItemClickListener) yakalanıyor — bkz. yukarıdaki not.

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private var items: List<DownloadEntity> = emptyList()

    fun submitList(list: List<DownloadEntity>) {
        items = list
        notifyDataSetChanged()
    }

    fun getItem(position: Int): DownloadEntity? = items.getOrNull(position)

    inner class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding

        b.tvTitle.text  = item.title
        b.tvFormat.text = "${item.format}  •  .${item.ext}"
        b.ivStatus.setImageResource(
            if (item.status == "completed") R.drawable.ic_check_circle
            else R.drawable.ic_error
        )

        val date = SimpleDateFormat("dd MMM HH:mm", Locale("tr")).format(Date(item.createdAt))
        b.tvDate.text = date

        Glide.with(b.ivThumb.context)
            .load(item.thumbnail)
            .placeholder(R.drawable.ic_video_placeholder)
            .into(b.ivThumb)
    }
}
