package com.ymagenuz.snipdotranslate.speech

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ymagenuz.snipdotranslate.core.KNOWN_ENGINE_LANGUAGES
import com.ymagenuz.snipdotranslate.core.recognizedLanguageCodes
import com.ymagenuz.snipdotranslate.domain.SpeechEngine
import com.ymagenuz.snipdotranslate.domain.SpeechError
import com.ymagenuz.snipdotranslate.domain.SpeechInput
import com.ymagenuz.snipdotranslate.domain.SpeechListener
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

// Added in Android 12; the values are fixed, so they also work when read on older versions.
private const val ERROR_TOO_MANY_REQUESTS = 10
private const val ERROR_SERVER_DISCONNECTED = 11
private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
private const val ERROR_LANGUAGE_UNAVAILABLE = 13

// The secure setting naming the phone's chosen recognition service.
private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"

private const val SUPPORT_CHECK_TIMEOUT_MS = 3_000L

// Errors that say nothing about the microphone.
private val NOT_ABOUT_THE_MICROPHONE = setOf(SpeechError.NO_SPEECH, SpeechError.NETWORK, SpeechError.LANGUAGE_UNSUPPORTED)

/**
 * The phone's speech recognition service, such as Google's, which may send audio to its
 * servers. Falls back to on-device recognition where that's all the phone has.
 * Must be used on the main thread.
 */
class SystemSpeechInput(context: Context) : SpeechInput {
    private val context = context.applicationContext
    private val standard = SpeechRecognizer.isRecognitionAvailable(this.context)
    private val onDevice = !standard &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(this.context)

    override val isAvailable: Boolean = standard || onDevice

    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechListener? = null

    private fun recognizer(): SpeechRecognizer = recognizer ?: run {
        val created = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        created.setRecognitionListener(Events())
        recognizer = created
        created
    }

    override fun start(languageTag: String, listener: SpeechListener) {
        this.listener = listener
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        try {
            recognizer().startListening(intent)
        } catch (_: RuntimeException) {
            // A recognition service that went away; it's created again next time.
            recognizer?.destroy()
            recognizer = null
            this.listener = null
            listener.onError(SpeechError.TRANSIENT)
        }
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        listener = null
        recognizer?.cancel()
    }

    override fun release() {
        listener = null
        recognizer?.destroy()
        recognizer = null
    }

    override fun engineWithoutMicrophone(): SpeechEngine? {
        // On-device recognition gets its audio from Android, not a microphone of its own.
        if (onDevice) return null
        val packageName = enginePackage() ?: return null
        val manager = context.packageManager
        if (manager.checkPermission(Manifest.permission.RECORD_AUDIO, packageName) == PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val label = try {
            manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
        return SpeechEngine(label, packageName)
    }

    override suspend fun recognizedLanguages(): Set<String>? {
        if (!isAvailable) return null
        return reportedLanguages() ?: enginePackage()?.let(KNOWN_ENGINE_LANGUAGES::get)
    }

    /** What the engine itself says it can hear, on Android 13 and later, if it says. */
    private suspend fun reportedLanguages(): Set<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        // A recognizer of its own, so the check can't disturb one that's listening.
        val checker = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        return try {
            withTimeoutOrNull(SUPPORT_CHECK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    checker.checkRecognitionSupport(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                        context.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                val tags = support.installedOnDeviceLanguages + support.supportedOnDeviceLanguages +
                                    support.pendingOnDeviceLanguages + support.onlineLanguages
                                continuation.resume(recognizedLanguageCodes(tags).takeIf { it.isNotEmpty() })
                            }

                            // Most engines can't answer; Xiaomi's is one.
                            override fun onError(error: Int) = continuation.resume(null)
                        },
                    )
                }
            }
        } catch (_: RuntimeException) {
            null
        } finally {
            checker.destroy()
        }
    }

    /** The phone's chosen recognition app, or the only one installed. */
    private fun enginePackage(): String? {
        val chosen = try {
            Settings.Secure.getString(context.contentResolver, VOICE_RECOGNITION_SERVICE)
        } catch (_: SecurityException) {
            null
        }
        ComponentName.unflattenFromString(chosen.orEmpty())?.let { return it.packageName }
        return context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo.packageName }
            .distinct()
            .singleOrNull()
    }

    private inner class Events : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener?.onReady()
        }

        override fun onBeginningOfSpeech() {
            listener?.onSpeechBegin()
        }

        // Usually between about -2 dB in silence and 10 dB for speech.
        override fun onRmsChanged(rmsdB: Float) {
            listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            textOf(partialResults)?.let { listener?.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            val ended = listener ?: return
            listener = null
            ended.onResult(textOf(results).orEmpty())
        }

        override fun onError(error: Int) {
            val ended = listener ?: return
            listener = null
            val mapped = speechError(error)
            // Engines without the microphone report anything from a client error to a busy one.
            val blocked = mapped !in NOT_ABOUT_THE_MICROPHONE && engineWithoutMicrophone() != null
            ended.onError(if (blocked) SpeechError.ENGINE_MICROPHONE else mapped)
        }

        override fun onEndOfSpeech() = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun textOf(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun speechError(code: Int): SpeechError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_SPEECH
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION
        SpeechRecognizer.ERROR_AUDIO -> SpeechError.AUDIO
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        ERROR_SERVER_DISCONNECTED,
        ERROR_TOO_MANY_REQUESTS,
        -> SpeechError.NETWORK
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> SpeechError.LANGUAGE_UNSUPPORTED
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> SpeechError.TRANSIENT
        else -> SpeechError.OTHER
    }
}
