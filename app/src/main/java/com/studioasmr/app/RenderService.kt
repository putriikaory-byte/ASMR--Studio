package com.studioasmr.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File

object MediaInfo {
    fun durationSec(ctx: Context, uri: Uri): Long {
        return try {
            val m = MediaMetadataRetriever()
            m.setDataSource(ctx, uri)
            val ms = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            m.release()
            ms / 1000
        } catch (e: Exception) {
            0L
        }
    }

    fun sizeBytes(ctx: Context, uri: Uri): Long {
        return try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) {
                0L
            } else {
                val s = pfd.statSize
                pfd.close()
                s
            }
        } catch (e: Exception) {
            0L
        }
    }

    fun isAac(ctx: Context, uri: Uri): Boolean {
        val ex = MediaExtractor()
        var result = false
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    result = mime == "audio/mp4a-latm"
                    break
                }
            }
        } catch (e: Exception) {
            result = false
        } finally {
            ex.release()
        }
        return result
    }

    fun audioBps(ctx: Context, uri: Uri): Int {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    return if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        f.getInteger(MediaFormat.KEY_BIT_RATE)
                    } else {
                        192000
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            ex.release()
        }
        return 0
    }

    fun videoBpsFor(targetBytes: Long, durationSec: Long, audioBps: Int): Int {
        if (durationSec <= 0L) return 0
        val total = targetBytes * 8.0 * 0.97 / durationSec
        val v = total - audioBps
        return v.toLong().coerceIn(100000L, 200000000L).toInt()
    }
}

class RenderService : Service() {

    companion object {
        const val CHANNEL_ID = "render"
        const val NOTIF_ID = 1
        const val ACTION_CANCEL = "com.studioasmr.app.CANCEL"
    }

