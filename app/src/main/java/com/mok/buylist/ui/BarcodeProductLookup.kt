package com.mok.buylist.ui

import com.mok.buylist.data.validProductBarcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A suggestion, not a trusted catalog identity. Never replace a name already edited by the user. */
internal data class BarcodeProductSuggestion(
    val name: String,
    val source: String,
    val imageUrl: String? = null,
    val score: Int = 0,
)

internal object BarcodeProductLookup {
    private const val AGENT = "BuyList-Android/1.0 (barcode suggestions)"
    private val cache = mutableMapOf<String, List<BarcodeProductSuggestion>>()

    suspend fun lookup(code: String, force: Boolean = false): List<BarcodeProductSuggestion> =
        withContext(Dispatchers.IO) {
            if (!validProductBarcode(code)) return@withContext emptyList()
            if (!force) synchronized(cache) { cache[code] }?.let { return@withContext it }
            // One failed provider must not prevent suggestions from the others.
            val results = listOfNotNull(
                runCatching { openFoodFacts(code) }.getOrNull(),
                runCatching { upcItemDb(code) }.getOrNull(),
                runCatching { olegon(code) }.getOrNull(),
            ).filter { usefulName(it.name, code) }
            val ranked = results.distinctBy { it.name.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim() }
                .sortedWith(compareByDescending<BarcodeProductSuggestion> { it.score }
                    .thenByDescending { it.name.length })
            synchronized(cache) { cache[code] = ranked }
            ranked
        }

    private fun request(url: String, limit: Int = 120_000): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Accept", "application/json,text/plain;q=0.9")
        }
        return try {
            if (connection.responseCode != 200) return null
            val length = connection.contentLength
            if (length > limit) return null
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > limit) return null
                    output.write(buffer, 0, n)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openFoodFacts(code: String): BarcodeProductSuggestion? {
        val body = request("https://world.openfoodfacts.org/api/v2/product/$code.json?fields=product_name,product_name_ru,brands,image_front_url,image_url") ?: return null
        val product = JSONObject(body).optJSONObject("product") ?: return null
        val localized = product.optString("product_name_ru").trim()
        val general = product.optString("product_name").trim()
        val name = localized.ifBlank { general }
        if (!usefulName(name, code)) return null
        val brand = product.optString("brands").split(',').firstOrNull()?.trim().orEmpty()
        val title = if (brand.isNotBlank() && !name.contains(brand, ignoreCase = true))
            "$brand $name" else name
        val image = product.optString("image_front_url").ifBlank { product.optString("image_url") }
        return BarcodeProductSuggestion(title.take(200), "Open Food Facts",
            safeImageUrl(image), quality(title, localized.isNotBlank(), image.isNotBlank()))
    }

    private fun upcItemDb(code: String): BarcodeProductSuggestion? {
        val body = request("https://api.upcitemdb.com/prod/trial/lookup?upc=$code") ?: return null
        val item = JSONObject(body).optJSONArray("items")?.optJSONObject(0) ?: return null
        val name = item.optString("title").trim()
        if (!usefulName(name, code)) return null
        val images = item.optJSONArray("images")
        val image = images?.optString(0).orEmpty()
        return BarcodeProductSuggestion(name.take(200), "UPCitemdb", safeImageUrl(image),
            quality(name, false, image.isNotBlank()))
    }

    /**
     * Olegon may require registration / API authorization. Accept only useful text or JSON
     * from the public sample URL; never mistake HTML login/captcha/error pages for a title.
     */
    private fun olegon(code: String): BarcodeProductSuggestion? {
        val body = request("https://barcodes.olegon.ru/r.php?c=$code", 32_000)?.trim()
            ?: return null
        val name = if (body.startsWith("{")) {
            val data = JSONObject(body)
            listOf("name", "product_name", "title", "tovar", "Наименование")
                .firstNotNullOfOrNull { key -> data.optString(key).takeIf { it.isNotBlank() } }
                .orEmpty()
        } else if (body.startsWith("["))
            JSONArray(body).optJSONObject(0)?.optString("name").orEmpty()
        else body
        if (!usefulName(name, code) || name.contains('<') || name.contains('>')) return null
        return BarcodeProductSuggestion(name.trim().take(200), "Olegon",
            score = quality(name, true, false))
    }

    private fun safeImageUrl(value: String): String? {
        val url = runCatching { URL(value) }.getOrNull() ?: return null
        if (url.protocol != "https") return null
        val allowed = setOf("images.openfoodfacts.org", "images.openbeautyfacts.org",
            "static.openfoodfacts.org", "i5.walmartimages.com")
        return value.takeIf { url.host in allowed || url.host.endsWith(".upcitemdb.com") }
    }

    internal fun usefulName(raw: String, code: String): Boolean {
        val name = raw.trim()
        if (name.length !in 4..200 || name == code || name.all(Char::isDigit)) return false
        if (name.any { it == '\n' || it == '\r' || it == '<' || it == '>' }) return false
        val ignored = listOf("not found", "error", "captcha", "access denied", "не найден",
            "нет данных", "неизвестный товар", "invalid", "null", "undefined", "403 forbidden")
        return ignored.none { name.contains(it, ignoreCase = true) }
    }

    internal fun quality(name: String, localized: Boolean, image: Boolean): Int {
        val letters = name.count(Char::isLetter)
        val words = name.split(Regex("\\s+")).count { it.length > 1 }
        val generic = name.lowercase(Locale.ROOT) in setOf("продукт", "товар", "product", "food")
        return (if (localized) 15 else 0) + (if (image) 6 else 0) +
            words.coerceAtMost(7) * 3 + letters.coerceAtMost(45) / 5 -
            (if (generic) 50 else 0) - (if (name.length > 120) 8 else 0)
    }
}
