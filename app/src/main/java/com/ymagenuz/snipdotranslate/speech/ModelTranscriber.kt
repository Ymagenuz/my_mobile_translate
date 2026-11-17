package com.ymagenuz.snipdotranslate.speech

import com.ymagenuz.snipdotranslate.SnipDoApp
import com.ymagenuz.snipdotranslate.api.audioMessageContent
import com.ymagenuz.snipdotranslate.core.buildTranscriptionPrompt
import com.ymagenuz.snipdotranslate.core.transcriptionText
import com.ymagenuz.snipdotranslate.domain.ProviderAccess
import java.io.IOException

sealed interface Transcription {
    /** What was said; empty when the model heard no words. */
    data class Text(val text: String) : Transcription

    /** No API key or model, or Supports audio input is off. */
    data object NotReady : Transcription

    data class Failed(val message: String) : Transcription
}

/** Writes down a recorded sentence with the translation model, as OCR reads an image with it. */
suspend fun transcribeWithModel(app: SnipDoApp, wav: ByteArray, language: String): Transcription {
    val access = app.providerAccess() as? ProviderAccess.Ready ?: return Transcription.NotReady
    if (!access.provider.supportsAudio) return Transcription.NotReady
    return try {
        val content = audioMessageContent(buildTranscriptionPrompt(language), wav)
        Transcription.Text(transcriptionText(app.chatClient.complete(access.provider, access.apiKey, content)))
    } catch (failure: IOException) {
        Transcription.Failed(failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName)
    }
}
