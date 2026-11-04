package com.ongar.pengingatabsen

import android.content.Context
import org.json.JSONObject

/** Konstanta dan skrip JavaScript yang dipakai bersama oleh layar STAR ASN dan sinkronisasi latar belakang. */
object StarWeb {
    const val HOME_URL = "https://star-asn.kemenimipas.go.id/home/dashboard"

    /** Kredensial hanya diisikan di domain ini. Tambahkan domain SSO di sini bila login Anda dialihkan ke situs lain. */
    private val TRUSTED_HOSTS = listOf("kemenimipas.go.id")

    const val PROBE_JS =
        "(function(){return [].slice.call(document.querySelectorAll('input[type=password]'))" +
            ".some(function(e){return e.offsetParent!==null})?'yes':'no'})()"

    fun isTrusted(host: String?): Boolean =
        host != null && TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }

    @Volatile private var extractJs: String? = null

    /** Skrip pembaca presensi (assets/star_extract.js) + pemanggilnya. Hasilnya string JSON. */
    fun extractScript(ctx: Context): String {
        val js = extractJs ?: ctx.applicationContext.assets.open("star_extract.js")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }.also { extractJs = it }
        return "(function(){$js;return JSON.stringify(starRun());})()"
    }

    fun fillScript(user: String, pass: String, submit: Boolean): String = """
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
}
