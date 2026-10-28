package io.github.whyoolw.monopod.ui.menu

import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small decoded album covers for list rows, kept around while scrolling. */
object CoverCache {
    /** Where covers from the web (online music) are kept as files. */
    var remoteDir: File? = null

    /** A cover as a local file: web covers are downloaded once; blocking. */
    fun localFile(path: String?): String? {
        if (path == null || !path.startsWith("http")) return path
        val dir = remoteDir ?: return null
        val file = File(dir, "${path.hashCode().toUInt()}.jpg")
        if (!file.exists()) runCatching {
            dir.mkdirs()
            val c = URL(path).openConnection() as HttpURLConnection
            try {
                val tmp = File(file.path + ".part")
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            } finally {
                c.disconnect()
            }
        }
        return file.path.takeIf { file.exists() }
    }

    private const val THUMB_PX = 128
    // Sized in bytes: full covers (Now Playing, Cover Flow) are ~1 MB each, thumbnails ~64 KB
    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }

    fun peek(path: String?, full: Boolean = false): ImageBitmap? = path?.let { cache.get(key(it, full)) }

    /** A row thumbnail, or the whole 512 px cover with [full] for Now Playing. */
    suspend fun load(path: String?, full: Boolean = false): ImageBitmap? {
        if (path == null) return null
        val key = key(path, full)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            // Covers are stored at 512 px, a 4x sample is plenty for a row
            val opts = BitmapFactory.Options().apply { inSampleSize = if (full) 1 else 512 / THUMB_PX }
            localFile(path)?.let { BitmapFactory.decodeFile(it, opts) }?.asImageBitmap()
        }?.also { cache.put(key, it) }
    }

    private fun key(path: String, full: Boolean) = if (full) "$path#full" else path
}

/** A cover address from media metadata: the file path, or the whole web address. */
fun android.net.Uri.coverPath(): String? = if (scheme == "http" || scheme == "https") toString() else path
