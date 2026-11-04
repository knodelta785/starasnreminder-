package com.ongar.pengingatabsen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

object Notifier {
    const val CH_REMINDER = "reminder_absen"
    const val CH_ALARM = "alarm_absen"
    const val ID_IN = 1001
    const val ID_OUT = 1002
    const val ID_ALARM = 2001
    const val CH_QUICK = "akses_star_asn"
    const val ID_QUICK_IN = 1003
    const val ID_QUICK_OUT = 1004
    const val CH_LOGIN = "perlu_login_star"
    const val ID_LOGIN = 1005

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val reminder = NotificationChannel(
            CH_REMINDER, "Pengingat belum absen", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifikasi di status bar selama absen belum dilakukan"
            setShowBadge(true)
        }
        val alarm = NotificationChannel(
            CH_ALARM, "Alarm absen", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alarm pada jam absen masuk dan absen pulang"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val quick = NotificationChannel(
            CH_QUICK, "Pintasan STAR ASN", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifikasi tetap untuk membuka STAR ASN sekali ketuk"
            setShowBadge(false)
        }
        val login = NotificationChannel(
            CH_LOGIN, "Perlu login STAR ASN", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Muncul bila sinkron presensi butuh login ulang (mis. captcha)"
            setShowBadge(false)
        }
        nm.createNotificationChannels(listOf(reminder, alarm, quick, login))
    }

    fun actionIntent(ctx: Context, action: String, type: String): PendingIntent {
        val base = when (action) {
            ActionReceiver.ACTION_SYNC -> 40
            ActionReceiver.ACTION_SNOOZE -> 50
            else -> 60
        }
        val code = base + if (type == Store.TYPE_IN) 0 else 1
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_TYPE, type)
        return PendingIntent.getBroadcast(ctx, code, i, FLAGS)
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 70, i, FLAGS)
    }

    private fun openStar(ctx: Context): PendingIntent {
        val i = Intent(ctx, StarAsnActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 72, i, FLAGS)
    }

    /** Pintasan STAR ASN (login otomatis). Satu per jenis absen; tampil dan hilang bersama notifikasi "belum absen"-nya. */
    fun quickAccess(ctx: Context, type: String): Notification {
        val isIn = type == Store.TYPE_IN
        return NotificationCompat.Builder(ctx, CH_QUICK)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(if (isIn) "Login STAR ASN • absen masuk" else "Login STAR ASN • absen pulang")
            .setContentText("Ketuk untuk membuka STAR ASN dengan login otomatis")
            .setColor(ContextCompat.getColor(ctx, R.color.blue))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openStar(ctx))
            .build()
    }

    /** Notifikasi "belum absen" di status bar, dengan tombol kecil "Sudah absen ..." */
    fun reminder(ctx: Context, type: String): Notification {
        val isIn = type == Store.TYPE_IN
        val title = if (isIn) "Belum absen masuk" else "Belum absen pulang"
        val text = (if (isIn) "Jadwal masuk " else "Jadwal pulang ") +
            Store.scheduleLabel(ctx, type) + " • hilang otomatis setelah presensi di STAR ASN tercatat"
        return NotificationCompat.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(ContextCompat.getColor(ctx, R.color.red))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Buka STAR ASN", openStar(ctx))
            .addAction(0, "Cek presensi", actionIntent(ctx, ActionReceiver.ACTION_SYNC, type))
            .build()
    }

    /** Sinkron tidak bisa login sendiri (captcha / sesi habis): minta pengguna login sekali. */
    @SuppressLint("MissingPermission")
    fun loginNeeded(ctx: Context) {
        val app = ctx.applicationContext
        createChannels(app)
        val n = NotificationCompat.Builder(app, CH_LOGIN)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle("Presensi belum bisa disinkronkan")
            .setContentText("Ketuk untuk login STAR ASN sekali, setelah itu sinkron jalan otomatis lagi")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Sesi STAR ASN habis dan login otomatis tidak berhasil (mis. ada captcha). Ketuk untuk login sekali, setelah itu sinkron jalan otomatis lagi.")
            )
            .setColor(ContextCompat.getColor(app, R.color.amber))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openStar(app))
            .build()
        try {
            NotificationManagerCompat.from(app).notify(ID_LOGIN, n)
        } catch (e: SecurityException) {
            // izin notifikasi belum diberikan
        }
    }

    fun cancelLoginNeeded(ctx: Context) {
        NotificationManagerCompat.from(ctx.applicationContext).cancel(ID_LOGIN)
    }

    /** Notifikasi foreground untuk alarm yang sedang berbunyi (layar penuh). */
    fun alarmNotification(ctx: Context, type: String, test: Boolean): Notification {
        val label = if (type == Store.TYPE_IN) "masuk" else "pulang"
        val full = PendingIntent.getActivity(
            ctx, 71,
            Intent(ctx, AlarmActivity::class.java).putExtra(EXTRA_TYPE, type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            FLAGS
        )
        val b = NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(if (test) "Uji alarm absen $label" else "Waktunya absen $label!")
            .setContentText(
                if (test) "Ketuk untuk membuka"
                else "Jadwal " + Store.scheduleLabel(ctx, type) + " • ketuk untuk membuka alarm"
            )
            .setColor(ContextCompat.getColor(ctx, R.color.blue))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
        if (test) {
            b.addAction(0, "Matikan", actionIntent(ctx, ActionReceiver.ACTION_DISMISS, type))
        } else {
            b.addAction(0, "Buka STAR ASN", openStar(ctx))
            b.addAction(0, "Tunda 5 menit", actionIntent(ctx, ActionReceiver.ACTION_SNOOZE, type))
        }
        return b.build()
    }

    /**
     * Menyamakan notifikasi status bar dengan kondisi sekarang:
     * - Masuk: tampil sejak 00:01 selama belum absen masuk.
     * - Pulang: tampil sejak jam alarm pulang + 1 menit (16:31) selama belum absen pulang.
     */
    @SuppressLint("MissingPermission")
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        createChannels(app)
        val nm = NotificationManagerCompat.from(app)
        val now = Calendar.getInstance()
        val minNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val todayOn = Store.isTodayEnabled(app)

        val showIn = todayOn && !Store.isDone(app, Store.TYPE_IN) &&
            minNow >= Scheduler.reminderMinutes(app, Store.TYPE_IN)
        val showOut = todayOn && !Store.isDone(app, Store.TYPE_OUT) &&
            minNow >= Scheduler.reminderMinutes(app, Store.TYPE_OUT)

        try {
            if (showIn) nm.notify(ID_IN, reminder(app, Store.TYPE_IN)) else nm.cancel(ID_IN)
            if (showOut) nm.notify(ID_OUT, reminder(app, Store.TYPE_OUT)) else nm.cancel(ID_OUT)
            // Pintasan STAR ASN ikut muncul/hilang bersama notifikasi "belum absen" masing-masing
            val quickOn = CredentialStore.quickNotif(app)
            if (showIn && quickOn) nm.notify(ID_QUICK_IN, quickAccess(app, Store.TYPE_IN)) else nm.cancel(ID_QUICK_IN)
            if (showOut && quickOn) nm.notify(ID_QUICK_OUT, quickAccess(app, Store.TYPE_OUT)) else nm.cancel(ID_QUICK_OUT)
        } catch (e: SecurityException) {
            // izin notifikasi belum diberikan
        }
    }
}
