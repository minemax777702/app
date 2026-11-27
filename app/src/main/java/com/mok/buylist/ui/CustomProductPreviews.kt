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
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import android.util.AtomicFile
import com.mok.buylist.cloud.CloudPhoto

/** Local files are independent from purchases; API errors never prevent adding a product. */
internal object CustomProductPreviews {
    private val revisions = mutableStateMapOf<String, Int>()
    fun version(id: String, workspace: String? = null): Int = revisions["$workspace/$id"] ?: 0
    private fun file(context: Context, id: String, workspace: String?): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        return File(File(context.filesDir, if (workspace == null) "custom_previews" else "custom_previews_$workspace").apply { mkdirs() }, id + ".jpg")
    }
    fun image(context: Context, id: String, workspace: String? = null): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file(context, id, workspace).path) }.getOrNull()
    @Synchronized private fun save(context: Context, id: String, bytes: ByteArray, workspace: String?): Boolean {
        if (bytes.size > 4_000_000) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..10000 || bounds.outHeight !in 1..10000) return false
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val destination = file(context, id, workspace)
        val success = try {
            val output = ByteArrayOutputStream()
            var quality = 85
            do {
                output.reset()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                quality -= 10
            } while (workspace != null && output.size() > CloudPhoto.MAX_JPEG && quality >= 25)
            if (workspace != null && output.size() > CloudPhoto.MAX_JPEG) {
                val small = Bitmap.createScaledBitmap(bitmap, maxOf(1, bitmap.width / 2), maxOf(1, bitmap.height / 2), true)
                try { output.reset(); check(small.compress(Bitmap.CompressFormat.JPEG, 65, output)) }
                finally { if (small !== bitmap) small.recycle() }
            }
            check(workspace == null || output.size() <= CloudPhoto.MAX_JPEG)
            val at = maxOf(System.currentTimeMillis(), destination.lastModified() + 1)
            atomicWrite(destination, output.toByteArray(), at)
            true
        } catch (_: Exception) { false } finally { bitmap.recycle() }
        return success
    }
    private fun atomicWrite(destination: File, bytes: ByteArray, at: Long) {
        val atomic = AtomicFile(destination)
        val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
        check(destination.setLastModified(at)) { "Photo timestamp not saved" }
    }

    @Synchronized fun cloudPhoto(context: Context, id: String, workspace: String): CloudPhoto? {
        val f = file(context, id, workspace)
        if (!f.exists()) return null
        require(f.length() in 4L..4_000_000L)
        val bytes = if (f.length() <= CloudPhoto.MAX_JPEG) f.readBytes() else {
            // Existing pre-cloud photos are reduced in memory, without replacing the original.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            require(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024)
            val original = requireNotNull(BitmapFactory.decodeFile(f.path))
            val scaled = Bitmap.createScaledBitmap(original, maxOf(1, original.width / 2), maxOf(1, original.height / 2), true)
            try {
                ByteArrayOutputStream().also {
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 60, it))
                    require(it.size() <= CloudPhoto.MAX_JPEG)
                }.toByteArray()
            } finally {
                if (scaled !== original) scaled.recycle()
                original.recycle()
            }
        }
        return CloudPhoto(id, f.lastModified(), bytes)
    }

    suspend fun installCloudPhoto(context: Context, photo: CloudPhoto, workspace: String) {
        val saved = withContext(Dispatchers.IO) {
            synchronized(this@CustomProductPreviews) {
                val current = cloudPhoto(context, photo.product, workspace)
                if (current != null && current.compare(photo) >= 0) return@synchronized false
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, bounds)
                require(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024 && bounds.outMimeType == "image/jpeg")
                atomicWrite(file(context, photo.product, workspace), photo.jpeg, photo.at)
                true
            }
        }
        if (saved) withContext(Dispatchers.Main) { revisions["$workspace/${photo.product}"] = version(photo.product, workspace) + 1 }
    }

    suspend fun setFromUri(context: Context, id: String, uri: Uri, workspace: String? = null): Boolean = withContext(Dispatchers.IO) {
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
        val saved = save(context, id, bytes, workspace)
        if (saved) withContext(Dispatchers.Main) { revisions["$workspace/$id"] = version(id, workspace) + 1 }
        saved
    }
}

@Composable
internal fun CustomProductImage(productId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val workspace = LocalShoppingWorkspace.current?.localKey
    val p = LocalPalette.current
    val version = CustomProductPreviews.version(productId, workspace)
    val bitmap = remember(productId, version, workspace) { CustomProductPreviews.image(context, productId, workspace) }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(p.field), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(painterResource(R.drawable.ic_product_placeholder), null, tint = p.iconMuted,
            modifier = Modifier.fillMaxSize(0.5f))
    }
}

@Composable
internal fun CustomProductImageActions(productId: String) {
    val context = LocalContext.current
    val session = LocalShoppingWorkspace.current
    val workspace = session?.localKey
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var loading by remember(productId) { mutableStateOf(false) }
    var message by remember(productId) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            val ok = CustomProductPreviews.setFromUri(context, productId, uri, workspace)
            loading = false
            message = if (ok) null else "Не удалось открыть фото"
            if (ok && session != null) (context.applicationContext as com.mok.buylist.ShoppingApp).cloudSpaces.requestSync(session)
        }
    }
    Column {
        Column(horizontalAlignment = Alignment.Start) {
            TextButton(onClick = { picker.launch("image/*") }, enabled = !loading) {
                Text("Выбрать фото", color = p.accent)
            }
        }
        message?.let { Text(it, color = p.textSecondary, fontSize = 12.sp) }
    }
}
