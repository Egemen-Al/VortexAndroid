package com.vortex.downloader.util

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView öğelerine tıklama/uzun basma eklemenin alternatif yolu.
 *
 * Normalde her ViewHolder'ın kendi `itemView`'ine `setOnClickListener`
 * konur ve bu genelde çalışır. Ancak bazı cihaz/ROM + CardView +
 * RecyclerView kombinasyonlarında (bu projede tam olarak yaşandığı gibi:
 * `setOnClickListener`, `setOnLongClickListener`, hatta ham
 * `setOnTouchListener` bile item view üzerinde hiç tetiklenmiyor —
 * RecyclerView dokunmayı görüyor ama item'a hiç düşmüyor) item
 * seviyesindeki listener'lar sessizce hiç çağrılmayabiliyor.
 *
 * Bu sınıf, dokunmayı doğrudan RecyclerView seviyesinde
 * (`addOnItemTouchListener`) yakalayıp `findChildViewUnder` ile dokunulan
 * satırı bulur ve tıklama/uzun basmayı kendisi tetikler — item view'lerin
 * kendi listener zincirine hiç ihtiyaç duymaz, bu yüzden çok daha güvenilirdir.
 */
class RecyclerItemClickListener(
    context: Context,
    private val recyclerView: RecyclerView,
    private val onItemClick: (view: View, position: Int) -> Unit,
    private val onItemLongClick: ((view: View, position: Int) -> Unit)? = null,
) : RecyclerView.OnItemTouchListener {

    private val gestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return false
                val position = recyclerView.getChildAdapterPosition(child)
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(child, position)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return
                val position = recyclerView.getChildAdapterPosition(child)
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick?.invoke(child, position)
                }
            }
        })

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(e)
        // Asla intercept etme: normal kaydırma/ripple davranışı bozulmasın,
        // biz sadece "dinliyoruz", tıklamayı kendimiz elle tetikliyoruz.
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
}
