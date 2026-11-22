package com.mok.buylist.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.mok.buylist.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val VOICE_BARS = 17
private const val MAX_ALTERNATIVES = 5

/** Голосовой ввод через системную службу распознавания речи; уровни звука рисуют волну в шторке. */
@Stable
class VoiceInput(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (alternatives: List<String>) -> Unit,
) {
    private val context = context.applicationContext

    /**
     * Есть ли на телефоне служба распознавания речи. Проверка опрашивает систему (это не мгновенно),
     * поэтому делается в фоне — иначе открытие шторки с клавиатурой подтормаживает.
     */
    var available by mutableStateOf(false)
        private set

    internal suspend fun checkAvailability() {
        available = withContext(Dispatchers.Default) { SpeechRecognizer.isRecognitionAvailable(context) }
    }

    var listening by mutableStateOf(false)
        private set

    /** Последние уровни громкости, 0..1, от старых к новым. */
    var levels by mutableStateOf(List(VOICE_BARS) { 0f })
        private set

    var message by mutableStateOf<Int?>(null)
        private set

    internal var requestPermission: () -> Unit = {}

    private var recognizer: SpeechRecognizer? = null

    fun toggle() {
        when {
            listening -> stop()
            hasPermission() -> start()
            else -> requestPermission()
        }
    }

    fun start() {
        if (!available) return
        message = null
        release()
        val created = SpeechRecognizer.createSpeechRecognizer(context)
        created.setRecognitionListener(listener)
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Несколько вариантов: из них база товаров выберет тот, что похож на настоящий товар.
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_ALTERNATIVES),
        )
        recognizer = created
        listening = true
    }

    /** Останавливает запись; уже услышанное придёт в onResults. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        finishListening()
    }

    internal fun permissionDenied() {
        message = R.string.voice_permission_denied
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun finishListening() {
        listening = false
        levels = List(VOICE_BARS) { 0f }
    }

    private fun heardTexts(bundle: Bundle?): List<String> =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }

    private val listener = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB примерно от -2 (тишина) до 10 (громкая речь).
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            levels = levels.drop(1) + level
        }

        override fun onPartialResults(partialResults: Bundle?) {
            heardTexts(partialResults).firstOrNull()?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            val heard = heardTexts(results)
            if (heard.isNotEmpty()) onFinal(heard)
            finishListening()
        }

        override fun onError(error: Int) {
            finishListening()
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                message = R.string.voice_error
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

@Composable
fun rememberVoiceInput(onPartial: (String) -> Unit, onFinal: (List<String>) -> Unit): VoiceInput {
    val context = LocalContext.current
    val currentOnPartial by rememberUpdatedState(onPartial)
    val currentOnFinal by rememberUpdatedState(onFinal)
    val voice = remember { VoiceInput(context, { currentOnPartial(it) }, { currentOnFinal(it) }) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else voice.permissionDenied()
    }
    voice.requestPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    LaunchedEffect(voice) { voice.checkAvailability() }
    return voice
}
