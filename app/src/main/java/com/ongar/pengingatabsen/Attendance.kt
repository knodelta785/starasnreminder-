package com.ongar.pengingatabsen

import android.content.Context
import android.content.Intent

/**
 * Satu pintu untuk status absen. Status absen HANYA berasal dari website STAR ASN
 * (lihat [StarSync]); tidak ada lagi pencatatan manual oleh pengguna.
 */
object Attendance {
    const val ACTION_DATA_CHANGED = "com.ongar.pengingatabsen.DATA_CHANGED"

    /**
     * Menyamakan status satu jenis absen dengan hasil bacaan website.
     * - S_TIME  : website menunjukkan presensi sudah dilakukan pada jam [millis] -> dicatat.
     * - S_EMPTY : website menunjukkan belum presensi -> status lokal hari ini dikosongkan.
     * - lainnya : tidak dapat dipastikan -> status lokal dibiarkan.
     */
    fun applyRemote(ctx: Context, type: String, state: String, millis: Long?) {
        val app = ctx.applicationContext
        val before = Store.getTime(app, type)
        when (state) {
            StarSync.S_TIME -> {
                if (millis == null || millis <= 0L) return
                if (before == millis) return
                Store.setTime(app, type, millis)
                if (AlarmService.ringingType == type) AlarmService.stop(app)
                Scheduler.cancelSnooze(app, type)
            }
            StarSync.S_EMPTY -> {
                if (before == 0L) return
                Store.clearTime(app, type)
            }
            else -> return
        }
        Scheduler.scheduleAll(app)   // alarm hari ini otomatis off / aktif lagi sesuai status
        Notifier.sync(app)           // notifikasi status bar mengikuti status
    }

    /** Menghapus data absen lokal. Data akan terisi lagi pada sinkronisasi berikutnya. */
    fun reset(ctx: Context, all: Boolean) {
        val app = ctx.applicationContext
        AlarmService.stop(app)
        if (all) Store.resetAll(app) else Store.resetToday(app)
        Scheduler.cancelSnooze(app, Store.TYPE_IN)
        Scheduler.cancelSnooze(app, Store.TYPE_OUT)
        Scheduler.scheduleAll(app)
        Notifier.sync(app)
        notifyChanged(app)
        StarSync.request(app, 0L)
    }

    /** Memberi tahu layar yang terbuka agar menyegarkan tampilan. */
    fun notifyChanged(ctx: Context) {
        val app = ctx.applicationContext
        app.sendBroadcast(Intent(ACTION_DATA_CHANGED).setPackage(app.packageName))
    }
}
