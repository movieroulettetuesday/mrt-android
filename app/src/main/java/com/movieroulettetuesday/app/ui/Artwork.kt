package com.movieroulettetuesday.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.movieroulettetuesday.app.net.Http
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small dependency-free artwork loader (iOS ArtworkLoader): memory LRU + disk cache under
 * cacheDir/artwork, HTTPS only, tries the candidate URLs best-first. Disabled under tests.
 */
object ArtworkLoader {
    @Volatile var diskDir: File? = null
    @Volatile var enabled: Boolean = true
    private val memory = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cached(url: String): ImageBitmap? = memory.get(url)

    suspend fun load(urls: List<String>, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        for (url in urls) {
            memory.get(url)?.let { return@withContext it }
            val bytes = runCatching { readDisk(url) ?: Http.get(url, 15_000, accept = "image/*").also { writeDisk(url, it) } }.getOrNull() ?: continue
            val bmp = decode(bytes, maxPx) ?: continue
            memory.put(url, bmp)
            return@withContext bmp
        }
        null
    }

    private fun decode(bytes: ByteArray, maxPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun readDisk(url: String): ByteArray? = diskDir?.let { File(it, key(url)) }?.takeIf { it.isFile }?.readBytes()

    private fun writeDisk(url: String, bytes: ByteArray) {
        val dir = diskDir ?: return
        runCatching { dir.mkdirs(); File(dir, key(url)).writeBytes(bytes) }
    }
}

@Composable
fun ArtworkImage(
    urls: List<String>,
    size: Dp,
    placeholder: ImageVector,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = size / 6,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val image by produceState(initialValue = urls.firstNotNullOfOrNull { ArtworkLoader.cached(it) }, urls) {
        if (value == null) value = ArtworkLoader.load(urls, maxPx = 600)
    }
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Icon(placeholder, contentDescription, Modifier.size(size / 2.5f).testTag("artwork-placeholder"),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
