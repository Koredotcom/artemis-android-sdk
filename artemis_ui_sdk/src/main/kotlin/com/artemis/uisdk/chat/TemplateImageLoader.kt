package com.artemis.uisdk.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Bounded per-renderer image cache. Network/decode work never runs on the UI thread. */
internal class TemplateImageLoader(
    private val fetch: (String) -> Bitmap? = ::download,
    private val worker: Executor = imageWorkers,
    private val deliver: (Runnable) -> Unit = Handler(Looper.getMainLooper()).let { handler -> { task -> handler.post(task); Unit } },
) {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount.coerceAtLeast(1)
    }
    private val pending = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private var generation = 0
    fun load(url: String, done: (Bitmap?) -> Unit) {
        if (!allowedUrl(url)) { done(null); return }
        val epoch: Int
        synchronized(this) {
            cache.get(url)?.let { done(it); return }
            pending[url]?.let { it.add(done); return }
            pending[url] = mutableListOf(done)
            epoch = generation
        }
        worker.execute {
            val bitmap = runCatching { fetch(url) }.getOrNull()
            val callbacks = synchronized(this) {
                if (epoch != generation) return@execute
                if (bitmap != null) cache.put(url, bitmap)
                pending.remove(url).orEmpty()
            }
            deliver(Runnable {
                if (synchronized(this) { epoch == generation }) callbacks.forEach { it(bitmap) }
            })
        }
    }
    fun clear() = synchronized(this) { generation++; pending.clear(); cache.evictAll() }
    companion object {
        private val imageWorkers = Executors.newFixedThreadPool(2)
        fun allowedUrl(url: String): Boolean {
            if (url.startsWith("data:", true)) return url.length <= 11_184_832 && url.matches(Regex("data:image/(?:png|jpe?g|gif|webp|avif|bmp|x-icon|vnd.microsoft.icon);base64,[A-Za-z0-9+/]+={0,2}", RegexOption.IGNORE_CASE))
            return runCatching { URI(url).let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
        }
        private fun decode(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        private fun download(url: String): Bitmap? {
            if (url.startsWith("data:", true)) {
                val bytes = android.util.Base64.decode(url.substringAfter(','), android.util.Base64.NO_WRAP)
                return if (bytes.size <= 8 * 1024 * 1024) decode(bytes) else null
            }
            val connection = URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
            try {
                val bytes = connection.getInputStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 8 * 1024 * 1024) return null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                return decode(bytes)
            } finally { (connection as? HttpURLConnection)?.disconnect() }
        }
    }
}
