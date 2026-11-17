package com.ymagenuz.snipdotranslate.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ymagenuz.snipdotranslate.domain.SpeechError
import com.ymagenuz.snipdotranslate.domain.SpeechInput
import com.ymagenuz.snipdotranslate.domain.SpeechListener
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Without a sentence for this long, a recognition ends as silence, as the phone's do.
private const val QUIET_RECOGNITION_MS = 8_000L

// A sentence is written down over the network, which takes longer than recognition on the phone.
private const val MODEL_FINISH_TIMEOUT_MS = 20_000L

private const val OLD_RECORDER_WAIT_MS = 300L

/**
 * Hears speech through the translation model, for languages the phone's recognition can't:
 * it records, splits the audio into sentences at pauses, and sends each one to the model to
 * be written down. Recording carries on while a sentence is being written down, so a speaker
 * can keep talking; each sentence goes to the recognition that was listening when it began,
 * even if that one has since ended. Must be used on the main thread.
 */
class ModelSpeechInput(
    context: Context,
    private val transcribe: suspend (wav: ByteArray, language: String) -> Transcription,
    private val openCapture: () -> AudioCapture? = { MicrophoneCapture.open(context.applicationContext) },
) : SpeechInput {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recorder: Recorder? = null
    private var listener: SpeechListener? = null
    private var language = "en"
    private var stopping = false
    private var lastError: String? = null

    // Who the sentence being spoken right now belongs to.
    private var sentenceOwner: SpeechListener? = null
    private val sentences = ArrayDeque<Sentence>()

    private class Sentence(val owner: SpeechListener, val language: String) {
        var done = false
        var text = ""
        var error: SpeechError? = null
        var detail: String? = null
    }

    private val quiet = Runnable {
        val current = listener ?: return@Runnable
        if (sentenceOwner == null && sentences.none { it.owner === current }) {
            detach()
            current.onError(SpeechError.NO_SPEECH)
        } else {
            scheduleQuiet()
        }
    }

    override val isAvailable = true

    override val finishTimeoutMs = MODEL_FINISH_TIMEOUT_MS

    override fun lastErrorDetail(): String? = lastError

    override fun start(languageTag: String, listener: SpeechListener) {
        this.listener = listener
        language = languageTag.substringBefore('-')
        stopping = false
        val running = recorder?.takeIf { it.isAlive && !it.finishing } ?: openRecorder()
        if (running == null) {
            val failed = this.listener
            this.listener = null
            val error = if (hasMicrophone()) SpeechError.AUDIO else SpeechError.PERMISSION
            main.post { failed?.onError(error) }
            return
        }
        running.capturing = true
        main.post { if (this.listener === listener) listener.onReady() }
        scheduleQuiet()
    }

    override fun stop() {
        val current = listener ?: return
        stopping = true
        main.removeCallbacks(quiet)
        // The sentence being said is still written down and arrives as the result.
        recorder?.finish()
        if (recorder == null) finishIfNothingLeft(current)
    }

    override fun cancel() {
        // A sentence already said is still written down, for the recognition that heard it.
        listener = null
        stopping = false
        main.removeCallbacks(quiet)
        recorder?.finish()
    }

    override fun release() {
        cancel()
        sentences.clear()
        scope.cancel()
    }

    private fun hasMicrophone() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openRecorder(): Recorder? {
        // One still flushing its last sentence is a frame or two from closing.
        recorder?.join(OLD_RECORDER_WAIT_MS)
        if (!hasMicrophone()) return null
        val capture = openCapture() ?: return null
        val created = Recorder(capture)
        recorder = created
        created.start()
        return created
    }

    private fun detach() {
        listener = null
        stopping = false
        recorder?.capturing = false
        main.removeCallbacks(quiet)
    }

    private fun scheduleQuiet() {
        main.removeCallbacks(quiet)
        if (listener != null) main.postDelayed(quiet, QUIET_RECOGNITION_MS)
    }

    // From the recording thread.

    private fun onFrame(level: Float, event: VoiceSegmenter.Event?) {
        listener?.onLevel(level)
        when (event) {
            VoiceSegmenter.Event.Started -> {
                sentenceOwner = listener
                main.removeCallbacks(quiet)
                listener?.onSpeechBegin()
            }
            is VoiceSegmenter.Event.Ended -> {
                val owner = sentenceOwner
                sentenceOwner = null
                // Sound that began while nothing was listening, such as a translation read aloud.
                if (owner != null) writeDown(Sentence(owner, language), event.pcm)
                scheduleQuiet()
            }
            VoiceSegmenter.Event.Discarded -> {
                sentenceOwner = null
                scheduleQuiet()
                listener?.let(::finishIfNothingLeft)
            }
            null -> Unit
        }
    }

    private fun onRecorderFinished(finished: Recorder) {
        if (recorder === finished) recorder = null
        listener?.let(::finishIfNothingLeft)
    }

    private fun onRecorderFailed(failed: Recorder) {
        if (recorder === failed) recorder = null
        val current = listener ?: return
        detach()
        current.onError(SpeechError.AUDIO)
    }

    private fun writeDown(sentence: Sentence, pcm: ShortArray) {
        sentences.addLast(sentence)
        scope.launch {
            val wav = withContext(Dispatchers.Default) { wavBytes(pcm) }
            when (val result = transcribe(wav, sentence.language)) {
                is Transcription.Text -> sentence.text = result.text
                Transcription.NotReady -> sentence.error = SpeechError.CLOUD_NOT_READY
                is Transcription.Failed -> {
                    sentence.error = SpeechError.CLOUD_FAILED
                    sentence.detail = result.message
                }
            }
            sentence.done = true
            deliver()
        }
    }

    /** Hands written-down sentences over in the order they were said. */
    private fun deliver() {
        while (sentences.firstOrNull()?.done == true) {
            val sentence = sentences.removeFirst()
            val owner = sentence.owner
            val current = owner === listener
            val error = sentence.error
            when {
                error != null -> {
                    // The rest of that recognition's sentences would fail the same way.
                    sentences.removeAll { it.owner === owner }
                    lastError = sentence.detail
                    if (current) {
                        detach()
                        owner.onError(error)
                    }
                }
                sentence.text.isNotBlank() -> {
                    if (current) detach()
                    owner.onResult(sentence.text)
                }
            }
        }
        listener?.let(::finishIfNothingLeft)
    }

    /** After stopping, ends the recognition once nothing it heard is left to write down. */
    private fun finishIfNothingLeft(current: SpeechListener) {
        if (!stopping || recorder != null || sentenceOwner === current) return
        if (sentences.any { it.owner === current }) return
        detach()
        current.onResult("")
    }

    private inner class Recorder(private val capture: AudioCapture) : Thread("SnipDo interpretation audio") {
        @Volatile
        var capturing = false

        @Volatile
        var finishing = false

        private val segmenter = VoiceSegmenter()

        fun finish() {
            finishing = true
        }

        override fun run() {
            try {
                if (!capture.start()) {
                    main.post { onRecorderFailed(this) }
                    return
                }
                val frame = ShortArray(FRAME_SAMPLES)
                while (!finishing) {
                    var filled = 0
                    while (filled < frame.size && !finishing) {
                        val read = capture.read(frame, filled, frame.size - filled)
                        if (read < 0) {
                            main.post { onRecorderFailed(this) }
                            return
                        }
                        filled += read
                    }
                    if (filled < frame.size) break
                    val event = segmenter.accept(frame.copyOf(), capturing)
                    val level = segmenter.level
                    main.post { onFrame(level, event) }
                }
                val last = segmenter.finish()
                main.post {
                    onFrame(0f, last)
                    onRecorderFinished(this)
                }
            } finally {
                capture.close()
            }
        }
    }
}

/** Where the model's speech comes from: the microphone, or a stand-in when testing. */
interface AudioCapture {
    /** Starts capturing; false if it couldn't. */
    fun start(): Boolean

    /** Reads 16-bit mono samples at [SAMPLE_RATE], blocking; negative on failure. */
    fun read(into: ShortArray, offset: Int, count: Int): Int

    fun close()
}

/** The microphone, tuned for speech the way speech recognition uses it. */
class MicrophoneCapture private constructor(private val record: AudioRecord) : AudioCapture {
    override fun start(): Boolean = try {
        record.startRecording()
        record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    } catch (_: IllegalStateException) {
        false
    }

    override fun read(into: ShortArray, offset: Int, count: Int): Int = record.read(into, offset, count)

    override fun close() {
        try {
            record.stop()
        } catch (_: IllegalStateException) {
            // Never started.
        }
        record.release()
    }

    companion object {
        fun open(context: Context): MicrophoneCapture? {
            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!allowed) return null
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return null
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minimum, FRAME_SAMPLES * 2 * 8),
                )
            } catch (_: SecurityException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            return MicrophoneCapture(record)
        }
    }
}
