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
import android.provider.Settings
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
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
    private var voiceOrb: View? = null
    @Volatile private var voiceReady = false
    private var waitingForOverlayPermission = false

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

        requestAllRuntimePermissions()
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { status.text = "Слушаю…" }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) { setOrbLevel(((rmsdB + 2f) / 14f).coerceIn(0f, 1f) * 0.10f) }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { status.text = "Обрабатываю речь…" }
                    override fun onError(error: Int) {
                        status.text = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Ошибка микрофона. Проверь разрешение и отключи Bluetooth-гарнитуру для проверки."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон. Разреши его в настройках Android."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет связи с распознаванием речи. Проверь интернет или установи русский офлайн-пакет распознавания."
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал речь. Нажми кнопку и говори сразу после сигнала."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято. Подожди секунду и повтори."
                            else -> "Ошибка распознавания (код $error). Проверь разрешение на микрофон и службу распознавания Android."
                        }
                        listenButton.isEnabled = true
                        setOrbLevel(0f)
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
        val black = 0xFF050705.toInt()
        val lime = 0xFFB8FF43.toInt()
        val softLime = 0xFFDFFF9B.toInt()
        val muted = 0xFF879777.toInt()
        window.statusBarColor = black
        window.navigationBarColor = black
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(16))
            setBackgroundColor(black)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(18))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(14))
        }
        val logoMark = TextView(this).apply {
            text = "J"
            textSize = 24f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setTextColor(softLime)
            background = glossyCircle(0xFF18250D.toInt(), lime, 0xFF38571B.toInt())
            elevation = dp(8).toFloat()
        }
        header.addView(logoMark, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(TextView(this).apply {
            text = "JARVIS"
            textSize = 21f
            letterSpacing = 0.18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setTextColor(softLime)
        })
        brandText.addView(TextView(this).apply {
            text = "VOICE INTELLIGENCE"
            textSize = 9f
            letterSpacing = 0.19f
            setTextColor(muted)
        })
        header.addView(brandText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val gear = Button(this).apply {
            text = "⚙"
            textSize = 25f
            gravity = Gravity.CENTER
            isAllCaps = false
            contentDescription = "Системные настройки Jarvis"
            setTextColor(0xFFCFFF8B.toInt())
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF29391A.toInt(), 0xFF10170B.toInt(), 0xFF080C07.toInt())
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF587C30.toInt())
            }
            elevation = dp(5).toFloat()
            stateListAnimator = null
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.onFailure { status.text = "Не удалось открыть системные настройки." }
            }
        }
        header.addView(gear, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        val redIndicator = TextView(this).apply {
            text = "●"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFFF3B45.toInt())
            setShadowLayer(dp(6).toFloat(), 0f, 0f, 0xFFFF2635.toInt())
            contentDescription = "Индикатор"
        }
        header.addView(redIndicator, LinearLayout.LayoutParams(dp(18), dp(24)).apply { marginStart = dp(3) })
        content.addView(header, matchWidth())

        val topLine = TextView(this).apply {
            text = "YOUR PERSONAL VOICE ASSISTANT"
            textSize = 10f
            letterSpacing = 0.22f
            gravity = Gravity.CENTER
            setTextColor(0xFF77915B.toInt())
            setPadding(0, dp(10), 0, dp(4))
        }
        content.addView(topLine, matchWidth())

        val orbWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, dp(16))
        }
        val outer = TextView(this).apply {
            gravity = Gravity.CENTER
            background = glossyCircle(0xFF0C1308.toInt(), 0xFF4B7028.toInt(), 0xFF17240D.toInt())
            elevation = dp(10).toFloat()
        }
        val middle = TextView(this).apply {
            gravity = Gravity.CENTER
            background = glossyCircle(0xFF18260C.toInt(), 0xFF9FE83B.toInt(), 0xFF263D12.toInt())
        }
        val core = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = glossyCircle(0xFF0A0E08.toInt(), 0xFFD5FF88.toInt(), 0xFF1A2B0D.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        val glyph = TextView(this).apply {
            text = "J"
            textSize = 64f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            setTextColor(softLime)
            setShadowLayer(dp(18).toFloat(), 0f, 0f, 0xFF8DE52A.toInt())
        }
        val orbCaption = TextView(this).apply {
            text = "VOICE CORE"
            textSize = 8f
            letterSpacing = 0.24f
            gravity = Gravity.CENTER
            setTextColor(0xFF9CC66A.toInt())
        }
        core.addView(glyph, LinearLayout.LayoutParams(dp(112), dp(88)))
        core.addView(orbCaption)
        middle.addView(core, LinearLayout.LayoutParams(dp(142), dp(142)).apply { gravity = Gravity.CENTER })
        outer.addView(middle, LinearLayout.LayoutParams(dp(166), dp(166)).apply { gravity = Gravity.CENTER })
        voiceOrb = outer
        orbWrap.addView(outer, LinearLayout.LayoutParams(dp(190), dp(190)).apply { gravity = Gravity.CENTER })
        content.addView(orbWrap, matchWidth())

        val headline = TextView(this).apply {
            text = "Я тебя слушаю"
            textSize = 24f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFFE7F6D5.toInt())
            setPadding(0, dp(4), 0, dp(4))
        }
        content.addView(headline, matchWidth())
        val subline = TextView(this).apply {
            text = "Нажми на диалог и говори естественно"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(dp(8), 0, dp(8), dp(18))
        }
        content.addView(subline, matchWidth())

        status = TextView(this).apply {
            text = "Подготавливаю офлайн-голос…"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFD8E9C8.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedPanel(0xFF0C1109.toInt(), 0xFF293D1A.toInt(), 18)
        }
        content.addView(status, matchWidth())

        listenButton = neonButton("●   ГОЛОСОВОЙ ДИАЛОГ", true).apply {
            setOnClickListener { startListening() }
        }
        content.addView(listenButton, matchWidth())

        val backgroundButton = neonButton("◌   ФОНОВОЕ ПРОСЛУШИВАНИЕ", false).apply {
            setOnClickListener { startWakeListening() }
        }
        content.addView(backgroundButton, matchWidth())

        val floating = neonButton("⬡   ПЛАВАЮЩАЯ КНОПКА", false).apply {
            setOnClickListener { enableFloatingVoiceButton() }
        }
        content.addView(floating, matchWidth())

        val quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val camera = neonButton("КАМЕРА", false).apply {
            setOnClickListener { launchCamera() }
        }
        val music = neonButton("МУЗЫКА", false).apply {
            setOnClickListener { launchMusic() }
        }
        quickActions.addView(camera, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
        quickActions.addView(music, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) })
        content.addView(quickActions, matchWidth())

        val hint = TextView(this).apply {
            text = "Скажи «Джарвис» для активации в фоновом режиме"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF66745A.toInt())
            setPadding(dp(12), dp(16), dp(12), dp(4))
        }
        content.addView(hint, matchWidth())

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun setOrbLevel(level: Float) {
        runOnUiThread {
            val amount = level.coerceIn(0f, 0.16f)
            voiceOrb?.animate()?.cancel()
            voiceOrb?.animate()?.scaleX(1f + amount)?.scaleY(1f + amount)
                ?.setDuration(85L)?.start()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun glossyCircle(base: Int, edge: Int, shade: Int) =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(edge, base, shade, 0xFF070A06.toInt())
        ).apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setStroke(dp(1), edge)
        }

        private fun roundedPanel(color: Int, stroke: Int, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            setStroke((1.2f * resources.displayMetrics.density).toInt(), stroke)
        }

    private fun neonButton(label: String, primary: Boolean) = Button(this).apply {
        text = label
        textSize = if (primary) 14f else 11f
        letterSpacing = if (primary) 0.06f else 0.025f
        isAllCaps = false
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        setTextColor(if (primary) 0xFF101707.toInt() else 0xFFCFFF8B.toInt())
        background = if (primary) {
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFE0FF9A.toInt(), 0xFFB8FF43.toInt(), 0xFF78B922.toInt())
            ).apply {
                cornerRadius = dp(32).toFloat()
                setStroke(dp(1), 0xFFDFFF9B.toInt())
            }
        } else {
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1B2810.toInt(), 0xFF10170B.toInt(), 0xFF080C07.toInt())
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0xFF557A2D.toInt())
            }
        }
        elevation = if (primary) dp(7).toFloat() else dp(2).toFloat()
        setPadding(dp(16), dp(14), dp(16), dp(14))
        minHeight = dp(if (primary) 62 else 54)
        stateListAnimator = null
    }

    private fun requestAllRuntimePermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.READ_MEDIA_AUDIO
            wanted += Manifest.permission.READ_MEDIA_IMAGES
            wanted += Manifest.permission.READ_MEDIA_VIDEO
            wanted += Manifest.permission.POST_NOTIFICATIONS
        } else {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
            if (android.os.Build.VERSION.SDK_INT <= 28) wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing = wanted.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 420)
    }

    private fun enableFloatingVoiceButton() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Разреши Джарвису отображаться поверх других приложений."
            waitingForOverlayPermission = true
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(intent) }
            return
        }
        val serviceIntent = Intent(this, FloatingVoiceService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            status.text = "Плавающая кнопка включена. Она останется поверх других приложений."
        } catch (e: Exception) {
            status.text = "Не удалось включить плавающую кнопку: ${e.message}"
        }
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
        setOrbLevel(0.035f)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
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
            phrase.contains("камер") || phrase.contains("фото") || phrase.contains("сфот") -> launchCamera()
            phrase.contains("музык") || phrase.contains("песн") || phrase.contains("трек") || phrase.contains("медиаплеер") -> launchMusic()
            phrase.contains("позвони") || phrase.contains("набери") || phrase.contains("вызов") || phrase.contains("телефон") -> {
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
                    val count = minOf(1024, audio.samples.size - offset)
                    var sumSquares = 0.0
                    for (i in offset until minOf(offset + count, audio.samples.size)) {
                        val sample = audio.samples[i].toDouble()
                        sumSquares += sample * sample
                    }
                    val rms = kotlin.math.sqrt(sumSquares / count.coerceAtLeast(1)).toFloat()
                    runOnUiThread { setOrbLevel((rms * 0.55f).coerceIn(0f, 0.14f)) }
                    track.write(audio.samples, offset, count, AudioTrack.WRITE_BLOCKING)
                    offset += count
                }
                track.stop()
                runOnUiThread { setOrbLevel(0f) }
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