    private var wake: PowerManager.WakeLock? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            RenderState.cancelRequested = true
            return START_NOT_STICKY
        }

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification("Memulai...", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val job = RenderState.pendingJob
        if (job == null || worker != null) {
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        RenderState.pendingJob = null

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StudioASMR:render")
        wake?.acquire(12L * 60L * 60L * 1000L)

        RenderState.cancelRequested = false
        RenderState.running = true
        RenderState.percent = 0
        RenderState.message = "Memulai..."
        RenderState.etaSeconds = -1L
        RenderState.finishedText = ""
        RenderState.finishedOk = false

        val ticker = Thread {
            while (RenderState.running) {
                updateNotification()
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        ticker.start()

        val t = Thread {
            var err: String? = null
            try {
                err = execute(job)
            } catch (e: Exception) {
                err = e.message ?: "Terjadi kesalahan"
            }
            if (err == null) {
                RenderState.finishedOk = true
                RenderState.percent = 100
                RenderState.message = "Selesai"
            } else {
                RenderState.finishedOk = false
                RenderState.finishedText = if (err == "Dibatalkan") "Dibatalkan" else "Gagal: " + err
                RenderState.message = RenderState.finishedText
            }
            RenderState.running = false
            RenderState.cancelRequested = false

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID + 1, buildNotification(RenderState.finishedText, 100, false))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        worker = t
        t.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            wake?.release()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Proses rendering", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun updateNotification() {
        var text = RenderState.percent.toString() + "% • " + RenderState.message
        if (RenderState.etaSeconds > 0) {
            text += " • sisa " + Fmt.duration(RenderState.etaSeconds)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, RenderState.percent, true))
    }

    private fun buildNotification(text: String, pct: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Studio ASMR")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
        if (ongoing) {
            b.setProgress(100, pct, false)
            val cancel = PendingIntent.getService(
                this, 1,
                Intent(this, RenderService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, "Batalkan", cancel)
        } else {
            b.setAutoCancel(true)
        }
        return b.build()
    }

    private fun execute(job: RenderJob): String? {
        val ctx = applicationContext
        val startMs = System.currentTimeMillis()
        val outRef = arrayOfNulls<OutputTarget>(1)
        val tmpFiles = ArrayList<File>()
        var err: String? = null

        try {
            err = when (job.type) {
                JobType.VIDEO_LOOP -> doVideoLoop(ctx, job, startMs, outRef)
                JobType.AUDIO_LOOP -> doAudioLoop(ctx, job, startMs, outRef, tmpFiles)
                JobType.MIXER -> doMixer(ctx, job, startMs, outRef, tmpFiles)
                JobType.COMPRESS -> doCompress(ctx, job, startMs, outRef, tmpFiles)
            }
        } catch (e: Exception) {
            err = e.message ?: "Terjadi kesalahan"
        }

        for (f in tmpFiles) {
            try {
                f.delete()
            } catch (e: Exception) {
            }
        }

        val out = outRef[0]
        if (out != null) {
            if (err != null) {
                Storage.discard(ctx, out)
            } else {
                val size = Storage.finish(ctx, out)
                History.add(ctx, job.title, out.uri, size)
                val where = if (job.type == JobType.AUDIO_LOOP) "Music/StudioASMR" else "Movies/StudioASMR"
                var text = "Selesai: " + Fmt.size(size) + " • tersimpan di " + where
                val vu = job.videoUri
                if (job.type == JobType.COMPRESS && vu != null) {
                    text = "Selesai. Sebelum " + Fmt.size(MediaInfo.sizeBytes(ctx, vu)) +
                        ", sesudah " + Fmt.size(size) + " • tersimpan di " + where
                }
                RenderState.finishedText = text
            }
        }
        return err
    }

    private fun prepareAudio(
        ctx: Context,
        uri: Uri,
        startMs: Long,
        tmp: MutableList<File>,
        result: Array<Uri?>
    ): String? {
        if (MediaInfo.isAac(ctx, uri)) {
            result[0] = uri
            return null
        }
        RenderState.report(0, startMs, "Mengubah audio ke AAC...")
        val f = File(ctx.cacheDir, "audio_" + System.currentTimeMillis() + ".m4a")
        tmp.add(f)
        val e = Transcoder.audioToAac(ctx, uri, f.absolutePath, AppSettings.audioKbps(ctx)) { p ->
            RenderState.report(p / 4, startMs, "Mengubah audio ke AAC...")
        }
        if (e != null) return e
        result[0] = Uri.fromFile(f)
        return null
    }

    private fun doVideoLoop(
        ctx: Context,
        job: RenderJob,
        startMs: Long,
        outRef: Array<OutputTarget?>
    ): String? {
        val v = job.videoUri ?: return "Video belum dipilih"
        val out = Storage.create(ctx, true, Storage.outName("VideoLoop", ".mp4"))
            ?: return "Gagal membuat file hasil"
        outRef[0] = out
        RenderState.report(0, startMs, "Menyiapkan...")
        return Looper.loopMux(ctx, v, null, true, job.targetSeconds * 1000000L, out, startMs)
    }

    private fun doAudioLoop(
        ctx: Context,
        job: RenderJob,
        startMs: Long,
        outRef: Array<OutputTarget?>,
        tmp: MutableList<File>
    ): String? {
        val a = job.audioUri ?: return "Audio belum dipilih"
        val prepared = arrayOfNulls<Uri>(1)
        val e = prepareAudio(ctx, a, startMs, tmp, prepared)
        if (e != null) return e
        val out = Storage.create(ctx, false, Storage.outName("AudioLoop", ".m4a"))
            ?: return "Gagal membuat file hasil"
        outRef[0] = out
        return Looper.loopMux(ctx, null, prepared[0], false, job.targetSeconds * 1000000L, out, startMs)
    }

    private fun doMixer(
        ctx: Context,
        job: RenderJob,
        startMs: Long,
        outRef: Array<OutputTarget?>,
        tmp: MutableList<File>
    ): String? {
        val v = job.videoUri ?: return "Video belum dipilih"
        val a = job.audioUri ?: return "Audio belum dipilih"
        val prepared = arrayOfNulls<Uri>(1)
        val e = prepareAudio(ctx, a, startMs, tmp, prepared)
        if (e != null) return e
        val out = Storage.create(ctx, true, Storage.outName("Mixer", ".mp4"))
            ?: return "Gagal membuat file hasil"
        outRef[0] = out
        return Looper.loopMux(ctx, v, prepared[0], false, job.targetSeconds * 1000000L, out, startMs)
    }

    private fun doCompress(
        ctx: Context,
        job: RenderJob,
        startMs: Long,
        outRef: Array<OutputTarget?>,
        tmp: MutableList<File>
    ): String? {
        val src = job.videoUri ?: return "Video belum dipilih"
        val dur = MediaInfo.durationSec(ctx, src)
        if (dur <= 0L) return "Durasi video tidak terbaca"
        val vbps = MediaInfo.videoBpsFor(job.targetBytes, dur, MediaInfo.audioBps(ctx, src))
        if (vbps <= 0) return "Ukuran target tidak valid"

        val f = File(ctx.cacheDir, "kompres_" + System.currentTimeMillis() + ".mp4")
        tmp.add(f)
        RenderState.report(0, startMs, "Mengompres video...")
        var e = Transcoder.compress(ctx, src, f.absolutePath, vbps, AppSettings.useHevc(ctx)) { p ->
            RenderState.report(p, startMs, "Mengompres video...")
        }
        if (e != null && e != "Dibatalkan" && AppSettings.useHevc(ctx)) {
            try {
                f.delete()
            } catch (x: Exception) {
            }
            RenderState.report(0, startMs, "Mencoba H.264...")
            e = Transcoder.compress(ctx, src, f.absolutePath, vbps, false) { p ->
                RenderState.report(p, startMs, "Mengompres video (H.264)...")
            }
        }
        if (e != null) return e

        val out = Storage.create(ctx, true, Storage.outName("Kompres", ".mp4"))
            ?: return "Gagal membuat file hasil"
        outRef[0] = out
        RenderState.report(99, startMs, "Menyimpan hasil...")
        Storage.copyFile(f, out)
        return null
    }
}
