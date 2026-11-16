package app.a6600remote.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Загрузка картинок с камеры (миниатюры, превью) и сохранение в галерею телефона.
 * HTTP-сервер камеры слабый, поэтому одновременно не больше двух загрузок.
 */
object RemoteImages {

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val gate = Semaphore(2)

    /** [src] — http-адрес на камере или путь к файлу на телефоне. */
    suspend fun load(src: String, maxSize: Int): Bitmap? {
        val key = "$src@$maxSize"
        cache.get(key)?.let { return it }
        val bytes = readBytes(src) ?: return null
        val bmp = withContext(Dispatchers.Default) { decodeSampled(bytes, maxSize) } ?: return null
        cache.put(key, bmp)
        return bmp
    }

    suspend fun readBytes(src: String, timeoutMs: Int = 15_000): ByteArray? = gate.withPermit {
        withContext(Dispatchers.IO) {
            try {
                if (src.startsWith("http")) {
                    val conn = URL(src).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5_000
                    conn.readTimeout = timeoutMs
                    try {
                        conn.inputStream.use { it.readBytes() }
                    } finally {
                        conn.disconnect()
                    }
                } else {
                    File(src).readBytes()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun decodeSampled(bytes: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Сохраняет JPEG в «Pictures/A6600Remote». Разрешения не нужны (Android 10+). */
    suspend fun saveToGallery(context: Context, bytes: ByteArray, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/A6600Remote")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@withContext false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (_: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        }
}
