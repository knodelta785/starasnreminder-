package com.ongar.pengingatabsen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Dipanggil AlarmManager: membunyikan alarm dan/atau menampilkan notifikasi, lalu menjadwalkan ulang. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val type = intent.getStringExtra(EXTRA_TYPE) ?: Store.TYPE_IN
        if (intent.action == Scheduler.ACTION_ALARM && Store.shouldRing(ctx, type)) {
            try {
                AlarmService.start(ctx, type)
            } catch (e: Exception) {
                // layanan tidak boleh dijalankan saat ini; notifikasi tetap ditampilkan di bawah
            }
        }
        Scheduler.scheduleAll(ctx)
        Notifier.sync(ctx)
        // Cek STAR ASN. Bila ternyata presensi sudah ada, alarm yang sedang berbunyi dimatikan otomatis.
        when (intent.action) {
            Scheduler.ACTION_ALARM, Scheduler.ACTION_PRESYNC -> StarSync.request(ctx, 0L)
            else -> StarSync.request(ctx, 5 * 60_000L)
        }
        StarSync.ensurePeriodic(ctx)
    }
}

/** Tombol pada notifikasi: "Cek presensi", "Tunda", "Matikan". */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return
        when (intent.action) {
            ACTION_SYNC -> StarSync.request(ctx, 0L)
            ACTION_SNOOZE -> {
                AlarmService.stop(ctx)
                Scheduler.snooze(ctx, type, 5)
            }
            ACTION_DISMISS -> AlarmService.stop(ctx)
        }
    }

    companion object {
        const val ACTION_SYNC = "com.ongar.pengingatabsen.SYNC"
        const val ACTION_SNOOZE = "com.ongar.pengingatabsen.SNOOZE"
        const val ACTION_DISMISS = "com.ongar.pengingatabsen.DISMISS"
    }
}

/** Setelah restart HP / ganti jam / update aplikasi: jadwal dipasang ulang dan penjaga latar belakang dipasang. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.scheduleAll(ctx)
        Notifier.sync(ctx)
        StarSync.ensurePeriodic(ctx)
        StarSync.request(ctx, 0L)
    }
}
