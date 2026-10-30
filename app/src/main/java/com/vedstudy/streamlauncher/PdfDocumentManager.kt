package com.vedstudy.streamlauncher

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class PdfDocItem(
    val id: String,
    val title: String,
    val filePath: String,
    val originalUrl: String,
    val fileSizeBytes: Long,
    val dateAdded: Long,
    val lastReadPage: Int = 0,
    val totalPages: Int = 0
)

object PdfDocumentManager {

    private const val TAG = "PdfDocumentManager"
    private const val PREFS_NAME = "pdf_docs_prefs"
    private const val KEY_RECENTS = "recent_pdf_docs"
    private const val KEY_BOOKMARKS = "pdf_bookmarks_"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "pdf_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getCachedFileForUrl(context: Context, url: String, suggestedName: String? = null): File {
        val hash = md5(url)
        val ext = when {
            url.contains(".pdf", ignoreCase = true) -> ".pdf"
            url.contains(".docx", ignoreCase = true) -> ".docx"
            url.contains(".doc", ignoreCase = true) -> ".doc"
            else -> ".pdf"
        }
        val safeName = if (!suggestedName.isNullOrBlank()) {
            suggestedName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
        } else {
            "document"
        }
        return File(getCacheDir(context), "${safeName}_$hash$ext")
    }

    suspend fun downloadPdf(
        context: Context,
        url: String,
        sessionCookie: String?,
        suggestedName: String? = null,
        onProgress: (progress: Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        try {
            val targetFile = getCachedFileForUrl(context, url, suggestedName)
            if (targetFile.exists() && targetFile.length() > 500) {
                Log.d(TAG, "Using existing cached PDF: ${targetFile.absolutePath}")
                onProgress(1.0f)
                return@withContext targetFile
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")

            if (!sessionCookie.isNullOrEmpty() && (url.contains("testuk.org") || url.contains("vedstudy"))) {
                reqBuilder.header("Cookie", "session=$sessionCookie")
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            val totalBytes = body.contentLength()
            val tempFile = File(getCacheDir(context), "temp_${System.currentTimeMillis()}.tmp")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var accumulated: Long = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        accumulated += bytesRead
                        if (totalBytes > 0) {
                            val pct = accumulated.toFloat() / totalBytes.toFloat()
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                withContext(Dispatchers.Main) { onProgress(1.0f) }
                return@withContext targetFile
            }

            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading PDF: ${e.message}", e)
            return@withContext null
        }
    }

    fun saveRecentDoc(context: Context, doc: PdfDocItem) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val list = getRecentDocs(context).toMutableList()

            list.removeAll { it.filePath == doc.filePath || it.id == doc.id }
            list.add(0, doc)
            if (list.size > 50) list.removeAt(list.size - 1)

            val arr = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("filePath", item.filePath)
                    put("originalUrl", item.originalUrl)
                    put("fileSizeBytes", item.fileSizeBytes)
                    put("dateAdded", item.dateAdded)
                    put("lastReadPage", item.lastReadPage)
                    put("totalPages", item.totalPages)
                }
                arr.put(obj)
            }
            prefs.edit().putString(KEY_RECENTS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving recent doc: ${e.message}")
        }
    }

    fun getRecentDocs(context: Context): List<PdfDocItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        val result = mutableListOf<PdfDocItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val path = obj.optString("filePath")
                if (File(path).exists()) {
                    result.add(
                        PdfDocItem(
                            id = obj.optString("id"),
                            title = obj.optString("title"),
                            filePath = path,
                            originalUrl = obj.optString("originalUrl"),
                            fileSizeBytes = obj.optLong("fileSizeBytes"),
                            dateAdded = obj.optLong("dateAdded"),
                            lastReadPage = obj.optInt("lastReadPage", 0),
                            totalPages = obj.optInt("totalPages", 0)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing recent docs: ${e.message}")
        }
        return result
    }

    fun deleteRecentDoc(context: Context, doc: PdfDocItem) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = getRecentDocs(context).filterNot { it.filePath == doc.filePath }
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("filePath", item.filePath)
                put("originalUrl", item.originalUrl)
                put("fileSizeBytes", item.fileSizeBytes)
                put("dateAdded", item.dateAdded)
                put("lastReadPage", item.lastReadPage)
                put("totalPages", item.totalPages)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_RECENTS, arr.toString()).apply()

        try {
            val f = File(doc.filePath)
            if (f.exists()) f.delete()
        } catch (_: Exception) {}
    }

    fun updateProgress(context: Context, filePath: String, currentPage: Int, totalPages: Int) {
        val list = getRecentDocs(context).toMutableList()
        val index = list.indexOfFirst { it.filePath == filePath }
        if (index != -1) {
            val old = list[index]
            val updated = old.copy(lastReadPage = currentPage, totalPages = totalPages)
            list[index] = updated

            val arr = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("filePath", item.filePath)
                    put("originalUrl", item.originalUrl)
                    put("fileSizeBytes", item.fileSizeBytes)
                    put("dateAdded", item.dateAdded)
                    put("lastReadPage", item.lastReadPage)
                    put("totalPages", item.totalPages)
                }
                arr.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECENTS, arr.toString()).apply()
        }
    }

    fun toggleBookmark(context: Context, fileHash: String, pageIndex: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_BOOKMARKS + fileHash
        val set = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        val pageStr = pageIndex.toString()
        val isNowBookmarked = if (set.contains(pageStr)) {
            set.remove(pageStr)
            false
        } else {
            set.add(pageStr)
            true
        }
        prefs.edit().putStringSet(key, set).apply()
        return isNowBookmarked
    }

    fun isBookmarked(context: Context, fileHash: String, pageIndex: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_BOOKMARKS + fileHash, emptySet()) ?: emptySet()
        return set.contains(pageIndex.toString())
    }

    fun getBookmarks(context: Context, fileHash: String): List<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_BOOKMARKS + fileHash, emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.sorted()
    }

    fun sharePdf(context: Context, file: File, title: String) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Document via"))
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing PDF: ${e.message}")
        }
    }

    fun saveToDownloads(context: Context, file: File, displayName: String): Boolean {
        return try {
            val cleanName = if (displayName.endsWith(".pdf", ignoreCase = true)) displayName else "$displayName.pdf"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { inp ->
                        inp.copyTo(out)
                    }
                }
                true
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val target = File(downloadsDir, cleanName)
                file.copyTo(target, overwrite = true)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to Downloads: ${e.message}", e)
            false
        }
    }
}

