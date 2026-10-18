package ai.jarvis.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * User-started microphone foreground service. Uses Android's installed speech
 * recognition provider, not a dedicated offline wake-word model. Offline operation
 * depends on the device provider and its downloaded language pack.
 */
class WakeWordService : Service() {
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    @Volatile private var directCommandMode = false
    private var restarting = false
    @Volatile private var speaking = false
    @Volatile private var offlineTts: OfflineTts? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Ожидаю слово «Джарвис»")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        directCommandMode = intent?.action == ACTION_DIRECT
        updateNotification(if (directCommandMode) "Скажи команду сейчас" else "Ожидаю слово «Джарвис»")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("На устройстве нет распознавания речи")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            startRecognizer()
        }
        return START_STICKY
    }

    private fun startRecognizer() {
        if (!running || restarting) return
        restarting = true
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        restarting = false
                        updateNotification("Слушаю. Жду «Джарвис»")
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        restarting = false
                        val message = when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону. Разреши его в настройках Android"
                            SpeechRecognizer.ERROR_AUDIO -> "Ошибка аудио. Проверь микрофон и Bluetooth-гарнитуру"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Распознавание не подключилось. Проверь интернет или русский офлайн-пакет"
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал. Скажи команду чётко и ближе к микрофону"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, повторяю запуск"
                            else -> "Ошибка распознавания: $error. Повторяю запуск"
                        }
                        updateNotification(message)
                        if (running && !speaking) restartSoon()
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        restarting = false
                        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty().lowercase(Locale("ru", "RU"))
                        processPhrase(phrase)
                        if (running) restartSoon()
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                startListening(speechIntent)
            }
        } catch (e: Exception) {
            restarting = false
            Log.e(TAG, "Could not start speech recognizer", e)
            restartSoon()
        }
    }

    private fun processPhrase(phrase: String) {
        if (phrase.isBlank()) return
        if (directCommandMode) {
            directCommandMode = false
            executeCommand(phrase)
            return
        }
        val wakeWords = listOf("джарвис", "джарвес", "жарвис", "jarvis")
        val wake = wakeWords.map { it to phrase.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
            ?: return
        val command = phrase.substring(wake.second + wake.first.length).trim()
        if (command.isBlank()) {
            updateNotification("Слушаю, сэр!")
            speakOffline("Слушаю, сэр!")
            return
        }
        executeCommand(command)
    }

    private fun executeCommand(command: String) {
        when {
            command.contains("камер") || command.contains("фото") || command.contains("сфот") -> {
                speakOffline("Открываю камеру.")
                openApp(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            }
            command.contains("музык") || command.contains("песн") || command.contains("трек") || command.contains("плеер") -> {
                speakOffline("Открываю музыкальный плеер.")
                openApp(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
            }
            command.contains("позвони") || command.contains("набери") || command.contains("вызов") || command.contains("телефон") -> {
                updateNotification("Открыл набор номера. Проверь номер и подтверди вызов")
                speakOffline("Открываю набор номера. Проверь номер и подтверди вызов.")
                openApp(Intent(Intent.ACTION_DIAL))
            }
            command.contains("стоп") || command.contains("останови") ->
                updateNotification("Для остановки нажми «Выключить» в уведомлении")
            else -> {
                updateNotification("Услышал: $command. Пока поддерживаются камера, музыка и набор номера")
                speakOffline("Я услышал команду, но пока не умею её выполнять. Сейчас доступны камера, музыка и набор номера.")
            }
        }
    }

    private fun openApp(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            updateNotification("Команда отправлена")
        } catch (e: Exception) {
            updateNotification("Команда распознана. Разблокируйте телефон для открытия приложения")
        }
    }

    private fun speakOffline(text: String) {
        if (speaking) return
        speaking = true
        Thread {
            try {
                if (offlineTts == null) initializeOfflineVoice()
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
                    MainActivity.reportExternalVoiceLevel((rms * 0.55f).coerceIn(0f, 0.14f))
                    track.write(audio.samples, offset, count, AudioTrack.WRITE_BLOCKING)
                    offset += count
                }
                track.stop()
                track.release()
                MainActivity.reportExternalVoiceLevel(0f)
            } catch (e: Exception) {
                MainActivity.reportExternalVoiceLevel(0f)
                Log.e(TAG, "Could not speak wake response", e)
                updateNotification("Услышал «Джарвис», но не удалось произнести ответ")
            } finally {
                speaking = false
                if (running) android.os.Handler(mainLooper).postDelayed({ if (running) startRecognizer() }, 350)
            }
        }.start()
    }

    private fun initializeOfflineVoice() {
        val modelFolder = "vits-piper-ru_RU-dmitri-medium"
        val destination = File(filesDir, modelFolder)
        val model = File(destination, "ru_RU-dmitri-medium.onnx")
        if (!model.exists()) copyAssetTree(modelFolder, destination)
        check(model.exists()) { "Не найдена офлайн-модель голоса" }
        val tokens = File(destination, "tokens.txt")
        val espeakData = File(destination, "espeak-ng-data")
        check(tokens.exists() && espeakData.isDirectory) { "Не найдены файлы офлайн-голоса" }
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

    private fun restartSoon() {
        android.os.Handler(mainLooper).postDelayed({
            restarting = false
            if (running && !speaking) startRecognizer()
        }, 900)
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS · голосовое управление")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Выключить", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Голосовое управление Jarvis", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopListening() {
        running = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    override fun onDestroy() {
        stopListening()
        try { offlineTts?.release() } catch (_: Exception) {}
        offlineTts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ai.jarvis.offline.START_WAKE_LISTENING"
        const val ACTION_DIRECT = "ai.jarvis.offline.START_DIRECT_COMMAND"
        const val ACTION_STOP = "ai.jarvis.offline.STOP_WAKE_LISTENING"
        private const val CHANNEL_ID = "jarvis_voice_control"
        private const val NOTIFICATION_ID = 7104
        private const val TAG = "WakeWordService"
    }
}