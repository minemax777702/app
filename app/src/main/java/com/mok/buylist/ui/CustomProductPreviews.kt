package com.mok.buylist.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.data.validProductBarcode
import com.mok.buylist.barcodeSourceSettings
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Local files are independent from purchases; API errors never prevent adding a product. */
internal object CustomProductPreviews {
    private val revisions = mutableStateMapOf<String, Int>()
    private val requested = mutableSetOf<String>()
    fun version(id: String): Int = revisions[id] ?: 0
    private fun file(context: Context, id: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        return File(File(context.filesDir, "custom_previews").apply { mkdirs() }, id + ".jpg")
    }
    fun image(context: Context, id: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file(context, id).path) }.getOrNull()
    private fun save(context: Context, id: String, bytes: ByteArray): Boolean {
        if (bytes.size > 4_000_000) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..10000 || bounds.outHeight !in 1..10000) return false
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val destination = file(context, id)
        val tmp = File(destination.parentFile, id + ".tmp")
        val success = runCatching {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!tmp.renameTo(destination)) { tmp.copyTo(destination, overwrite = true); tmp.delete() }
        }.isSuccess
        bitmap.recycle()
        if (!success) tmp.delete()
        return success
    }
    suspend fun setFromUri(context: Context, id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 4_000_000) return@use null
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        }.getOrNull() ?: return@withContext false
        val saved = save(context, id, bytes)
        if (saved) withContext(Dispatchers.Main) { revisions[id] = version(id) + 1 }
        saved
    }
    suspend fun fetch(context: Context, id: String, barcode: String, force: Boolean = false): Boolean {
        if (!validProductBarcode(barcode)) return false
        val enabled = context.barcodeSourceSettings.enabled.value
        if (enabled.none { it == BarcodeSource.OPEN_FOOD_FACTS ||
                it == BarcodeSource.UPCITEMDB || it == BarcodeSource.OLEGON }) return false
        val token = id + ":" + barcode + "|" +
            enabled.map { it.name }.sorted().joinToString(",")
        synchronized(requested) {
            if (!force && (token in requested || file(context, id).exists())) return false
            requested.add(token)
        }
        return withContext(Dispatchers.IO) {
            val suggestions = BarcodeProductLookup.lookup(barcode, force = force, sources = enabled)
            for (suggestion in suggestions) {
                val url = suggestion.imageUrl ?: continue
                val bytes = runCatching {
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "BuyList-Android/1.0 (product preview)")
                    }
                    try {
                        if (connection.responseCode != 200) return@runCatching null
                        val output = ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (output.size() + n > 4_000_000) return@runCatching null
                                output.write(buffer, 0, n)
                            }
                        }
                        output.toByteArray()
                    } finally { connection.disconnect() }
                }.getOrNull() ?: continue
                // Never overwrite a photo from the gallery during automatic lookup.
                if (!force && file(context, id).exists()) return@withContext false
                if (save(context, id, bytes)) {
                    withContext(Dispatchers.Main) { revisions[id] = version(id) + 1 }
                    return@withContext true
                }
            }
            false
        }
    }
}

@Composable
internal fun CustomProductImage(productId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val version = CustomProductPreviews.version(productId)
    val bitmap = remember(productId, version) { CustomProductPreviews.image(context, productId) }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(p.field), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(painterResource(R.drawable.ic_product_placeholder), null, tint = p.iconMuted,
            modifier = Modifier.fillMaxSize(0.5f))
    }
}

@Composable
internal fun CustomProductImageActions(productId: String, barcode: String) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var loading by remember(productId) { mutableStateOf(false) }
    var message by remember(productId) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            val ok = CustomProductPreviews.setFromUri(context, productId, uri)
            loading = false
            message = if (ok) null else "Не удалось открыть фото"
        }
    }
    Column {
        Column(horizontalAlignment = Alignment.Start) {
            TextButton(onClick = { picker.launch("image/*") }, enabled = !loading) {
                Text("Выбрать фото", color = p.accent)
            }
            if (barcode.isNotBlank()) TextButton(onClick = {
                scope.launch {
                    loading = true
                    val ok = CustomProductPreviews.fetch(context, productId, barcode, force = true)
                    loading = false
                    message = if (ok) "Фото обновлено" else "Фото по штрихкоду не найдено"
                }
            }, enabled = !loading && validProductBarcode(barcode)) {
                Text(if (loading) "Загрузка…" else "Обновить по штрихкоду", color = p.accent)
            }
        }
        message?.let { Text(it, color = p.textSecondary, fontSize = 12.sp) }
    }
}
