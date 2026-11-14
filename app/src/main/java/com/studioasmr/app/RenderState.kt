package com.studioasmr.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class JobType { VIDEO_LOOP, AUDIO_LOOP, MIXER, COMPRESS }

class RenderJob(
    val type: JobType,
    val videoUri: Uri?,
    val audioUri: Uri?,
    val targetSeconds: Long,
    val targetBytes: Long,
    val title: String
)

object RenderState {
    @Volatile var pendingJob: RenderJob? = null
    @Volatile var running = false
    @Volatile var percent = 0
    @Volatile var message = "Siap"
    @Volatile var etaSeconds = -1L
    @Volatile var cancelRequested = false
    @Volatile var finishedText = ""
    @Volatile var finishedOk = false

    fun report(pct: Int, startMs: Long, msg: String) {
        percent = pct.coerceIn(0, 100)
        message = msg
        val elapsed = (System.currentTimeMillis() - startMs) / 1000
        etaSeconds = if (percent in 2..99) elapsed * (100 - percent) / percent else -1L
    }
}

object Fmt {
    fun size(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
        else String.format(Locale.US, "%.1f MB", mb)
    }

    fun duration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d jam %d menit", h, m)
            m > 0 -> String.format(Locale.US, "%d menit %d detik", m, s)
            else -> String.format(Locale.US, "%d detik", s)
        }
    }
}

class HistoryItem(val time: String, val title: String, val uri: String, val size: Long)

object History {
    private fun file(ctx: Context) = File(ctx.filesDir, "riwayat.txt")

    fun add(ctx: Context, title: String, uri: Uri, size: Long) {
        val t = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date())
        val line = listOf(t, title.replace("|", " "), uri.toString(), size.toString())
            .joinToString("|")
        file(ctx).appendText(line + "\n")
    }

    fun load(ctx: Context): List<HistoryItem> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 4) null else HistoryItem(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0L)
        }.reversed()
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}

object AppSettings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun audioKbps(ctx: Context): Int = p(ctx).getInt("audio_kbps", 192)
    fun setAudioKbps(ctx: Context, v: Int) {
        p(ctx).edit().putInt("audio_kbps", v).apply()
    }

    fun useHevc(ctx: Context): Boolean = p(ctx).getBoolean("hevc", true)
    fun setUseHevc(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("hevc", v).apply()
    }
}
