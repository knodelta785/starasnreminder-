package com.ongar.pengingatabsen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ongar.pengingatabsen.databinding.ActivityStarAsnBinding
import org.json.JSONObject

/**
 * Membuka STAR ASN di dalam aplikasi dan mengisi form login dengan akun aktif.
 * Aplikasi TIDAK menekan tombol presensi; presensi dilakukan di website, lalu aplikasi membaca hasilnya otomatis.
 */
class StarAsnActivity : AppCompatActivity() {
    companion object {
        const val HOME_URL = "https://star-asn.kemenimipas.go.id/home/dashboard"

        /** Kredensial hanya diisikan di domain ini. Tambahkan domain SSO di sini bila login Anda dialihkan ke situs lain. */
        private val TRUSTED_HOSTS = listOf("kemenimipas.go.id")

        private const val PROBE_JS =
            "(function(){return [].slice.call(document.querySelectorAll('input[type=password]'))" +
                ".some(function(e){return e.offsetParent!==null})?'yes':'no'})()"

        private fun isTrusted(host: String?): Boolean =
            host != null && TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private lateinit var b: ActivityStarAsnBinding
    private val handler = Handler(Looper.getMainLooper())
    private val lock = DeviceLock(this)
    private var started = false
    private var lastSubmit = 0L
    private var fillToken = 0

    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var geoOrigin: String? = null
    private var geoCb: GeolocationPermissions.Callback? = null
    private var camReq: PermissionRequest? = null

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }
    private val locPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val o = geoOrigin
        if (o != null) geoCb?.invoke(o, granted, false)
        geoCb = null
        geoOrigin = null
    }
    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val r = camReq
        camReq = null
        if (r != null) {
            if (granted) r.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else r.deny()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStarAsnBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.title = "STAR ASN"
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.inflateMenu(R.menu.star_menu)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reload -> b.web.reload()
                R.id.action_switch -> showSwitch()
                R.id.action_browser -> openInBrowser()
                R.id.action_clear -> confirmClear()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        b.progress.max = 100

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.web.canGoBack()) b.web.goBack() else finish()
            }
        })

        setupWeb()

        if (CredentialStore.requireUnlock(this) && lock.isSecure()) {
            b.web.visibility = View.INVISIBLE
            lock.verify("Buka STAR ASN", onFail = { finish() }) {
                b.web.visibility = View.VISIBLE
                begin()
            }
        } else {
            begin()
        }
    }

    private fun begin() {
        if (started) return
        started = true
        if (CredentialStore.active(this) == null) {
            toast("Belum ada akun tersimpan. Tambahkan lewat menu Akun STAR ASN.")
        }
        b.web.loadUrl(HOME_URL)
    }

    private fun setupWeb() {
        val s = b.web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setGeolocationEnabled(true)
        s.allowFileAccess = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(b.web, true)
        }

        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (scheme == "http" || scheme == "https") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                    // tidak ada aplikasi yang menangani
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                fillToken++
                tryFill(url, 0, fillToken)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) toast("Gagal memuat halaman. Periksa koneksi internet.")
            }
        }

        b.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.progress.progress = newProgress
                b.progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                if (!isTrusted(Uri.parse(origin).host)) {
                    callback.invoke(origin, false, false)
                } else if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    callback.invoke(origin, true, false)
                } else {
                    geoOrigin = origin
                    geoCb = callback
                    locPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsCam = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (!wantsCam || !isTrusted(request.origin.host)) {
                        request.deny()
                    } else if (granted(Manifest.permission.CAMERA)) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        camReq?.deny()
                        camReq = request
                        camPerm.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView, callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = callback
                return try {
                    chooser.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }
    }

    // ---------------- Isi login otomatis ----------------

    private fun tryFill(url: String?, attempt: Int, token: Int) {
        if (token != fillToken || url == null) return
        if (!isTrusted(Uri.parse(url).host)) return
        val acc = CredentialStore.active(this) ?: return

        b.web.evaluateJavascript(PROBE_JS) { r ->
            if (token != fillToken) return@evaluateJavascript
            if (r?.contains("yes") == true) {
                val pass = CredentialStore.decryptPassword(acc)
                if (pass == null) {
                    toast("Password akun tidak terbaca. Edit akun dan simpan ulang password.")
                    return@evaluateJavascript
                }
                val wantSubmit = CredentialStore.autoSubmit(this)
                val canSubmit = wantSubmit && System.currentTimeMillis() - lastSubmit > 20_000
                if (wantSubmit && !canSubmit) {
                    toast("Login belum berhasil? Periksa username dan password di menu Akun STAR ASN.")
                }
                b.web.evaluateJavascript(fillScript(acc.username, pass, canSubmit)) { res ->
                    when {
                        res?.contains("submitted") == true -> lastSubmit = System.currentTimeMillis()
                        res?.contains("captcha") == true ->
                            toast("Username dan password terisi. Isi captcha lalu tekan Masuk.")
                        res?.contains("nouser") == true ->
                            toast("Kolom username tidak ditemukan. Isi manual.")
                    }
                }
            } else if (attempt < 6) {
                handler.postDelayed({ tryFill(url, attempt + 1, token) }, 800)
            }
        }
    }

    private fun fillScript(user: String, pass: String, submit: Boolean): String = """
(function(u,p,doSubmit){
  function vis(e){return e.offsetParent!==null&&!e.disabled&&!e.readOnly;}
  function setVal(el,v){
    var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
    d.set.call(el,v);
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
  }
  var pw=[].slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];
  if(!pw) return 'nofield';
  var scope=pw.form||document;
  var all=[].slice.call(scope.querySelectorAll('input')).filter(function(e){
    var t=(e.type||'text').toLowerCase();
    return vis(e)&&(t==='text'||t==='email'||t==='tel'||t==='number');
  });
  var userEl=null, extra=false;
  all.forEach(function(e){
    var pos=e.compareDocumentPosition(pw);
    if(pos&Node.DOCUMENT_POSITION_FOLLOWING){userEl=e;}
    else if(pos&Node.DOCUMENT_POSITION_PRECEDING){extra=true;}
  });
  if(!userEl) return 'nouser';
  setVal(userEl,u); setVal(pw,p);
  var cap=extra||!!document.querySelector('iframe[src*="recaptcha"],.g-recaptcha,.h-captcha,.cf-turnstile,[class*="captcha"],img[src*="captcha"]');
  if(cap) return 'captcha';
  if(!doSubmit) return 'filled';
  setTimeout(function(){
    var f=pw.form;
    var btn=(f||document).querySelector('button[type=submit],input[type=submit]')||(f?f.querySelector('button'):null);
    if(btn){btn.click();}else if(f&&f.requestSubmit){f.requestSubmit();}
  },400);
  return 'submitted';
})(${JSONObject.quote(user)},${JSONObject.quote(pass)},$submit)
""".trimIndent()

    // ---------------- Menu ----------------

    private fun showSwitch() {
        val accounts = CredentialStore.list(this)
        if (accounts.isEmpty()) {
            toast("Belum ada akun tersimpan.")
            return
        }
        val labels = accounts.map { it.label.ifBlank { it.username } }.toTypedArray()
        val cur = accounts.indexOfFirst { it.id == CredentialStore.activeId(this) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Ganti akun")
            .setSingleChoiceItems(labels, cur) { d, which ->
                CredentialStore.setActive(this, accounts[which].id)
                d.dismiss()
                clearSession()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Keluar dari STAR ASN?")
            .setMessage("Sesi login di aplikasi ini dihapus, lalu halaman login dimuat ulang.")
            .setPositiveButton("Keluar") { _, _ -> clearSession() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun clearSession() {
        lastSubmit = 0L
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { b.web.loadUrl(HOME_URL) }
    }

    private fun openInBrowser() {
        val url = b.web.url ?: HOME_URL
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Tidak ada browser yang tersedia.")
        }
    }

    // ---------------- Siklus hidup ----------------

    override fun onResume() {
        super.onResume()
        b.web.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        b.web.onPause()
        // Pengguna baru saja memakai STAR ASN (login / presensi): baca ulang hasilnya sekarang.
        StarSync.request(this, 5_000L)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        (b.web.parent as? ViewGroup)?.removeView(b.web)
        b.web.destroy()
        super.onDestroy()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
