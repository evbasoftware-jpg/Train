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
  private var configuredForLang: String? = null
  private var pendingText: String? = null

  private fun ensureInitialized() {
    if (tts != null) return
    try {
      // Prefer Google Speech Services engine for natural Indian/global female voices
      val listener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
          isReady = true
          configuredForLang = null
          configureVoiceIfNeeded(currentLangCode)
          pendingText?.let { text ->
            pendingText = null
            speakNow(text)
          }
        } else {
          // Retry once with system default TTS engine if Google engine wasn't available
          if (tts != null) {
            try {
              tts?.shutdown()
            } catch (_: Throwable) {
            }
            tts = TextToSpeech(appContext) { fallbackStatus ->
              isReady = (fallbackStatus == TextToSpeech.SUCCESS)
              if (isReady) {
                configuredForLang = null
                configureVoiceIfNeeded(currentLangCode)
                pendingText?.let { text ->
                  pendingText = null
                  speakNow(text)
                }
              }
            }
          }
        }
      }
      tts = TextToSpeech(appContext, listener, "com.google.android.tts")
    } catch (_: Throwable) {
      isReady = false
    }
  }

  fun announce(text: String, languageCode: String) {
    if (text.isBlank()) return
    val nextLang = languageCode.ifBlank { "bn" }
    val langChanged = nextLang != currentLangCode
    currentLangCode = nextLang
    ensureInitialized()
    if (!isReady) {
      pendingText = text
      return
    }
    if (langChanged || configuredForLang != currentLangCode) {
      configureVoiceIfNeeded(currentLangCode)
    }
    speakNow(text)
  }

  fun setLanguage(languageCode: String) {
    val clean = languageCode.ifBlank { "bn" }
    if (clean != currentLangCode) {
      currentLangCode = clean
      configuredForLang = null
      if (isReady) {
        configureVoiceIfNeeded(clean)
      }
    }
  }

  fun stop() {
    pendingText = null
    try {
      tts?.stop()
    } catch (_: Throwable) {
    }
  }

  private fun configureVoiceIfNeeded(langCode: String) {
    val engine = tts ?: return
    if (configuredForLang == langCode) return
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

      // Soft, pleasant, slightly elevated female cadence
      engine.setPitch(1.18f)
      engine.setSpeechRate(0.84f)
      engine.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )

      val targetLang = chosenLocale?.language ?: preferredLocale.language
      val availableVoices = engine.voices
      if (!availableVoices.isNullOrEmpty()) {
        val femaleVoice = selectSweetFemaleVoice(availableVoices, targetLang)
        if (femaleVoice != null) {
          engine.voice = femaleVoice
        }
      }
      configuredForLang = langCode
    } catch (_: Throwable) {
    }
  }

  private fun selectSweetFemaleVoice(voices: Set<Voice>, targetLanguage: String): Voice? {
    val langMatches = voices.filter {
      it.locale.language.equals(targetLanguage, ignoreCase = true)
    }
    if (langMatches.isEmpty()) return null

    // Google TTS voice codes: bnf / ban / bnd = Bengali Female; hia / hic / hie / cfn = Hindi Female;
    // end / enc / ena / sfg / tpf / iog / gba = English Female.
    // bnm / hid / ene / enm / iob / iom / gbd = Male voices to avoid.
    val femaleHints = listOf(
      "female", "woman", "girl", "f00", "f01", "f02", "-f-", "_f_",
      "bnf", "bnd", "ban", "hia", "hic", "hie", "cfn",
      "end", "enc", "ena", "sfg", "tpf", "iog", "tpd", "gba", "gbb",
      "wavenet-a", "wavenet-c", "wavenet-e", "neural2-a", "neural2-c", "standard-a", "standard-c",
    )
    val maleHints = listOf(
      "male", "man", "boy", "m00", "m01", "-m-", "_m_",
      "bnm", "hid", "ene", "enm", "iob", "iom", "gbd",
      "wavenet-b", "wavenet-d", "neural2-d", "standard-b", "standard-d",
    )

    fun score(v: Voice): Int {
      val name = v.name.lowercase(Locale.US)
      val feats = v.features?.joinToString(" ")?.lowercase(Locale.US) ?: ""
      var s = 0
      if (femaleHints.any { name.contains(it) || feats.contains(it) }) s += 100
      if (maleHints.any { name.contains(it) || feats.contains(it) }) s -= 120
      if (!v.isNetworkConnectionRequired) s += 15
      if (v.quality >= Voice.QUALITY_HIGH) s += 10
      return s
    }

    return langMatches.maxByOrNull { score(it) }
  }

  private fun speakNow(text: String) {
    val engine = tts ?: return
    try {
      val params = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.88f)
      }
      engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "train_stop_${System.currentTimeMillis()}")
    } catch (_: Throwable) {
    }
  }
}
