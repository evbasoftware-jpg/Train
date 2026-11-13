package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

class VoiceAnnouncer(context: Context) {
  private val appContext = context.applicationContext
  private var tts: TextToSpeech? = null
  private var isReady = false
  private var currentLangCode = "bn"
  private var pendingText: String? = null

  private fun ensureInitialized() {
    if (tts != null) return
    try {
      tts = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          isReady = true
          configureVoice(currentLangCode)
          pendingText?.let { text ->
            pendingText = null
            speakNow(text)
          }
        } else {
          isReady = false
        }
      }
    } catch (_: Throwable) {
      isReady = false
    }
  }

  fun announce(text: String, languageCode: String) {
    if (text.isBlank()) return
    currentLangCode = languageCode.ifBlank { "bn" }
    ensureInitialized()
    if (!isReady) {
      pendingText = text
      return
    }
    configureVoice(currentLangCode)
    speakNow(text)
  }

  fun stop() {
    pendingText = null
    try {
      tts?.stop()
    } catch (_: Throwable) {
    }
  }

  private fun configureVoice(langCode: String) {
    val engine = tts ?: return
    try {
      val preferredLocale = when {
        langCode.equals("bn", ignoreCase = true) -> Locale("bn", "IN")
        langCode.equals("hi", ignoreCase = true) -> Locale("hi", "IN")
        langCode.equals("en", ignoreCase = true) -> Locale("en", "IN")
        else -> Locale.forLanguageTag(langCode)
      }

      val candidates = listOf(
        preferredLocale,
        Locale("bn", "BD"),
        Locale("bn", "IN"),
        Locale("en", "IN"),
        Locale.ENGLISH,
      )

      var chosenLocale: Locale? = null
      for (loc in candidates) {
        val avail = engine.isLanguageAvailable(loc)
        if (avail >= TextToSpeech.LANG_AVAILABLE) {
          engine.language = loc
          chosenLocale = loc
          break
        }
      }

      // Soft female speech cadence
      engine.setPitch(1.12f)
      engine.setSpeechRate(0.88f)
      engine.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )

      val targetLang = chosenLocale?.language ?: preferredLocale.language
      val availableVoices = engine.voices
      if (!availableVoices.isNullOrEmpty()) {
        val femaleVoice = selectSoftFemaleVoice(availableVoices, targetLang)
        if (femaleVoice != null) {
          engine.voice = femaleVoice
        }
      }
    } catch (_: Throwable) {
    }
  }

  private fun selectSoftFemaleVoice(voices: Set<Voice>, targetLanguage: String): Voice? {
    val langMatches = voices.filter {
      it.locale.language.equals(targetLanguage, ignoreCase = true)
    }
    if (langMatches.isEmpty()) return null

    // Common Google/Android TTS female voice patterns; avoid male voice identifiers
    val femaleHints = listOf(
      "female", "f00", "-f-", "bnf", "bnd", "bnx", "hie", "hia",
      "end", "enc", "sfg", "tpf", "iog", "iol", "sfb", "woman",
    )
    val maleHints = listOf("male", "-m-", "bng", "hif", "iom", "iob", "man")

    fun score(v: Voice): Int {
      val name = v.name.lowercase(Locale.US)
      val feats = v.features?.joinToString(" ")?.lowercase(Locale.US) ?: ""
      var s = 0
      if (femaleHints.any { name.contains(it) || feats.contains(it) }) s += 50
      if (maleHints.any { name.contains(it) || feats.contains(it) }) s -= 60
      if (!v.isNetworkConnectionRequired) s += 10
      if (v.quality >= Voice.QUALITY_HIGH) s += 5
      return s
    }

    return langMatches.maxByOrNull { score(it) }
  }

  private fun speakNow(text: String) {
    val engine = tts ?: return
    try {
      val params = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.85f)
      }
      engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "train_stop_${System.currentTimeMillis()}")
    } catch (_: Throwable) {
    }
  }
}
