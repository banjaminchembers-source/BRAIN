package ai.jarvis.offline

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private lateinit var status: TextView
    private lateinit var listenButton: Button
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
        }
        status.text = "Джарвис готов. Пока используется системный TTS."
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { status.text = "Слушаю…" }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { status.text = "Обрабатываю речь…" }
                    override fun onError(error: Int) {
                        status.text = "Офлайн-распознавание недоступно (код $error). Проверь локальный языковой пакет Android."
                        listenButton.isEnabled = true
                    }
                    override fun onResults(results: Bundle?) {
                        listenButton.isEnabled = true
                        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (phrase.isNullOrBlank()) status.text = "Не разобрал команду."
                        else {
                            status.text = "Команда: $phrase"
                            handleCommand(phrase)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } else {
            status.text = "На устройстве нет движка распознавания речи."
            listenButton.isEnabled = false
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 28)
            setBackgroundColor(0xFF10151D.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 30f
            setTextColor(0xFFEAF2FF.toInt())
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFB9C7D9.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        listenButton = Button(this).apply {
            text = "Голосовой диалог"
            textSize = 18f
            setOnClickListener { startListening() }
        }
        val camera = Button(this).apply {
            text = "Открыть камеру"
            setOnClickListener { launchCamera() }
        }
        val music = Button(this).apply {
            text = "Открыть музыку"
            setOnClickListener { launchMusic() }
        }
        content.addView(title, matchWidth())
        content.addView(status, matchWidth())
        content.addView(listenButton, matchWidth())
        content.addView(camera, matchWidth())
        content.addView(music, matchWidth())
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 12 }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        val sr = recognizer ?: run {
            status.text = "Нет движка распознавания речи."
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        listenButton.isEnabled = false
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            listenButton.isEnabled = true
            status.text = "Не удалось запустить распознавание."
        }
    }

    private fun handleCommand(raw: String) {
        val phrase = raw.lowercase(Locale("ru", "RU"))
        when {
            phrase.contains("камер") || phrase.contains("фото") -> launchCamera()
            phrase.contains("музык") || phrase.contains("песн") -> launchMusic()
            phrase.contains("позвони") || phrase.contains("набери") || phrase.contains("вызов") -> {
                speak("Открываю набор номера. Проверь номер и подтверди вызов.")
                startActivity(Intent(Intent.ACTION_DIAL))
            }
            phrase.contains("привет") || phrase.contains("джарвис") -> speak("На связи. Работаю в пределах возможностей устройства.")
            else -> speak("Эта команда пока не добавлена в локальный список.")
        }
    }

    private fun launchCamera() {
        runCatching { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
            .onFailure { speak("Камера недоступна.") }
    }

    private fun launchMusic() {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        runCatching { startActivity(intent) }
            .onFailure { speak("Музыкальное приложение не найдено.") }
    }

    private fun speak(text: String) {
        status.text = text
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-" + System.nanoTime())
    }

    override fun onInit(statusCode: Int) {
        ttsReady = statusCode == TextToSpeech.SUCCESS
        if (ttsReady) {
            val result = tts?.setLanguage(Locale("ru", "RU"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                status.text = "Русский голос TTS не установлен на устройстве."
            }
        } else {
            status.text = "Системный TTS не запустился."
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
