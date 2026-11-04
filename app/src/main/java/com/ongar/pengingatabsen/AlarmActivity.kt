package com.ongar.pengingatabsen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ongar.pengingatabsen.databinding.ActivityAlarmBinding

/** Layar alarm penuh yang tampil di atas kunci layar saat alarm berbunyi. */
class AlarmActivity : AppCompatActivity() {
    private lateinit var b: ActivityAlarmBinding
    private var registered = false

    private val stopped = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val type = AlarmService.ringingType
        if (type == null) {   // alarm sudah berhenti
            finish()
            return
        }
        val test = AlarmService.ringingTest
        val isIn = type == Store.TYPE_IN

        b = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvAlarmTitle.text = when {
            test -> "UJI ALARM"
            isIn -> "WAKTUNYA ABSEN MASUK"
            else -> "WAKTUNYA ABSEN PULANG"
        }
        b.tvAlarmSub.text = "Jadwal " + Store.scheduleLabel(this, type)
        b.btnDone.text = "CEK PRESENSI DI STAR ASN"

        if (test) {
            b.btnDone.visibility = View.GONE
            b.btnSnooze.visibility = View.GONE
        }

        // Alarm berhenti otomatis begitu STAR ASN menunjukkan presensi sudah tercatat.
        b.btnDone.setOnClickListener {
            StarSync.request(this, 0L)
            b.btnDone.isEnabled = false
            b.btnDone.text = "MENGECEK STAR ASN…"
            b.btnDone.postDelayed({
                if (!isFinishing) {
                    b.btnDone.isEnabled = true
                    b.btnDone.text = "CEK PRESENSI DI STAR ASN"
                }
            }, 20_000)
        }
        b.btnSnooze.setOnClickListener {
            AlarmService.stop(this)
            Scheduler.snooze(this, type, 5)
            finish()
        }
        b.btnStop.setOnClickListener {
            AlarmService.stop(this)
            finish()
        }

        ContextCompat.registerReceiver(
            this, stopped, IntentFilter(AlarmService.ACTION_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
    }

    override fun onDestroy() {
        if (registered) {
            try {
                unregisterReceiver(stopped)
            } catch (e: IllegalArgumentException) {
            }
        }
        super.onDestroy()
    }
}
