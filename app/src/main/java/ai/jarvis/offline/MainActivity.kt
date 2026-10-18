package ai.jarvis.offline

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : Activity() {
    private var offlineTts: OfflineTts? = null
    private var recognizer: SpeechRecognizer? = null
    private lateinit var status: TextView
    private lateinit var listenButton: Button
    @Volatile private var voiceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        status.text = "Подготавливаю офлайн-голос…"
        Thread {
            runCatching { initializeOfflineVoice() }
                .onSuccess {
                    voiceReady = true
                    runOnUiThread { status.text = "Офлайн-голос Димитри готов. Интернет не требуется." }
                }
                .onFailure { error ->
                    runOnUiThread { status.text = "Не удалось загрузить голосовую модель: ${error.message}" }
                }
        }.start()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { status.text = "Слушаю…" }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { status.text = "Обрабатываю речь…" }
                    override fun onError(error: Int) {
                        status.text = "Распознавание недоступно (код $error). Для офлайн-режима установи локальный русский пакет распознавания Android."
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

    private fun initializeOfflineVoice() {
        val modelFolder = "vits-piper-ru_RU-dmitri-medium"
        val destination = File(filesDir, modelFolder)
        val model = File(destination, "ru_RU-dmitri-medium.onnx")
        if (!model.exists()) {
            copyAssetTree(modelFolder, destination)
        }
        check(model.exists()) { "Файл модели не найден: ${model.absolutePath}" }
        val tokens = File(destination, "tokens.txt")
        val espeakData = File(destination, "espeak-ng-data")
        check(tokens.exists()) { "Не найден tokens.txt" }
        check(espeakData.isDirectory) { "Не найдены данные espeak-ng" }

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = espeakData.absolutePath
                ),
                numThreads = 2
            )
        )
        offlineTts = OfflineTts(config = config)
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        } else {
            destination.mkdirs()
            children.forEach { child -> copyAssetTree("$assetPath/$child", File(destination, child)) }
        }
    }

    private fun buildUi() {
        window.statusBarColor = 0xFF050509.toInt()
        window.navigationBarColor = 0xFF050509.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 26, 24, 24)
            setBackgroundColor(0xFF050509.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }
        val eyebrow = TextView(this).apply {
            text = "PERSONAL AI  /  OFFLINE VOICE"
            textSize = 11f
            letterSpacing = 0.18f
            setTextColor(0xFF00F5FF.toInt())
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "J A R V I S"
            textSize = 32f
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            setTextColor(0xFF00F5FF.toInt())
            gravity = Gravity.CENTER
            setShadowLayer(18f, 0f, 0f, 0xFF00D9FF.toInt())
            setPadding(0, 12, 0, 4)
        }
        val subtitle = TextView(this).apply {
            text = "ГОЛОСОВОЙ ИНТЕРФЕЙС"
            textSize = 11f
            letterSpacing = 0.24f
            setTextColor(0xFFB44CFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 22)
        }
        status = TextView(this).apply {
            text = "Подготавливаю голосовую систему…"
            textSize = 15f
            setTextColor(0xFFD7D9E8.toInt())
            gravity = Gravity.CENTER
            setPadding(16, 20, 16, 20)
            background = roundedPanel(0xFF0D101B.toInt(), 0xFF263A58.toInt(), 18)
        }
        listenButton = neonButton("◉   ГОЛОСОВОЙ ДИАЛОГ", 0xFF00F5FF.toInt(), 0xFF061116.toInt()).apply {
            setOnClickListener { startListening() }
        }
        val backgroundButton = neonButton("⌁   ФОНОВОЕ ПРОСЛУШИВАНИЕ", 0xFFB44CFF.toInt(), 0xFF12091A.toInt()).apply {
            setOnClickListener { startWakeListening() }
        }
        val camera = neonButton("КАМЕРА", 0xFF00F5FF.toInt(), 0xFF061116.toInt()).apply {
            setOnClickListener { launchCamera() }
        }
        val music = neonButton("МУЗЫКА", 0xFFB44CFF.toInt(), 0xFF12091A.toInt()).apply {
            setOnClickListener { launchMusic() }
        }
        val hint = TextView(this).apply {
            text = "Скажи «Джарвис» и команду. Для фонового режима приложение покажет постоянное уведомление."
            textSize = 12f
            setTextColor(0xFF858AA4.toInt())
            gravity = Gravity.CENTER
            setPadding(12, 18, 12, 6)
        }
        content.addView(eyebrow, matchWidth())
        content.addView(title, matchWidth())
        content.addView(subtitle, matchWidth())
        content.addView(status, matchWidth())
        content.addView(listenButton, matchWidth())
        content.addView(backgroundButton, matchWidth())
        content.addView(camera, matchWidth())
        content.addView(music, matchWidth())
        content.addView(hint, matchWidth())
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun roundedPanel(color: Int, stroke: Int, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            setStroke((1.2f * resources.displayMetrics.density).toInt(), stroke)
        }

    private fun neonButton(label: String, neon: Int, fill: Int) = Button(this).apply {
        text = label
        textSize = 13f
        letterSpacing = 0.04f
        isAllCaps = false
        setTextColor(neon)
        background = roundedPanel(fill, neon, 48)
        elevation = 0f
        setPadding(18, 18, 18, 18)
        minHeight = (58 * resources.displayMetrics.density).toInt()
        stateListAnimator = null
    }

    private fun startWakeListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            status.text = "Разреши доступ к микрофону, затем включи фоновое прослушивание."
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
        }
        try {
            val serviceIntent = Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_START)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            status.text = "Фоновое прослушивание запущено. Скажи «Джарвис»."
        } catch (e: Exception) {
            status.text = "Не удалось запустить фоновый микрофон: ${e.message}"
        }
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
            phrase.contains("привет") || phrase.contains("джарвис") -> speak("На связи. Голос работает локально.")
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
        if (!voiceReady) {
            status.text = "Голосовая модель ещё загружается."
            return
        }
        Thread {
            runCatching {
                val audio = offlineTts!!.generate(text = text, sid = 0, speed = 1.0f)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(audio.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .build()
                    )
                    .setBufferSizeInBytes(16384)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                var offset = 0
                while (offset < audio.samples.size) {
                    val count = minOf(4096, audio.samples.size - offset)
                    track.write(audio.samples, offset, count, AudioTrack.WRITE_BLOCKING)
                    offset += count
                }
                track.stop()
                track.release()
            }.onFailure { error ->
                runOnUiThread { status.text = "Ошибка синтеза голоса: ${error.message}" }
            }
        }.start()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        offlineTts?.release()
        offlineTts = null
        super.onDestroy()
    }
}
