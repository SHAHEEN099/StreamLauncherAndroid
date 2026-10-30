package com.vedstudy.streamlauncher

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PdfThumbnailsAdapter(
    private val pdfRenderer: PdfRenderer,
    private val scope: CoroutineScope,
    private val activePage: Int,
    private val onPageSelected: (Int) -> Unit
) : RecyclerView.Adapter<PdfThumbnailsAdapter.ThumbnailViewHolder>() {

    private val renderMutex = Mutex()
    private val thumbCache = LruCache<Int, Bitmap>(60)

    override fun getItemCount(): Int = pdfRenderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_thumbnail, parent, false)
        return ThumbnailViewHolder(view)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onViewRecycled(holder: ThumbnailViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelJob()
    }

    inner class ThumbnailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val border: FrameLayout = itemView.findViewById(R.id.thumbnailBorder)
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val pbThumb: ProgressBar = itemView.findViewById(R.id.pbThumbnail)
        private val tvPage: TextView = itemView.findViewById(R.id.tvThumbnailPageNum)
        private var job: Job? = null

        fun cancelJob() {
            job?.cancel()
            job = null
        }

        fun bind(pageIndex: Int) {
            cancelJob()
            tvPage.text = "Page ${pageIndex + 1}"

            if (pageIndex == activePage) {
                border.setBackgroundColor(Color.parseColor("#38BDF8"))
            } else {
                border.setBackgroundResource(R.drawable.reader_badge_bg)
            }

            itemView.setOnClickListener {
                onPageSelected(pageIndex)
            }

            val cached = thumbCache.get(pageIndex)
            if (cached != null) {
                pbThumb.visibility = View.GONE
                ivThumb.setImageBitmap(cached)
                return
            }

            pbThumb.visibility = View.VISIBLE
            ivThumb.setImageBitmap(null)

            job = scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    renderMutex.withLock {
                        try {
                            if (pageIndex < 0 || pageIndex >= pdfRenderer.pageCount) return@withLock null
                            val page = pdfRenderer.openPage(pageIndex)
                            val targetW = 220
                            val targetH = ((targetW.toFloat() / page.width.toFloat()) * page.height).toInt()
                            val b = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            b
                        } catch (_: Exception) {
                            null
                        }
                    }
                }

                if (isActive && bmp != null) {
                    thumbCache.put(pageIndex, bmp)
                    pbThumb.visibility = View.GONE
                    ivThumb.setImageBitmap(bmp)
                }
            }
        }
    }
}
