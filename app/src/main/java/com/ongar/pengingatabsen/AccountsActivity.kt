package com.ongar.pengingatabsen

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ongar.pengingatabsen.databinding.ActivityAccountsBinding
import com.ongar.pengingatabsen.databinding.DialogAccountBinding
import com.ongar.pengingatabsen.databinding.ItemAccountBinding

/** Kelola akun login STAR ASN. Password disembunyikan; melihatnya butuh PIN/sidik jari HP. */
class AccountsActivity : AppCompatActivity() {
    private lateinit var b: ActivityAccountsBinding
    private val lock = DeviceLock(this)
    private val handler = Handler(Looper.getMainLooper())
    private val revealed = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cegah screenshot / rekam layar / thumbnail recent apps di halaman ini
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        b = ActivityAccountsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.title = "Akun STAR ASN"
        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnAdd.setOnClickListener { showEdit(null) }

        b.swQuick.isChecked = CredentialStore.quickNotif(this)
        b.swLock.isChecked = CredentialStore.requireUnlock(this)
        b.swSubmit.isChecked = CredentialStore.autoSubmit(this)

        b.swQuick.setOnCheckedChangeListener { _, on ->
            CredentialStore.setQuickNotif(this, on)
            Notifier.sync(this)
        }
        b.swSubmit.setOnCheckedChangeListener { _, on -> CredentialStore.setAutoSubmit(this, on) }
        b.swLock.setOnCheckedChangeListener { v, on ->
            if (on && !lock.isSecure()) {
                v.isChecked = false
                toast("Aktifkan kunci layar (PIN/pola/sidik jari) di pengaturan HP dahulu.")
            } else {
                CredentialStore.setRequireUnlock(this, on)
            }
        }
        render()
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        revealed.clear()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    private fun render() {
        b.list.removeAllViews()
        val accounts = CredentialStore.list(this)
        val activeId = CredentialStore.activeId(this)
        b.tvEmpty.visibility = if (accounts.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        accounts.forEach { a ->
            val row = ItemAccountBinding.inflate(layoutInflater, b.list, false)
            val plain = revealed[a.id]
            row.tvLabel.text = a.label.ifBlank { a.username }
            row.tvUser.text = a.username
            row.tvPass.text = plain ?: "••••••••"
            row.btnEye.setImageResource(if (plain != null) R.drawable.ic_eye_off else R.drawable.ic_eye)
            row.rbActive.isChecked = a.id == activeId

            val select = {
                CredentialStore.setActive(this, a.id)
                render()
            }
            row.root.setOnClickListener { select() }
            row.rbActive.setOnClickListener { select() }
            row.btnEye.setOnClickListener {
                if (plain != null) {
                    revealed.remove(a.id)
                    render()
                } else {
                    lock.verify("Lihat password") {
                        val p = CredentialStore.decryptPassword(a)
                        if (p == null) {
                            toast("Password tidak terbaca. Edit akun dan simpan ulang password.")
                        } else {
                            revealed[a.id] = p
                            render()
                            handler.postDelayed({
                                revealed.remove(a.id)
                                render()
                            }, 10_000)
                        }
                    }
                }
            }
            row.btnEdit.setOnClickListener { showEdit(a) }
            row.btnDelete.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Hapus akun?")
                    .setMessage("Akun \"" + a.label.ifBlank { a.username } + "\" dihapus dari aplikasi ini.")
                    .setPositiveButton("Hapus") { _, _ ->
                        revealed.remove(a.id)
                        CredentialStore.delete(this, a.id)
                        render()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            b.list.addView(row.root)
        }
    }

    private fun showEdit(a: CredentialStore.Account?) {
        val v = DialogAccountBinding.inflate(layoutInflater)
        if (a != null) {
            v.etLabel.setText(a.label)
            v.etUser.setText(a.username)
            v.tilPass.hint = "Password (kosongkan jika tidak diubah)"
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (a == null) "Tambah akun" else "Edit akun")
            .setView(v.root)
            .setPositiveButton("Simpan") { _, _ ->
                val user = v.etUser.text?.toString()?.trim().orEmpty()
                val pass = v.etPass.text?.toString().orEmpty()
                val label = v.etLabel.text?.toString()?.trim().orEmpty()
                if (user.isEmpty() || (a == null && pass.isEmpty())) {
                    toast("Username dan password wajib diisi.")
                } else {
                    CredentialStore.save(this, a?.id, label, user, pass.ifEmpty { null })
                    revealed.clear()
                    render()
                }
            }
            .setNegativeButton("Batal", null)
            .create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
