package ai.jarvis.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

class FloatingVoiceService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var listening = false
    private lateinit var params: WindowManager.LayoutParams
    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("JARVIS · плавающая кнопка")
                .setContentText("Кнопка голосового режима поверх приложений активна")
                .setContentIntent(open).setOngoing(true).build()
        else Notification.Builder(this).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS · плавающая кнопка").setContentText("Голосовой режим доступен")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(ID, notification)
        showBubble()
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = TextView(this).apply {
            text = "JARVIS\nГОЛОС"
            gravity = Gravity.CENTER
            textSize = 11f
            letterSpacing = 0.04f
            setTextColor(0xFF00F5FF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xF20A0D18.toInt())
                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFF00F5FF.toInt())
            }
            elevation = 14f
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            (84 * resources.displayMetrics.density).toInt(),
            (84 * resources.displayMetrics.density).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * resources.displayMetrics.density).toInt()
            y = (180 * resources.displayMetrics.density).toInt()
        }
        bubble?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) < 8 && kotlin.math.abs(dy) < 8) toggleVoice()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX - (event.rawX - touchX).toInt()).coerceAtLeast(0)
                    params.y = (startY + (event.rawY - touchY).toInt()).coerceAtLeast(0)
                    runCatching { windowManager?.updateViewLayout(view, params) }
                    true
                }
                else -> true
            }
        }
        runCatching { windowManager?.addView(bubble, params) }.onFailure { stopSelf() }
    }

    private fun toggleVoice() {
        val intent = Intent(this, WakeWordService::class.java)
        if (!listening) {
            intent.action = WakeWordService.ACTION_DIRECT
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                listening = true
                bubble?.text = "JARVIS\nСЛУШАЮ"
                (bubble?.background as? GradientDrawable)?.setStroke(
                    (2 * resources.displayMetrics.density).toInt(), 0xFFB44CFF.toInt()
                )
            } catch (_: Exception) {
                bubble?.text = "ОТКРОЙ\nJARVIS"
            }
        } else {
            intent.action = WakeWordService.ACTION_STOP
            startService(intent)
            listening = false
            bubble?.text = "JARVIS\nГОЛОС"
            (bubble?.background as? GradientDrawable)?.setStroke(
                (2 * resources.displayMetrics.density).toInt(), 0xFF00F5FF.toInt()
            )
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Плавающая кнопка Jarvis", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        runCatching { bubble?.let { windowManager?.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "jarvis_floating_voice"
        private const val ID = 7105
    }
}
