package com.vedstudy.streamlauncher

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PdfPagesAdapter(
    private val pdfRenderer: PdfRenderer,
    private val scope: CoroutineScope,
    private val screenWidth: Int,
    private var colorMode: ReaderColorMode = ReaderColorMode.NORMAL,
    private val onPageTap: () -> Unit
) : RecyclerView.Adapter<PdfPagesAdapter.PageViewHolder>() {

    private val pageCount = pdfRenderer.pageCount
    private val renderMutex = Mutex()

    // 25MB LRU Bitmap Cache
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 6).coerceAtLeast(15 * 1024)
    private val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun setColorMode(newMode: ReaderColorMode) {
        if (colorMode != newMode) {
            colorMode = newMode
            bitmapCache.evictAll()
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelJob()
    }

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val zoomImageView: ZoomableTouchImageView = itemView.findViewById(R.id.pageImageView)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.pageProgressBar)
        private var renderJob: Job? = null

        fun cancelJob() {
            renderJob?.cancel()
            renderJob = null
        }

        fun bind(pageIndex: Int) {
            cancelJob()
            zoomImageView.resetZoom()
            zoomImageView.applyColorMode(colorMode)
            zoomImageView.onSingleTapListener = onPageTap

            val cached = bitmapCache.get(pageIndex)
            if (cached != null && !cached.isRecycled) {
                progressBar.visibility = View.GONE
                zoomImageView.visibility = View.VISIBLE
                zoomImageView.setImageBitmap(cached)
                return
            }

            progressBar.visibility = View.VISIBLE
            zoomImageView.setImageBitmap(null)

            renderJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    renderPageSafely(pageIndex)
                }

                if (isActive && bitmap != null) {
                    bitmapCache.put(pageIndex, bitmap)
                    progressBar.visibility = View.GONE
                    zoomImageView.visibility = View.VISIBLE
                    zoomImageView.setImageBitmap(bitmap)
                }
            }
        }

        private suspend fun renderPageSafely(pageIndex: Int): Bitmap? = renderMutex.withLock {
            try {
                if (pageIndex < 0 || pageIndex >= pdfRenderer.pageCount) return null
                val page = pdfRenderer.openPage(pageIndex)

                // High resolution scaling: at least 1080px wide or 2x screen width
                val targetWidth = (screenWidth * 1.8f).toInt().coerceAtLeast(1080)
                val targetHeight = ((targetWidth.toFloat() / page.width.toFloat()) * page.height).toInt()

                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                // Essential for exact original colors: initialize bitmap with opaque white canvas
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmap
            } catch (e: Exception) {
                null
            }
        }
    }
}

