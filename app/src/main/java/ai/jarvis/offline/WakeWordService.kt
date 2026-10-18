package ai.jarvis.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * User-started microphone foreground service. Uses Android's installed speech
 * recognition provider, not a dedicated offline wake-word model. Offline operation
 * depends on the device provider and its downloaded language pack.
 */
class WakeWordService : Service() {
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    private var restarting = false

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
                        if (running) restartSoon()
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
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
        val wakeIndex = phrase.indexOf("джарвис")
        if (wakeIndex < 0) return
        val command = phrase.substring(wakeIndex + "джарвис".length).trim()
        if (command.isBlank()) {
            updateNotification("Слово услышано. Скажите команду")
            return
        }
        when {
            command.contains("камер") || command.contains("фото") ->
                openApp(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            command.contains("музык") || command.contains("песн") ->
                openApp(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
            command.contains("позвони") || command.contains("набери") || command.contains("вызов") -> {
                updateNotification("Откройте телефон и подтвердите вызов")
                openApp(Intent(Intent.ACTION_DIAL))
            }
            else -> updateNotification("Услышал: $command")
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

    private fun restartSoon() {
        android.os.Handler(mainLooper).postDelayed({
            restarting = false
            if (running) startRecognizer()
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ai.jarvis.offline.START_WAKE_LISTENING"
        const val ACTION_STOP = "ai.jarvis.offline.STOP_WAKE_LISTENING"
        private const val CHANNEL_ID = "jarvis_voice_control"
        private const val NOTIFICATION_ID = 7104
        private const val TAG = "WakeWordService"
    }
}