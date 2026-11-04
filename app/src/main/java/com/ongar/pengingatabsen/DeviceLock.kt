package com.ongar.pengingatabsen

import android.app.Activity
import android.app.KeyguardManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Verifikasi PIN / pola / sidik jari milik HP sebelum aksi sensitif. Buat sebagai properti Activity. */
class DeviceLock(private val activity: ComponentActivity) {
    private var onOk: (() -> Unit)? = null
    private var onFail: (() -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val ok = onOk
        val fail = onFail
        onOk = null
        onFail = null
        if (r.resultCode == Activity.RESULT_OK) ok?.invoke() else fail?.invoke()
    }

    private fun km(): KeyguardManager = activity.getSystemService(KeyguardManager::class.java)

    fun isSecure(): Boolean = km().isDeviceSecure

    fun verify(title: String, onFail: (() -> Unit)? = null, onOk: () -> Unit) {
        val intent = if (isSecure()) {
            km().createConfirmDeviceCredentialIntent(title, "Verifikasi identitas Anda")
        } else null
        if (intent == null) {
            Toast.makeText(
                activity, "Aktifkan kunci layar (PIN/pola/sidik jari) di pengaturan HP dahulu.",
                Toast.LENGTH_LONG
            ).show()
            onFail?.invoke()
            return
        }
        this.onOk = onOk
        this.onFail = onFail
        launcher.launch(intent)
    }
}
