package com.mok.buylist.ui

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mok.buylist.sync.QrDecoder
import java.util.concurrent.Executors

/**
 * Камера с чтением QR. Каждый распознанный код отдаётся в [onText] с фонового потока; одинаковые коды подряд
 * не отсеиваются — это делает получатель (кадры принимаются в любом порядке и с повторами).
 */
@Composable
fun QrScanner(useFront: Boolean, onText: (String) -> Unit, modifier: Modifier = Modifier, barcode: Boolean = false,
    onCameraError: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnText by rememberUpdatedState(onText)
    val latestOnCameraError by rememberUpdatedState(onCameraError)
    val previewView = remember(barcode) { PreviewView(context).apply {
        if (barcode) {
            // TextureView respects Compose bounds and FIT_CENTER without clipping the camera frame.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        } else scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(useFront, lifecycleOwner, barcode) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener(
            {
                if (disposed) return@addListener
                val cameraProvider = try { future.get() } catch (_: Exception) { latestOnCameraError(); return@addListener }
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    // Кадр 1280×720: у QR на 65 модулей хватает точек на модуль, а распознавание остаётся быстрым.
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, QrAnalyzer(barcode) { text -> latestOnText(text) }) }
                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } catch (e: Exception) {
                    latestOnCameraError()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { disposed = true; provider?.unbindAll() }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private class QrAnalyzer(private val barcode: Boolean, private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val decoder = QrDecoder()
    private val barcodeDecoder = com.mok.buylist.sync.ProductBarcodeDecoder()
    private var previousBarcode: String? = null

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(maxOf(buffer.remaining(), plane.rowStride * image.height))
            buffer.get(data, 0, buffer.remaining())
            val value = if (barcode) barcodeDecoder.decode(data, plane.rowStride, image.width, image.height)
                else decoder.decode(data, plane.rowStride, image.width, image.height)
            if (barcode) {
                if (value != null && value == previousBarcode) onText(value)
                previousBarcode = value
            } else value?.let(onText)
        } finally {
            image.close()
        }
    }
}
