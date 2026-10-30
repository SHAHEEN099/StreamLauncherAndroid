package com.vedstudy.streamlauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class DocsLibraryAdapter(
    private var items: List<PdfDocItem>,
    private val onDocClicked: (PdfDocItem) -> Unit,
    private val onShareClicked: (PdfDocItem) -> Unit,
    private val onDeleteClicked: (PdfDocItem) -> Unit
) : RecyclerView.Adapter<DocsLibraryAdapter.DocViewHolder>() {

    fun updateItems(newItems: List<PdfDocItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_doc_library, parent, false)
        return DocViewHolder(view)
    }

    override fun onBindViewHolder(holder: DocViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class DocViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvItemDocTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvItemDocSubtitle)
        private val btnShare: ImageButton = itemView.findViewById(R.id.btnItemDocShare)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnItemDocDelete)

        fun bind(doc: PdfDocItem) {
            tvTitle.text = doc.title

            val mb = doc.fileSizeBytes / (1024f * 1024f)
            val sizeStr = if (mb >= 0.1f) "%.1f MB".format(mb) else "${doc.fileSizeBytes / 1024} KB"

            val progressStr = if (doc.totalPages > 0) {
                val pct = ((doc.lastReadPage + 1).toFloat() / doc.totalPages.toFloat() * 100).toInt()
                "Read $pct% (p. ${doc.lastReadPage + 1}/${doc.totalPages})"
            } else {
                val dateStr = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(doc.dateAdded))
                "Saved on $dateStr"
            }

            tvSubtitle.text = "$sizeStr • $progressStr"

            itemView.setOnClickListener { onDocClicked(doc) }
            btnShare.setOnClickListener { onShareClicked(doc) }
            btnDelete.setOnClickListener { onDeleteClicked(doc) }
        }
    }
}
