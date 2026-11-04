package com.ongar.pengingatabsen

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Membunyikan alarm (suara + getar) sampai dimatikan atau 2 menit berlalu. */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: Store.TYPE_IN
        val test = intent?.getBooleanExtra(EXTRA_TEST, false) ?: false
        ringingType = type
        ringingTest = test

        Notifier.createChannels(this)
        ServiceCompat.startForeground(
            this, Notifier.ID_ALARM,
            Notifier.alarmNotification(this, type, test),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        startSound()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, RING_MS)
        return START_NOT_STICKY
    }

    private fun startSound() {
        stopSound()
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            player = null
        }
        startVibration()
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator = v
        val pattern = longArrayOf(0, 700, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            v?.vibrate(pattern, 0)
        }
    }

    private fun stopSound() {
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        ringingType = null
        ringingTest = false
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOPPED = "com.ongar.pengingatabsen.ALARM_STOPPED"
        private const val RING_MS = 2 * 60 * 1000L

        @Volatile var ringingType: String? = null
        @Volatile var ringingTest: Boolean = false

        fun start(ctx: Context, type: String, test: Boolean = false) {
            val i = Intent(ctx, AlarmService::class.java)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_TEST, test)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlarmService::class.java))
        }
    }
}
