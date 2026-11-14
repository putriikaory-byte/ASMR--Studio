package com.studioasmr.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var screen = "home"
    private var pollRunnable: Runnable? = null

    private var videoUri: Uri? = null
    private var audioUri: Uri? = null
    private var lastMinutes = "60"
    private var lastMb = "500"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
        scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scroll)
        if (RenderState.running) showProgress() else showHome()
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen == "home" || screen == "progress") {
            super.onBackPressed()
        } else {
            showHome()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        if (requestCode == 1) videoUri = uri
        if (requestCode == 2) audioUri = uri
        when (screen) {
            "videoloop" -> showVideoLoop()
            "audioloop" -> showAudioLoop()
            "mixer" -> showMixer()
            "compress" -> showCompress()
        }
    }

    // ---------- bantuan umum ----------

    private fun stopPolling() {
        val r = pollRunnable
        if (r != null) handler.removeCallbacks(r)
        pollRunnable = null
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun begin(title: String, back: Boolean) {
        stopPolling()
        content.removeAllViews()
        val pad = Ui.dp(this, 20)
        content.setPadding(pad, Ui.dp(this, 56), pad, Ui.dp(this, 48))
        if (back) {
            Ui.add(content, Ui.button(this, "← Kembali", false) { showHome() }, 0)
        }
        Ui.add(content, Ui.text(this, title, 26f, Ui.TEXT, true), if (back) 16 else 0)
        scroll.scrollTo(0, 0)
    }

    private fun displayName(uri: Uri): String {
        var n = "file"
        try {
            val c = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (c != null) {
                c.use {
                    if (it.moveToFirst()) n = it.getString(0) ?: "file"
                }
            }
        } catch (e: Exception) {
        }
        return n
    }

    private fun pickFile(video: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = if (video) "video/*" else "audio/*"
        startActivityForResult(i, if (video) 1 else 2)
    }

    private fun numberField(initial: String): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(initial)
        e.setTextColor(Ui.TEXT)
        e.setHintTextColor(Ui.SUB)
        e.textSize = 18f
        e.background = Ui.rounded(Ui.BTN2, Ui.dp(this, 12).toFloat())
        e.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12))
        return e
    }

    private fun watch(e: EditText, onChange: (String) -> Unit) {
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
    }

    private fun presetRow(field: EditText, labels: List<String>, values: List<String>) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (i in labels.indices) {
            val b = Ui.button(this, labels[i], false) { field.setText(values[i]) }
            b.textSize = 13f
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10))
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i < labels.size - 1) p.marginEnd = Ui.dp(this, 6)
            row.addView(b, p)
        }
        Ui.add(content, row, 8)
    }

    private fun parseMinutes(et: EditText): Long? {
        val m = et.text.toString().toLongOrNull() ?: 0L
        if (m < 3L || m > 600L) {
            toast("Durasi harus 3 sampai 600 menit (maksimal 10 jam)")
            return null
        }
        return m
    }

    private fun startJob(type: JobType, seconds: Long, targetBytes: Long, title: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        RenderState.finishedText = ""
        RenderState.finishedOk = false
        RenderState.percent = 0
        RenderState.message = "Memulai..."
        RenderState.etaSeconds = -1L
        RenderState.cancelRequested = false
        RenderState.running = true
        RenderState.pendingJob = RenderJob(type, videoUri, audioUri, seconds, targetBytes, title)
        startForegroundService(Intent(this, RenderService::class.java))
        showProgress()
    }

    // ---------- layar utama ----------

    private fun showHome() {
        screen = "home"
        begin("Studio ASMR", false)
        Ui.add(content, Ui.text(this, "Edit video dan audio ASMR langsung di HP, tanpa internet.", 14f, Ui.SUB), 6)

        if (RenderState.running) {
            val card = Ui.card(this)
            card.addView(Ui.text(this, "Proses sedang berjalan (" + RenderState.percent + "%)", 15f, Ui.ACCENT, true))
            Ui.add(card, Ui.button(this, "Lihat progres", true) { showProgress() }, 10)
            Ui.add(content, card, 16)
        }

        Ui.add(content, Ui.button(this, "🎬  Video Loop", false) { showVideoLoop() }, 20)
        Ui.add(content, Ui.button(this, "🎧  Audio Loop", false) { showAudioLoop() }, 10)
        Ui.add(content, Ui.button(this, "🎞  Video + Audio Mixer", false) { showMixer() }, 10)
        Ui.add(content, Ui.button(this, "📦  Video Compressor", false) { showCompress() }, 10)
        Ui.add(content, Ui.button(this, "🕘  Riwayat Hasil", false) { showHistory() }, 10)
        Ui.add(content, Ui.button(this, "⚙  Pengaturan", false) { showSettings() }, 10)
    }

    // ---------- Video Loop ----------

    private fun showVideoLoop() {
        screen = "videoloop"
        begin("Video Loop", true)
        Ui.add(
            content,
            Ui.text(this, "Ulang video otomatis jadi satu file panjang tanpa jeda hitam. Suara asli video dipertahankan.", 14f, Ui.SUB),
            8
        )

        val v = videoUri
        var dur = 0L
        var size = 0L
        val card = Ui.card(this)
        if (v == null) {
            card.addView(Ui.text(this, "Belum ada video dipilih", 15f))
        } else {
            dur = MediaInfo.durationSec(this, v)
            size = MediaInfo.sizeBytes(this, v)
            card.addView(Ui.text(this, displayName(v), 15f, Ui.TEXT, true))
            Ui.add(card, Ui.text(this, "Durasi " + Fmt.duration(dur) + " • " + Fmt.size(size), 13f, Ui.SUB), 4)
        }
        Ui.add(card, Ui.button(this, "Pilih video", false) { pickFile(true) }, 12)
        Ui.add(content, card, 16)

        Ui.add(content, Ui.text(this, "Durasi hasil (menit, 3 sampai 600)", 14f, Ui.SUB), 20)
        val et = numberField(lastMinutes)
        Ui.add(content, et, 6)
        presetRow(et, listOf("3 mnt", "30 mnt", "1 jam", "3 jam", "10 jam"), listOf("3", "30", "60", "180", "600"))
        val est = Ui.text(this, "", 14f, Ui.SUB)
        Ui.add(content, est, 12)

        val totalDur = dur
        val totalSize = size
        fun refresh() {
            val m = et.text.toString().toLongOrNull() ?: 0L
            var s = "Durasi hasil: " + Fmt.duration(m * 60L)
            if (totalDur > 0L && m > 0L) {
                s += "\nPerkiraan ukuran: " + Fmt.size(totalSize * m * 60L / totalDur)
            }
            est.text = s
        }
        refresh()
        watch(et) { t ->
            lastMinutes = t
            refresh()
        }

        Ui.add(content, Ui.button(this, "Mulai Render", true) {
            if (v == null) {
                toast("Pilih video dulu")
            } else {
                val m = parseMinutes(et)
                if (m != null) {
                    startJob(JobType.VIDEO_LOOP, m * 60L, 0L, "Video Loop • " + Fmt.duration(m * 60L))
                }
            }
        }, 16)
    }

    // ---------- Audio Loop ----------

    private fun showAudioLoop() {
        screen = "audioloop"
        begin("Audio Loop", true)
        Ui.add(
            content,
            Ui.text(this, "Ulang audio otomatis jadi satu file panjang. Audio non-AAC (misalnya MP3) diubah ke AAC dulu.", 14f, Ui.SUB),
            8
        )

        val a = audioUri
        var dur = 0L
        var size = 0L
        var aac = true
        val card = Ui.card(this)
        if (a == null) {
            card.addView(Ui.text(this, "Belum ada audio dipilih", 15f))
        } else {
            dur = MediaInfo.durationSec(this, a)
            size = MediaInfo.sizeBytes(this, a)
            aac = MediaInfo.isAac(this, a)
            card.addView(Ui.text(this, displayName(a), 15f, Ui.TEXT, true))
            Ui.add(card, Ui.text(this, "Durasi " + Fmt.duration(dur) + " • " + Fmt.size(size), 13f, Ui.SUB), 4)
            if (!aac) {
                Ui.add(card, Ui.text(this, "Akan diubah ke AAC dulu sebelum diulang.", 13f, Ui.SUB), 4)
            }
        }
        Ui.add(card, Ui.button(this, "Pilih audio", false) { pickFile(false) }, 12)
        Ui.add(content, card, 16)

        Ui.add(content, Ui.text(this, "Durasi hasil (menit, 3 sampai 600)", 14f, Ui.SUB), 20)
        val et = numberField(lastMinutes)
        Ui.add(content, et, 6)
        presetRow(et, listOf("3 mnt", "30 mnt", "1 jam", "3 jam", "10 jam"), listOf("3", "30", "60", "180", "600"))
        val est = Ui.text(this, "", 14f, Ui.SUB)
        Ui.add(content, est, 12)

        val totalDur = dur
        val totalSize = size
        val isAac = aac
        val kbps = AppSettings.audioKbps(this)
        fun refresh() {
            val m = et.text.toString().toLongOrNull() ?: 0L
            var s = "Durasi hasil: " + Fmt.duration(m * 60L)
            if (totalDur > 0L && m > 0L) {
                val bytes = if (isAac) {
                    totalSize * m * 60L / totalDur
                } else {
                    m * 60L * kbps * 125L
                }
                s += "\nPerkiraan ukuran: " + Fmt.size(bytes)
            }
            est.text = s
        }
        refresh()
        watch(et) { t ->
            lastMinutes = t
            refresh()
        }

        Ui.add(content, Ui.button(this, "Mulai Render", true) {
            if (a == null) {
                toast("Pilih audio dulu")
            } else {
                val m = parseMinutes(et)
                if (m != null) {
                    startJob(JobType.AUDIO_LOOP, m * 60L, 0L, "Audio Loop • " + Fmt.duration(m * 60L))
                }
            }
        }, 16)
    }

    // ---------- Mixer ----------

    private fun showMixer() {
        screen = "mixer"
        begin("Video + Audio Mixer", true)
        Ui.add(
            content,
            Ui.text(this, "Suara asli video dihapus total, diganti audio pilihanmu. Video dan audio diulang sampai durasi yang kamu mau.", 14f, Ui.SUB),
            8
        )

        val v = videoUri
        val a = audioUri
        var vDur = 0L
        var vSize = 0L

        val cardV = Ui.card(this)
        if (v == null) {
            cardV.addView(Ui.text(this, "Belum ada video dipilih", 15f))
        } else {
            vDur = MediaInfo.durationSec(this, v)
            vSize = MediaInfo.sizeBytes(this, v)
            cardV.addView(Ui.text(this, displayName(v), 15f, Ui.TEXT, true))
            Ui.add(cardV, Ui.text(this, "Durasi " + Fmt.duration(vDur) + " • " + Fmt.size(vSize), 13f, Ui.SUB), 4)
        }
        Ui.add(cardV, Ui.button(this, "Pilih video", false) { pickFile(true) }, 12)
        Ui.add(content, cardV, 16)

        val cardA = Ui.card(this)
        if (a == null) {
            cardA.addView(Ui.text(this, "Belum ada audio dipilih", 15f))
        } else {
            cardA.addView(Ui.text(this, displayName(a), 15f, Ui.TEXT, true))
            Ui.add(
                cardA,
                Ui.text(this, "Durasi " + Fmt.duration(MediaInfo.durationSec(this, a)) + " • " + Fmt.size(MediaInfo.sizeBytes(this, a)), 13f, Ui.SUB),
                4
            )
        }
        Ui.add(cardA, Ui.button(this, "Pilih audio", false) { pickFile(false) }, 12)
        Ui.add(content, cardA, 12)

        Ui.add(content, Ui.text(this, "Durasi hasil (menit, 3 sampai 600)", 14f, Ui.SUB), 20)
        val et = numberField(lastMinutes)
        Ui.add(content, et, 6)
        presetRow(et, listOf("3 mnt", "30 mnt", "1 jam", "3 jam", "10 jam"), listOf("3", "30", "60", "180", "600"))
        val est = Ui.text(this, "", 14f, Ui.SUB)
        Ui.add(content, est, 12)

        val totalDur = vDur
        val totalSize = vSize
        fun refresh() {
            val m = et.text.toString().toLongOrNull() ?: 0L
            var s = "Durasi hasil: " + Fmt.duration(m * 60L)
            if (totalDur > 0L && m > 0L) {
                s += "\nPerkiraan ukuran: sekitar " + Fmt.size(totalSize * m * 60L / totalDur)
            }
            est.text = s
        }
        refresh()
        watch(et) { t ->
            lastMinutes = t
            refresh()
        }

        Ui.add(content, Ui.button(this, "Mulai Render", true) {
            if (v == null || a == null) {
                toast("Pilih video dan audio dulu")
            } else {
                val m = parseMinutes(et)
                if (m != null) {
                    startJob(JobType.MIXER, m * 60L, 0L, "Mixer • " + Fmt.duration(m * 60L))
                }
            }
        }, 16)
    }

    // ---------- Kompres ----------

    private fun showCompress() {
        screen = "compress"
        begin("Kompres Video", true)
        Ui.add(
            content,
            Ui.text(this, "Perkecil ukuran video ke target yang kamu pilih. Resolusi tidak diubah dan audio tetap ada.", 14f, Ui.SUB),
            8
        )

        val v = videoUri
        var dur = 0L
        var size = 0L
        var abps = 0
        val card = Ui.card(this)
        if (v == null) {
            card.addView(Ui.text(this, "Belum ada video dipilih", 15f))
        } else {
            dur = MediaInfo.durationSec(this, v)
            size = MediaInfo.sizeBytes(this, v)
            abps = MediaInfo.audioBps(this, v)
            card.addView(Ui.text(this, displayName(v), 15f, Ui.TEXT, true))
            Ui.add(card, Ui.text(this, "Ukuran asli " + Fmt.size(size) + " • Durasi " + Fmt.duration(dur), 13f, Ui.SUB), 4)
        }
        Ui.add(card, Ui.button(this, "Pilih video", false) { pickFile(true) }, 12)
        Ui.add(content, card, 16)

        Ui.add(content, Ui.text(this, "Ukuran target (MB)", 14f, Ui.SUB), 20)
        val et = numberField(lastMb)
        Ui.add(content, et, 6)
        presetRow(et, listOf("100 MB", "200 MB", "300 MB", "500 MB", "1 GB"), listOf("100", "200", "300", "500", "1024"))
        val est = Ui.text(this, "", 14f, Ui.SUB)
        Ui.add(content, est, 12)

        val totalDur = dur
        val totalSize = size
        val audioBps = abps
        fun refresh() {
            val mb = et.text.toString().toLongOrNull() ?: 0L
            if (v == null || totalDur <= 0L || mb <= 0L) {
                est.text = ""
                return
            }
            val target = mb * 1048576L
            val vbps = MediaInfo.videoBpsFor(target, totalDur, audioBps)
            var s = "Ukuran sebelum: " + Fmt.size(totalSize) +
                "\nPerkiraan sesudah: sekitar " + Fmt.size((target * 0.97).toLong()) +
                "\nBitrate video: sekitar " + (vbps / 1000) + " kbps"
            if (target >= totalSize) {
                s += "\n⚠ Target harus lebih kecil dari ukuran asli."
            } else if (vbps < 500000) {
                s += "\n⚠ Target sangat kecil, kualitas gambar bisa turun jelas."
            }
            est.text = s
        }
        refresh()
        watch(et) { t ->
            lastMb = t
            refresh()
        }

        Ui.add(content, Ui.button(this, "Mulai Kompres", true) {
            val mb = et.text.toString().toLongOrNull() ?: 0L
            if (v == null) {
                toast("Pilih video dulu")
            } else if (mb < 5L) {
                toast("Ukuran target minimal 5 MB")
            } else if (mb * 1048576L >= totalSize) {
                toast("Target harus lebih kecil dari ukuran asli")
            } else {
                startJob(JobType.COMPRESS, 0L, mb * 1048576L, "Kompres • target " + mb + " MB")
            }
        }, 16)
    }

    // ---------- Progres ----------

    private fun showProgress() {
        screen = "progress"
        begin("Sedang memproses", false)

        val card = Ui.card(this)
        val pct = Ui.text(this, "0%", 44f, Ui.ACCENT, true)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        val msg = Ui.text(this, "", 15f)
        val eta = Ui.text(this, "", 14f, Ui.SUB)
        card.addView(pct)
        Ui.add(card, bar, 8)
        Ui.add(card, msg, 12)
        Ui.add(card, eta, 4)
        Ui.add(content, card, 16)

        Ui.add(
            content,
            Ui.text(this, "Proses tetap berjalan di latar belakang. Kamu boleh membuka aplikasi lain atau mematikan layar.", 13f, Ui.SUB),
            12
        )

        val cancel = Ui.button(this, "Batalkan", false) {
            RenderState.cancelRequested = true
            toast("Membatalkan...")
        }
        cancel.setTextColor(Ui.DANGER)
        Ui.add(content, cancel, 16)

        val runnable = object : Runnable {
            override fun run() {
                pct.text = RenderState.percent.toString() + "%"
                bar.progress = RenderState.percent
                msg.text = RenderState.message
                eta.text = if (RenderState.etaSeconds > 0L) {
                    "Perkiraan sisa waktu: " + Fmt.duration(RenderState.etaSeconds)
                } else {
                    ""
                }
                if (!RenderState.running && RenderState.finishedText.isNotEmpty()) {
                    showResult()
                    return
                }
                handler.postDelayed(this, 500)
            }
        }
        pollRunnable = runnable
        handler.post(runnable)
    }

    private fun showResult() {
        screen = "result"
        begin(if (RenderState.finishedOk) "Selesai ✅" else "Proses berhenti", false)
        val card = Ui.card(this)
        card.addView(Ui.text(this, RenderState.finishedText, 15f))
        Ui.add(content, card, 16)

        if (RenderState.finishedOk) {
            val last = History.load(this).firstOrNull()
            if (last != null) {
                Ui.add(content, Ui.button(this, "Buka hasil", true) { openUri(Uri.parse(last.uri)) }, 16)
            }
        }
        Ui.add(content, Ui.button(this, "Kembali ke menu", false) { showHome() }, 12)
    }

    private fun openUri(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            toast("Tidak bisa membuka file ini (mungkin sudah dihapus)")
        }
    }

    // ---------- Riwayat ----------

    private fun showHistory() {
        screen = "history"
        begin("Riwayat Hasil", true)
        val items = History.load(this)
        if (items.isEmpty()) {
            Ui.add(content, Ui.text(this, "Belum ada hasil rendering.", 15f, Ui.SUB), 16)
            return
        }
        for (item in items) {
            val card = Ui.card(this)
            card.addView(Ui.text(this, item.title, 15f, Ui.TEXT, true))
            Ui.add(card, Ui.text(this, item.time + " • " + Fmt.size(item.size), 13f, Ui.SUB), 4)
            Ui.add(card, Ui.button(this, "Buka", false) { openUri(Uri.parse(item.uri)) }, 10)
            Ui.add(content, card, 12)
        }
        Ui.add(content, Ui.button(this, "Hapus daftar riwayat (file tidak dihapus)", false) {
            History.clear(this)
            showHistory()
        }, 20)
    }

    // ---------- Pengaturan ----------

    private fun showSettings() {
        screen = "settings"
        begin("Pengaturan", true)

        Ui.add(content, Ui.text(this, "Kualitas audio saat konversi ke AAC (kbps)", 14f, Ui.SUB), 16)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val options = listOf(128, 192, 256, 320)
        for (i in options.indices) {
            val k = options[i]
            val b = Ui.button(this, k.toString(), AppSettings.audioKbps(this) == k) {
                AppSettings.setAudioKbps(this, k)
                showSettings()
            }
            b.minWidth = 0
            b.minimumWidth = 0
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i < options.size - 1) p.marginEnd = Ui.dp(this, 6)
            row.addView(b, p)
        }
        Ui.add(content, row, 8)

        val sw = Switch(this)
        sw.text = "Pakai HEVC (H.265) untuk kompres"
        sw.setTextColor(Ui.TEXT)
        sw.isChecked = AppSettings.useHevc(this)
        sw.setOnCheckedChangeListener { _, checked -> AppSettings.setUseHevc(this, checked) }
        Ui.add(content, sw, 24)
        Ui.add(
            content,
            Ui.text(this, "HEVC memberi gambar lebih bagus di ukuran yang sama. Kalau HP tidak mendukung, otomatis memakai H.264.", 13f, Ui.SUB),
            6
        )

        Ui.add(
            content,
            Ui.text(this, "Hasil video disimpan di Movies/StudioASMR dan audio di Music/StudioASMR. File asli tidak pernah dihapus.", 13f, Ui.SUB),
            24
        )
    }
}
