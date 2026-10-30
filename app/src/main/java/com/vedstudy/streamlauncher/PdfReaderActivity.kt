package com.vedstudy.streamlauncher

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vedstudy.streamlauncher.databinding.ActivityPdfReaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfReaderBinding

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pagesAdapter: PdfPagesAdapter? = null

    private var currentFile: File? = null
    private var docTitle: String = "Document"
    private var docUrl: String = ""
    private var fileHash: String = ""

    private var isHorizontalMode = false
    private var currentColorMode = ReaderColorMode.NORMAL
    private var areBarsVisible = true
    private var horizontalSnapHelper: PagerSnapHelper? = null

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"

        fun createIntent(context: Context, filePath: String, title: String, originalUrl: String = ""): Intent {
            return Intent(context, PdfReaderActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_URL, originalUrl)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen awake while reading study notes
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        docTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Document"
        docUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)

        binding.tvDocTitle.text = docTitle

        setupListeners()

        if (!filePath.isNullOrEmpty() && File(filePath).exists()) {
            openPdfFile(File(filePath))
        } else if (docUrl.isNotEmpty()) {
            downloadAndOpenPdf(docUrl)
        } else {
            Toast.makeText(this, "No document specified", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupListeners() {
        binding.btnReaderBack.setOnClickListener {
            finish()
        }

        binding.btnBookmark.setOnClickListener {
            toggleBookmarkCurrentPage()
        }

        binding.btnColorMode.setOnClickListener {
            cycleColorMode()
        }

        binding.btnScrollMode.setOnClickListener {
            toggleScrollMode()
        }

        binding.btnMoreOptions.setOnClickListener { view ->
            showMoreOptionsMenu(view)
        }

        binding.btnJumpPage.setOnClickListener {
            showJumpPageDialog()
        }

        binding.btnThumbnails.setOnClickListener {
            showThumbnailsBottomSheet()
        }

        binding.sbPageScrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val targetPage = progress.coerceIn(0, (pdfRenderer?.pageCount ?: 1) - 1)
                    updatePageIndicator(targetPage)
                    binding.pdfRecyclerView.scrollToPosition(targetPage)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.pdfRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val current = layoutManager.findFirstVisibleItemPosition()
                if (current >= 0) {
                    updatePageIndicator(current)
                }
            }
        })
    }

    private fun downloadAndOpenPdf(url: String) {
        binding.loadingContainer.visibility = View.VISIBLE
        binding.tvLoadingStatus.text = "Downloading high-speed study notes..."
        binding.pbDownloadProgress.progress = 0

        val sessionCookie = SessionManager(this).getSessionCookie()

        lifecycleScope.launch {
            val file = PdfDocumentManager.downloadPdf(
                context = this@PdfReaderActivity,
                url = url,
                sessionCookie = sessionCookie,
                suggestedName = docTitle,
                onProgress = { pct ->
                    binding.pbDownloadProgress.progress = (pct * 100).toInt()
                    binding.tvLoadingStatus.text = "Downloading notes (${(pct * 100).toInt()}%)..."
                }
            )

            binding.loadingContainer.visibility = View.GONE

            if (file != null && file.exists()) {
                openPdfFile(file)
            } else {
                Toast.makeText(this@PdfReaderActivity, "Could not load document", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun openPdfFile(file: File) {
        currentFile = file
        fileHash = file.name

        try {
            parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val pfd = parcelFileDescriptor ?: throw IllegalStateException("Cannot open file descriptor")
            val renderer = PdfRenderer(pfd)
            pdfRenderer = renderer

            val total = renderer.pageCount
            binding.sbPageScrubber.max = (total - 1).coerceAtLeast(0)

            // Setup RecyclerView
            setupRecyclerView()

            // Save to recent docs library
            PdfDocumentManager.saveRecentDoc(
                this,
                PdfDocItem(
                    id = fileHash,
                    title = docTitle,
                    filePath = file.absolutePath,
                    originalUrl = docUrl,
                    fileSizeBytes = file.length(),
                    dateAdded = System.currentTimeMillis(),
                    lastReadPage = 0,
                    totalPages = total
                )
            )

            // Restore last read position
            val recentList = PdfDocumentManager.getRecentDocs(this)
            val savedDoc = recentList.find { it.filePath == file.absolutePath }
            val lastPage = savedDoc?.lastReadPage ?: 0
            if (lastPage > 0 && lastPage < total) {
                binding.pdfRecyclerView.post {
                    binding.pdfRecyclerView.scrollToPosition(lastPage)
                    updatePageIndicator(lastPage)
                }
            } else {
                updatePageIndicator(0)
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to render PDF: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupRecyclerView() {
        val renderer = pdfRenderer ?: return
        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels

        pagesAdapter = PdfPagesAdapter(
            pdfRenderer = renderer,
            scope = lifecycleScope,
            screenWidth = screenW,
            colorMode = currentColorMode,
            onPageTap = { toggleBarsVisibility() }
        )

        binding.pdfRecyclerView.apply {
            adapter = pagesAdapter
            layoutManager = LinearLayoutManager(this@PdfReaderActivity, LinearLayoutManager.VERTICAL, false)
            itemAnimator = null
        }
    }

    private fun toggleScrollMode() {
        val renderer = pdfRenderer ?: return
        val layoutManager = binding.pdfRecyclerView.layoutManager as? LinearLayoutManager
        val currentPage = layoutManager?.findFirstVisibleItemPosition() ?: 0

        isHorizontalMode = !isHorizontalMode

        if (isHorizontalMode) {
            horizontalSnapHelper = PagerSnapHelper().apply {
                attachToRecyclerView(binding.pdfRecyclerView)
            }
            binding.pdfRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.btnScrollMode.setImageResource(R.drawable.ic_scroll_mode)
            Toast.makeText(this, "Horizontal Book Mode", Toast.LENGTH_SHORT).show()
        } else {
            horizontalSnapHelper?.attachToRecyclerView(null)
            horizontalSnapHelper = null
            binding.pdfRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
            binding.btnScrollMode.setImageResource(R.drawable.ic_scroll_mode)
            Toast.makeText(this, "Continuous Vertical Scroll", Toast.LENGTH_SHORT).show()
        }

        binding.pdfRecyclerView.scrollToPosition(currentPage)
    }

    private fun cycleColorMode() {
        currentColorMode = when (currentColorMode) {
            ReaderColorMode.NORMAL -> {
                binding.btnColorMode.setImageResource(R.drawable.ic_light_mode)
                Toast.makeText(this, "Night Mode (Dark Paper)", Toast.LENGTH_SHORT).show()
                ReaderColorMode.NIGHT
            }
            ReaderColorMode.NIGHT -> {
                binding.btnColorMode.setImageResource(R.drawable.ic_sepia_mode)
                Toast.makeText(this, "Sepia Mode (Eye Comfort)", Toast.LENGTH_SHORT).show()
                ReaderColorMode.SEPIA
            }
            ReaderColorMode.SEPIA -> {
                binding.btnColorMode.setImageResource(R.drawable.ic_night_mode)
                Toast.makeText(this, "Normal Light Mode", Toast.LENGTH_SHORT).show()
                ReaderColorMode.NORMAL
            }
        }
        pagesAdapter?.setColorMode(currentColorMode)
    }

    private fun toggleBookmarkCurrentPage() {
        val layoutManager = binding.pdfRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val currentPage = layoutManager.findFirstVisibleItemPosition()
        if (currentPage < 0) return

        val isBookmarked = PdfDocumentManager.toggleBookmark(this, fileHash, currentPage)
        updateBookmarkIcon(isBookmarked)

        val msg = if (isBookmarked) "Page ${currentPage + 1} Bookmarked" else "Bookmark Removed"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateBookmarkIcon(isBookmarked: Boolean) {
        if (isBookmarked) {
            binding.btnBookmark.setImageResource(R.drawable.ic_bookmark)
        } else {
            binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_border)
        }
    }

    private fun updatePageIndicator(pageIndex: Int) {
        val total = pdfRenderer?.pageCount ?: 1
        val displayNum = (pageIndex + 1).coerceIn(1, total)
        binding.tvPageIndicator.text = "Page $displayNum / $total"

        if (binding.sbPageScrubber.progress != pageIndex) {
            binding.sbPageScrubber.progress = pageIndex
        }

        val isBookmarked = PdfDocumentManager.isBookmarked(this, fileHash, pageIndex)
        updateBookmarkIcon(isBookmarked)

        currentFile?.let {
            PdfDocumentManager.updateProgress(this, it.absolutePath, pageIndex, total)
        }
    }

    private fun toggleBarsVisibility() {
        areBarsVisible = !areBarsVisible
        val targetVis = if (areBarsVisible) View.VISIBLE else View.GONE
        binding.topToolbar.visibility = targetVis
        binding.bottomNav.visibility = targetVis
    }

    private fun showJumpPageDialog() {
        val total = pdfRenderer?.pageCount ?: 1
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Enter page (1 - $total)"
            setPadding(40, 30, 40, 30)
            setTextColor(getColor(R.color.text_white))
            setHintTextColor(getColor(R.color.text_muted))
        }

        AlertDialog.Builder(this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_Dialog_Alert)
            .setTitle("Jump to Page")
            .setMessage("Total pages: $total")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val entered = input.text.toString().toIntOrNull()
                if (entered != null && entered in 1..total) {
                    val target = entered - 1
                    binding.pdfRecyclerView.scrollToPosition(target)
                    updatePageIndicator(target)
                } else {
                    Toast.makeText(this, "Please enter a valid page number", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showThumbnailsBottomSheet() {
        val renderer = pdfRenderer ?: return
        val dialog = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.dialog_pdf_thumbnails, null)
        dialog.setContentView(sheetView)

        val rv = sheetView.findViewById<RecyclerView>(R.id.rvThumbnails)
        val btnClose = sheetView.findViewById<View>(R.id.btnCloseThumbnails)

        btnClose.setOnClickListener { dialog.dismiss() }

        val layoutManager = binding.pdfRecyclerView.layoutManager as? LinearLayoutManager
        val currentPage = layoutManager?.findFirstVisibleItemPosition() ?: 0

        val thumbAdapter = PdfThumbnailsAdapter(
            pdfRenderer = renderer,
            scope = lifecycleScope,
            activePage = currentPage,
            onPageSelected = { selectedPage ->
                binding.pdfRecyclerView.scrollToPosition(selectedPage)
                updatePageIndicator(selectedPage)
                dialog.dismiss()
            }
        )

        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = thumbAdapter
        rv.scrollToPosition(currentPage)

        dialog.show()
    }

    private fun showMoreOptionsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "📤 Share Document")
        popup.menu.add(0, 2, 1, "💾 Save to Downloads")
        popup.menu.add(0, 3, 2, "📑 View Bookmarks")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    currentFile?.let { PdfDocumentManager.sharePdf(this, it, docTitle) }
                    true
                }
                2 -> {
                    currentFile?.let {
                        val saved = PdfDocumentManager.saveToDownloads(this, it, docTitle)
                        if (saved) {
                            Toast.makeText(this, "Saved to device Downloads folder!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Could not save to Downloads", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                3 -> {
                    showBookmarksDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showBookmarksDialog() {
        val bookmarks = PdfDocumentManager.getBookmarks(this, fileHash)
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "No pages bookmarked yet. Tap the bookmark icon to save pages!", Toast.LENGTH_LONG).show()
            return
        }

        val items = bookmarks.map { "Page ${it + 1}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bookmarked Pages")
            .setItems(items) { _, which ->
                val targetPage = bookmarks[which]
                binding.pdfRecyclerView.scrollToPosition(targetPage)
                updatePageIndicator(targetPage)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
        } catch (_: Exception) {}
    }
}
