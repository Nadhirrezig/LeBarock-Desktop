package com.lebarock.lebarock_desktop.ui.pos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import com.lebarock.lebarock_desktop.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The item photos of the POS cards, as the web shows them (`item_photo_url`, client 2026-11-08: "a photo on each
 * article and the food icon for those with none"). The caisse's address in that URL is its own `APP_URL` (often
 * `localhost`): the path is put on the server set in Réglages, as the handheld does. An item without a photo comes
 * as the web's `img/food.svg`, drawn here from the copy bundled with the app (no network); a photo that cannot be
 * fetched falls back to it too (tried again after a minute). Photos stay in memory while the app runs, and in the
 * HTTP cache on disk (`ApiClient.imageHttp`).
 */
class ItemImages(
    private val http: () -> OkHttpClient,
    private val baseUrl: () -> String,
    private val decode: (bytes: ByteArray, svg: Boolean) -> Painter = ::decodePainter,
    placeholderOf: () -> Painter = { bundled("food") },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Where a card's picture comes from. */
    sealed interface Source {
        data object Placeholder : Source
        data class Remote(val url: String) : Source
    }

    val placeholder: Painter by lazy(placeholderOf)

    private val memory: MutableMap<String, Painter> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Painter>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Painter>?) = size > MAX_IN_MEMORY
        },
    )
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** What is already known for [url], drawn at once (no blink when a card comes back on screen). */
    fun cached(url: String?): Painter? = when (val source = resolve(url, baseUrl())) {
        Source.Placeholder -> placeholder
        is Source.Remote -> memory[source.url]
    }

    suspend fun load(url: String?): Painter = when (val source = resolve(url, baseUrl())) {
        Source.Placeholder -> placeholder
        is Source.Remote -> memory[source.url] ?: fetch(source.url)
    }

    private suspend fun fetch(url: String): Painter = withContext(Dispatchers.IO) {
        failedAt[url]?.let { if (now() - it < RETRY_MILLIS) return@withContext placeholder }
        runCatching {
            http().newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val svg = url.substringBefore('?').lowercase().endsWith(".svg") ||
                    response.header("Content-Type").orEmpty().contains("svg")
                decode(response.body.bytes(), svg)
            }
        }.fold(
            onSuccess = { painter ->
                failedAt.remove(url)
                memory[url] = painter
                painter
            },
            onFailure = {
                if (failedAt.put(url, now()) == null) AppLog.warn("Photo d'article non chargée ($url) : ${it.message}")
                placeholder
            },
        )
    }

    companion object {
        private const val MAX_IN_MEMORY = 400
        private const val RETRY_MILLIS = 60_000L
        private val TYPE_ICONS = setOf("veg", "non-veg", "egg", "drink")
        private val typeCache = ConcurrentHashMap<String, Painter>()

        /**
         * The caisse's URL on the server of Réglages (scheme, host and port from [baseUrl], path and query kept);
         * no URL, or the web's own `img/food.svg`, is the bundled placeholder.
         */
        fun resolve(url: String?, baseUrl: String): Source {
            if (url.isNullOrBlank()) return Source.Placeholder
            val schemeEnd = url.indexOf("://")
            val path = if (schemeEnd < 0) url else url.indexOf('/', schemeEnd + 3).let { if (it < 0) "/" else url.substring(it) }
            if (path.substringBefore('?').endsWith("/img/food.svg")) return Source.Placeholder
            if (baseUrl.isBlank()) return if (schemeEnd < 0) Source.Placeholder else Source.Remote(url)
            return Source.Remote(baseUrl.trimEnd('/') + (if (path.startsWith("/")) path else "/$path"))
        }

        /** The item type's small badge (web `img/{type}.svg`); none for a type the web has no icon for (« other »). */
        fun typeIcon(type: String?): Painter? = type?.takeIf { it in TYPE_ICONS }?.let { t -> typeCache.getOrPut(t) { bundled(t) } }

        private fun bundled(name: String): Painter =
            ItemImages::class.java.getResourceAsStream("/item/$name.svg")!!.use { loadSvgPainter(it, Density(1f)) }

        private fun decodePainter(bytes: ByteArray, svg: Boolean): Painter =
            if (svg) {
                bytes.inputStream().use { loadSvgPainter(it, Density(1f)) }
            } else {
                BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
            }
    }
}

/** A card's square picture: the photo cropped to the square (web `object-cover`); a plain square while it loads. */
@Composable
fun ItemPhoto(url: String?, images: ItemImages, modifier: Modifier = Modifier) {
    val painter by produceState(images.cached(url), url) { value = images.load(url) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        painter?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
