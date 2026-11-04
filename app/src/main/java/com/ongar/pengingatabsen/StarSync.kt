package com.ongar.pengingatabsen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Sinkronisasi presensi dari website STAR ASN.
 *
 * Website STAR ASN adalah sumber kebenaran: jam presensi masuk / pulang aktual dibaca dari dashboard
 * (hanya membaca teks halaman, tidak pernah menekan tombol presensi) lalu dicatat otomatis di aplikasi.
 * Tidak ada lagi pencatatan manual.
 */
object StarSync {
    const val OK = "ok"
    const val NEED_LOGIN = "login"
    const val UNREADABLE = "unreadable"
    const val ERROR = "error"

    const val S_TIME = "time"
    const val S_EMPTY = "empty"
    const val S_UNKNOWN = "unknown"

    private const val PREF = "star_sync"
    private const val WORK_ONCE = "star_sync_once"
    private const val WORK_PERIODIC = "star_sync_periodic"

    data class Reading(
        val status: String,
        val inState: String = S_UNKNOWN,
        val inTime: String? = null,      // HH:mm:ss
        val outState: String = S_UNKNOWN,
        val outTime: String? = null,
        val message: String = "",
        val url: String = "",
        val dump: String = ""
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun constraints() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    // ---------- Penjadwalan ----------

    /** Sinkron berkala tiap 15 menit (batas terkecil Android) selama aplikasi terpasang. */
    fun ensurePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints()).build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Minta sinkron sekarang. Dibatasi agar tidak berulang-ulang dalam [minGapMs]. */
    fun request(ctx: Context, minGapMs: Long = 90_000L) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (minGapMs > 0L && now - p.getLong("last_request", 0L) < minGapMs) return
        p.edit().putLong("last_request", now).apply()
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork(WORK_ONCE, ExistingWorkPolicy.KEEP, req)
    }

    // ---------- Status terakhir ----------

    fun status(ctx: Context): String = prefs(ctx).getString("status", "") ?: ""
    fun message(ctx: Context): String = prefs(ctx).getString("message", "") ?: ""
    fun lastTry(ctx: Context): Long = prefs(ctx).getLong("last_try", 0L)
    fun lastOk(ctx: Context): Long = prefs(ctx).getLong("last_ok", 0L)
    fun lastUrl(ctx: Context): String = prefs(ctx).getString("url", "") ?: ""
    fun lastDump(ctx: Context): String = prefs(ctx).getString("dump", "") ?: ""

    /** true selama sinkron sedang berjalan (otomatis dianggap selesai bila macet > 3 menit). */
    fun isRunning(ctx: Context): Boolean {
        val t = prefs(ctx).getLong("running_since", 0L)
        return t > 0L && System.currentTimeMillis() - t < 3 * 60_000L
    }

    fun setRunning(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putLong("running_since", if (on) System.currentTimeMillis() else 0L).apply()
    }

    private fun save(ctx: Context, r: Reading) {
        val e = prefs(ctx).edit()
            .putString("status", r.status)
            .putString("message", r.message)
            .putString("url", r.url)
            .putLong("last_try", System.currentTimeMillis())
        if (r.dump.isNotEmpty()) e.putString("dump", r.dump)
        if (r.status == OK) e.putLong("last_ok", System.currentTimeMillis())
        e.apply()
    }

    // ---------- Membaca hasil skrip ----------

    /** null = halaman belum siap dibaca (masih memuat). */
    fun fromJson(raw: String?): Reading? {
        if (raw == null || raw == "null") return null
        return try {
            val s = JSONTokener(raw).nextValue() as? String ?: return null
            val o = JSONObject(s)
            val url = o.optString("url")
            val dump = o.optString("dump")
            when (o.optString("page")) {
                "login" -> Reading(NEED_LOGIN, message = "Sesi STAR ASN belum login", url = url, dump = dump)
                "page" -> {
                    val i = o.optJSONObject("in")
                    val u = o.optJSONObject("out")
                    val r = Reading(
                        OK,
                        inState = i?.optString("state", S_UNKNOWN) ?: S_UNKNOWN,
                        inTime = i?.optString("time")?.takeIf { it.isNotEmpty() && it != "null" },
                        outState = u?.optString("state", S_UNKNOWN) ?: S_UNKNOWN,
                        outTime = u?.optString("time")?.takeIf { it.isNotEmpty() && it != "null" },
                        url = url, dump = dump
                    )
                    if (r.inState == S_UNKNOWN && r.outState == S_UNKNOWN) {
                        r.copy(status = UNREADABLE, message = "Halaman terbuka, tetapi data presensi tidak ditemukan")
                    } else r
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun toMillis(hms: String?): Long? {
        if (hms == null) return null
        val p = hms.split(":").mapNotNull { it.toIntOrNull() }
        if (p.size < 2) return null
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, p[0])
        c.set(Calendar.MINUTE, p[1])
        c.set(Calendar.SECOND, p.getOrElse(2) { 0 })
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------- Menerapkan hasil ----------

    /** Menyimpan hasil dan menyamakan status absen aplikasi dengan website STAR ASN. */
    fun apply(ctx: Context, r: Reading) {
        val app = ctx.applicationContext
        save(app, r)
        if (r.status == OK) {
            Attendance.applyRemote(app, Store.TYPE_IN, r.inState, toMillis(r.inTime))
            Attendance.applyRemote(app, Store.TYPE_OUT, r.outState, toMillis(r.outTime))
            Notifier.cancelLoginNeeded(app)
        } else if (r.status == NEED_LOGIN) {
            Notifier.loginNeeded(app)
        }
        Attendance.notifyChanged(app)
    }

    /** Ringkasan satu baris untuk layar utama. */
    fun summary(ctx: Context): String {
        val t = lastOk(ctx)
        val ago = if (t > 0) "terakhir " + android.text.format.DateUtils.getRelativeTimeSpanString(
            t, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
        ) else ""
        return when (status(ctx)) {
            OK -> "Tersinkron dengan STAR ASN • $ago"
            NEED_LOGIN -> "Perlu login STAR ASN • ketuk untuk login" + if (ago.isNotEmpty()) " ($ago)" else ""
            UNREADABLE -> "Data presensi di halaman tidak terbaca • lihat Diagnostik Sinkron"
            ERROR -> "Gagal sinkron: " + message(ctx) + if (ago.isNotEmpty()) " ($ago)" else ""
            else -> "Belum pernah sinkron • ketuk untuk sinkron sekarang"
        }
    }
}

/** Menjalankan sinkron di latar belakang (WorkManager). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        StarSync.setRunning(applicationContext, true)
        Attendance.notifyChanged(applicationContext)
        try {
            val reading = try {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<StarSync.Reading> { cont ->
                        val f = StarFetcher(applicationContext) { if (cont.isActive) cont.resume(it) }
                        cont.invokeOnCancellation {
                            Handler(Looper.getMainLooper()).post { f.cancel() }
                        }
                        f.start()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                StarSync.Reading(StarSync.ERROR, message = e.message ?: "kesalahan tidak diketahui")
            }
            StarSync.apply(applicationContext, reading)
        } finally {
            StarSync.setRunning(applicationContext, false)
            Attendance.notifyChanged(applicationContext)
        }
        return Result.success()
    }
}

/**
 * WebView tersembunyi yang membuka dashboard STAR ASN memakai sesi login yang sama dengan layar STAR ASN
 * (cookie dibagi). Bila sesi habis, mencoba login otomatis dengan akun tersimpan; jika terhalang captcha
 * atau kunci layar, hasilnya NEED_LOGIN dan pengguna diberi notifikasi untuk login sekali.
 */
@SuppressLint("SetJavaScriptEnabled")
class StarFetcher(
    private val ctx: Context,
    private val onDone: (StarSync.Reading) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var finished = false
    private var triedLogin = false
    private var loginAt = 0L
    private var polls = 0
    private val startedAt = SystemClock.uptimeMillis()

    fun start() {
        val w = WebView(ctx.applicationContext)
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme
                return !(s == "http" || s == "https")
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                polls = 0
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    finish(StarSync.Reading(StarSync.ERROR, message = "tidak ada koneksi atau situs tidak dapat dibuka"))
                }
            }
        }
        w.loadUrl(StarWeb.HOME_URL)
        handler.postDelayed({ timeout() }, TOTAL_MS)
        handler.postDelayed({ poll() }, 1500)
    }

    fun cancel() {
        if (finished) return
        finished = true
        release()
    }

    private fun next() {
        if (!finished) handler.postDelayed({ poll() }, 1200)
    }

    private fun timeout() {
        if (finished) return
        finish(StarSync.Reading(StarSync.ERROR, message = "waktu habis saat memuat STAR ASN"))
    }

    private fun poll() {
        if (finished) return
        val w = web ?: return
        val url = w.url
        val host = if (url == null) null else Uri.parse(url).host
        if (url == null || url.startsWith("about:")) { next(); return }
        if (!StarWeb.isTrusted(host)) {
            // dialihkan ke situs lain (mis. SSO) -> perlu login manual
            finish(StarSync.Reading(StarSync.NEED_LOGIN, message = "Dialihkan ke $host, perlu login manual", url = url))
            return
        }
        polls++
        w.evaluateJavascript(StarWeb.extractScript(ctx)) { raw -> onRaw(raw) }
    }

    private fun onRaw(raw: String?) {
        if (finished) return
        val r = StarSync.fromJson(raw)
        if (r == null) { next(); return }
        when (r.status) {
            StarSync.NEED_LOGIN -> handleLogin(r)
            StarSync.OK -> {
                val bothKnown = r.inState != StarSync.S_UNKNOWN && r.outState != StarSync.S_UNKNOWN
                // beri waktu halaman (SPA) menampilkan kedua data sebelum menyimpulkan
                if (bothKnown || polls >= 4) finish(r) else next()
            }
            else -> if (polls >= 10) finish(r) else next()
        }
    }

    private fun handleLogin(r: StarSync.Reading) {
        val now = SystemClock.uptimeMillis()
        if (triedLogin) {
            if (now - loginAt > 25_000) {
                finish(r.copy(message = "Login otomatis tidak berhasil. Periksa username dan password di Akun STAR ASN"))
            } else next()
            return
        }
        val acc = CredentialStore.active(ctx)
        if (acc == null) {
            finish(r.copy(message = "Belum ada akun STAR ASN tersimpan"))
            return
        }
        if (CredentialStore.requireUnlock(ctx)) {
            finish(r.copy(message = "Login otomatis di latar belakang dimatikan oleh pengaturan kunci layar"))
            return
        }
        val pass = CredentialStore.decryptPassword(acc)
        if (pass == null) {
            finish(r.copy(message = "Password akun tidak terbaca. Simpan ulang password di Akun STAR ASN"))
            return
        }
        web?.evaluateJavascript(StarWeb.fillScript(acc.username, pass, true)) { res ->
            when {
                res?.contains("submitted") == true -> {
                    triedLogin = true
                    loginAt = SystemClock.uptimeMillis()
                    next()
                }
                res?.contains("captcha") == true ->
                    finish(r.copy(message = "Login membutuhkan captcha. Login sekali lewat tombol Buka STAR ASN"))
                res?.contains("nofield") == true -> next()
                else -> finish(r.copy(message = "Form login tidak dikenali"))
            }
        }
    }

    private fun finish(r: StarSync.Reading) {
        if (finished) return
        finished = true
        release()
        onDone(r)
    }

    private fun release() {
        handler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
        web?.let {
            it.stopLoading()
            it.destroy()
        }
        web = null
    }

    private companion object {
        const val TOTAL_MS = 75_000L
    }
}
