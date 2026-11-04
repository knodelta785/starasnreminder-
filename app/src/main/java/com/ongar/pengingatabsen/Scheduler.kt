package com.ongar.pengingatabsen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Penjadwalan alarm bunyi (hingga 3 slot per jenis absen), pemicu notifikasi
 * "belum absen" (00:01 / jam default pulang + 1 menit), dan alarm penjaga layanan latar belakang.
 */
object Scheduler {
    const val ACTION_ALARM = "com.ongar.pengingatabsen.ACTION_ALARM"
    const val ACTION_REMIND = "com.ongar.pengingatabsen.ACTION_REMIND"

    const val ACTION_WATCHDOG = "com.ongar.pengingatabsen.ACTION_WATCHDOG"
    /** Sinkron singkat sebelum alarm berbunyi, supaya presensi yang baru dilakukan sudah terbaca. */
    const val ACTION_PRESYNC = "com.ongar.pengingatabsen.ACTION_PRESYNC"
    private const val CODE_PRESYNC = 80
    private const val PRESYNC_LEAD_MS = 90_000L

    private const val CODE_ALARM = 100      // 100 + idx*3 + slot
    private const val CODE_WATCHDOG = 90
    private const val WATCHDOG_MS = 30 * 60_000L
    private const val CODE_SNOOZE = 20
    private const val CODE_REMIND = 30
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun idx(type: String) = if (type == Store.TYPE_IN) 0 else 1

    private fun pi(ctx: Context, action: String, type: String, code: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_TYPE, type)
        return PendingIntent.getBroadcast(ctx, code, i, FLAGS)
    }

    /** Menit (dari 00:00) saat notifikasi "belum absen" mulai muncul (berdasarkan jam default). */
    fun reminderMinutes(ctx: Context, type: String): Int =
        if (type == Store.TYPE_IN) 1 else minOf(Store.defaultMinutes(ctx, type) + 1, 1439)

    fun scheduleAll(ctx: Context) {
        val app = ctx.applicationContext
        for (type in listOf(Store.TYPE_IN, Store.TYPE_OUT)) {
            for (slot in 0 until Store.SLOTS) {
                val alarmPi = pi(app, ACTION_ALARM, type, CODE_ALARM + idx(type) * Store.SLOTS + slot)
                val next = nextSlotMillis(app, type, slot)
                if (next != null) setRinging(app, alarmPi, next) else cancel(app, alarmPi)
            }

            val presync = pi(app, ACTION_PRESYNC, type, CODE_PRESYNC + idx(type))
            val nextAlarm = nextAlarmMillis(app, type)
            val presyncAt = nextAlarm?.minus(PRESYNC_LEAD_MS)
            if (presyncAt != null && presyncAt > System.currentTimeMillis()) {
                setExact(app, presync, presyncAt)
            } else {
                cancel(app, presync)
            }

            val mins = reminderMinutes(app, type)
            val at = nextTime(mins / 60, mins % 60).timeInMillis
            setExact(app, pi(app, ACTION_REMIND, type, CODE_REMIND + idx(type)), at)
        }
        scheduleWatchdog(app)
    }

    /**
     * Waktu berikutnya untuk satu slot alarm. Melewati hari yang tidak dipilih dan hari yang
     * absennya sudah dilakukan, sehingga SEMUA slot otomatis off setelah absen hari itu.
     */
    private fun nextSlotMillis(ctx: Context, type: String, slot: Int): Long? {
        if (!Store.alarmEnabled(ctx, type) || !Store.slotEnabled(ctx, type, slot)) return null
        val cal = nextTime(Store.slotHour(ctx, type, slot), Store.slotMinute(ctx, type, slot))
        repeat(8) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (Store.isDayEnabled(ctx, dow) && !Store.isDone(ctx, type, Store.dateKey(cal))) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    /** Waktu alarm terdekat dari semua slot aktif untuk jenis absen ini. */
    fun nextAlarmMillis(ctx: Context, type: String): Long? =
        (0 until Store.SLOTS).mapNotNull { nextSlotMillis(ctx, type, it) }.minOrNull()

    /** Alarm penjaga (tanpa notifikasi): memasang ulang seluruh jadwal secara berkala. */
    private fun scheduleWatchdog(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_WATCHDOG)
        val pi = PendingIntent.getBroadcast(ctx, CODE_WATCHDOG, i, FLAGS)
        if (!Store.backgroundEnabled(ctx)) {
            am.cancel(pi)
            return
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + WATCHDOG_MS, pi)
    }

    fun snooze(ctx: Context, type: String, minutes: Int) {
        val app = ctx.applicationContext
        val at = System.currentTimeMillis() + minutes * 60_000L
        setRinging(app, pi(app, ACTION_ALARM, type, CODE_SNOOZE + idx(type)), at)
    }

    fun cancelSnooze(ctx: Context, type: String) {
        val app = ctx.applicationContext
        cancel(app, pi(app, ACTION_ALARM, type, CODE_SNOOZE + idx(type)))
    }

    private fun nextTime(h: Int, m: Int): Calendar {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1)
        return c
    }

    private fun setRinging(ctx: Context, pi: PendingIntent, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(ctx, 99, Intent(ctx, MainActivity::class.java), FLAGS)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun setExact(ctx: Context, pi: PendingIntent, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancel(ctx: Context, pi: PendingIntent) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }
}
