package com.ongar.pengingatabsen

import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ongar.pengingatabsen.databinding.ActivityAlarmSettingBinding
import com.ongar.pengingatabsen.databinding.ItemAlarmSlotBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menu Alarm Absen Masuk / Alarm Absen Pulang (satu layar, dibedakan oleh EXTRA_TYPE). */
class AlarmSettingActivity : AppCompatActivity() {
    private lateinit var b: ActivityAlarmSettingBinding
    private lateinit var type: String
    private var updating = false
    private val slotViews = mutableListOf<ItemAlarmSlotBinding>()

    private val nextFmt = SimpleDateFormat("EEEE, d MMM yyyy HH:mm", Locale.forLanguageTag("id-ID"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = intent.getStringExtra(EXTRA_TYPE) ?: Store.TYPE_IN
        b = ActivityAlarmSettingBinding.inflate(layoutInflater)
        setContentView(b.root)

        val isIn = type == Store.TYPE_IN
        val label = if (isIn) "masuk" else "pulang"
        b.toolbar.title = if (isIn) "Alarm Absen Masuk" else "Alarm Absen Pulang"
        b.toolbar.setNavigationOnClickListener { finish() }
        b.tvSlotsTitle.text = "Waktu alarm absen $label"
        b.tvInfo.text = "Alarm berbunyi pada tiap waktu alarm yang aktif, di hari pengingat yang dipilih. " +
            "Begitu Anda menekan \"Sudah absen $label\" hari itu, SEMUA alarm $label otomatis OFF " +
            "sampai besok. Jam default hanya jadwal acuan dan tidak menyalakan alarm."
        b.tvDefaultNote.text = "Jam default dipakai sebagai jadwal acuan, bukan waktu alarm berbunyi."

        b.btnChangeTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, m ->
                    Store.setDefaultTime(this, type, h, m)
                    Scheduler.scheduleAll(this)
                    Notifier.sync(this)
                    refresh()
                },
                Store.defaultHour(this, type), Store.defaultMinute(this, type), true
            ).show()
        }

        b.switchAlarm.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            Store.setAlarmEnabled(this, type, checked)
            if (!checked && AlarmService.ringingType == type) AlarmService.stop(this)
            Scheduler.scheduleAll(this)
            refresh()
        }

        buildSlots()
        b.btnTest.setOnClickListener { AlarmService.start(this, type, test = true) }
    }

    private fun buildSlots() {
        for (slot in 0 until Store.SLOTS) {
            val row = ItemAlarmSlotBinding.inflate(layoutInflater, b.slotList, false)
            row.tvSlotName.text = "Alarm ${slot + 1}"
            val pick = {
                TimePickerDialog(
                    this,
                    { _, h, m ->
                        Store.setSlotTime(this, type, slot, h, m)
                        Scheduler.scheduleAll(this)
                        refresh()
                    },
                    Store.slotHour(this, type, slot), Store.slotMinute(this, type, slot), true
                ).show()
            }
            row.tvSlotTime.setOnClickListener { pick() }
            row.btnSlotEdit.setOnClickListener { pick() }
            row.switchSlot.setOnCheckedChangeListener { _, checked ->
                if (updating) return@setOnCheckedChangeListener
                Store.setSlotEnabled(this, type, slot, checked)
                if (!Store.anySlotEnabled(this, type) && AlarmService.ringingType == type) {
                    AlarmService.stop(this)
                }
                Scheduler.scheduleAll(this)
                refresh()
            }
            slotViews.add(row)
            b.slotList.addView(row.root)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = Store.alarmEnabled(this, type)
        val anySlot = Store.anySlotEnabled(this, type)
        val done = Store.isDone(this, type) && Store.isTodayEnabled(this)

        b.tvTime.text = Store.scheduleLabel(this, type)

        updating = true
        b.switchAlarm.isChecked = enabled
        slotViews.forEachIndexed { slot, row ->
            row.tvSlotTime.text = Store.slotLabel(this, type, slot)
            row.switchSlot.isChecked = Store.slotEnabled(this, type, slot)
            val on = enabled && row.switchSlot.isChecked
            row.tvSlotTime.alpha = if (on) 1f else 0.4f
        }
        updating = false

        val (label, color) = when {
            !enabled -> "ALARM NONAKTIF" to R.color.text_secondary
            !anySlot -> "TIDAK ADA ALARM AKTIF" to R.color.text_secondary
            done -> "SEMUA ALARM OFF • SUDAH ABSEN" to R.color.green
            else -> "ALARM AKTIF" to R.color.blue
        }
        b.tvStatus.text = label
        b.tvStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))

        val next = Scheduler.nextAlarmMillis(this, type)
        b.tvNext.text = if (next != null) {
            "Berbunyi berikutnya: " + nextFmt.format(Date(next))
        } else {
            "Tidak ada jadwal alarm (alarm nonaktif atau tidak ada hari yang dipilih)."
        }
    }
}
