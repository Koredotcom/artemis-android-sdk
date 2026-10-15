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
        if (!runCatching { URI(url).let { it.scheme.equals("https", true) && it.host != null } }.getOrDefault(false)) { done(null); return }
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
        private fun download(url: String): Bitmap? {
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
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally { (connection as? HttpURLConnection)?.disconnect() }
        }
    }
}
