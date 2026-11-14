package com.studioasmr.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OutputTarget(val uri: Uri, val pfd: ParcelFileDescriptor)

object Storage {
    fun outName(prefix: String, ext: String): String {
        val t = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "StudioASMR_" + prefix + "_" + t + ext
    }

    fun create(ctx: Context, video: Boolean, name: String): OutputTarget? {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "audio/mp4")
        values.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (video) "Movies/StudioASMR" else "Music/StudioASMR"
        )
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val collection = if (video) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val uri = ctx.contentResolver.insert(collection, values) ?: return null
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
        if (pfd == null) {
            ctx.contentResolver.delete(uri, null, null)
            return null
        }
        return OutputTarget(uri, pfd)
    }

    fun sizeOf(ctx: Context, uri: Uri): Long {
        val cols = arrayOf(MediaStore.MediaColumns.SIZE)
        val c = ctx.contentResolver.query(uri, cols, null, null, null) ?: return 0L
        c.use {
            return if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    fun finish(ctx: Context, t: OutputTarget): Long {
        try {
            t.pfd.close()
        } catch (e: Exception) {
        }
        val v = ContentValues()
        v.put(MediaStore.MediaColumns.IS_PENDING, 0)
        ctx.contentResolver.update(t.uri, v, null, null)
        return sizeOf(ctx, t.uri)
    }

    fun discard(ctx: Context, t: OutputTarget) {
        try {
            t.pfd.close()
        } catch (e: Exception) {
        }
        try {
            ctx.contentResolver.delete(t.uri, null, null)
        } catch (e: Exception) {
        }
    }

    fun copyFile(file: File, t: OutputTarget) {
        file.inputStream().use { input ->
            ParcelFileDescriptor.AutoCloseOutputStream(t.pfd).use { output ->
                input.copyTo(output, 1 shl 20)
            }
        }
    }
}

class TrackReader(
    private val ctx: Context,
    private val uri: Uri,
    private val wantVideo: Boolean
) {
    var ex: MediaExtractor = MediaExtractor()
    var index = -1
    var format: MediaFormat? = null
    var mime = ""
    var minUs = Long.MAX_VALUE
    var maxUs = 0L
    var count = 0
    var frameUs = 0L
    var base = 0L
    var loopLen = 0L
    var iter = 0L
    var muxerTrack = -1

    fun open(): Boolean {
        try {
            ex.setDataSource(ctx, uri, null)
        } catch (e: Exception) {
            return false
        }
        val prefix = if (wantVideo) "video/" else "audio/"
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith(prefix)) {
                index = i
                format = f
                mime = m
                break
            }
        }
        if (index < 0) return false
        ex.selectTrack(index)
        while (true) {
            val t = ex.sampleTime
            if (t < 0) break
            if (t < minUs) minUs = t
            if (t > maxUs) maxUs = t
            count++
            ex.advance()
        }
        if (count == 0) return false
        frameUs = if (count > 1) (maxUs - minUs) / (count - 1) else 0L
        base = minUs
        loopLen = maxUs - minUs + frameUs
        restart()
        return true
    }

    fun restart() {
        try {
            ex.release()
        } catch (e: Exception) {
        }
        ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        ex.selectTrack(index)
    }

    // Waktu absolut sampel berikutnya (sudah ditambah offset putaran).
    fun nextTime(): Long {
        var guard = 0
        while (true) {
            val t = ex.sampleTime
            if (t >= 0 && (t - base) < loopLen) {
                return (t - base) + iter * loopLen
            }
            guard++
            if (guard > 3) return Long.MAX_VALUE
            iter++
            restart()
        }
    }

    fun close() {
        try {
            ex.release()
        } catch (e: Exception) {
        }
    }
}

object Looper {

    fun loopMux(
        ctx: Context,
        videoSrc: Uri?,
        audioSrc: Uri?,
        audioFromVideo: Boolean,
        targetUs: Long,
        out: OutputTarget,
        startMs: Long
    ): String? {
        val list = ArrayList<TrackReader>()
        var rotation = 0

        if (videoSrc != null) {
            val v = TrackReader(ctx, videoSrc, true)
            if (!v.open()) {
                v.close()
                return "File video tidak valid"
            }
            list.add(v)
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(ctx, videoSrc)
                rotation = mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0
                mmr.release()
            } catch (e: Exception) {
                rotation = 0
            }
            if (audioFromVideo) {
                val a = TrackReader(ctx, videoSrc, false)
                if (a.open()) {
                    val nb = minOf(v.base, a.base)
                    v.base = nb
                    a.base = nb
                    v.loopLen = v.maxUs - nb + v.frameUs
                    a.loopLen = v.loopLen
                    list.add(a)
                } else {
                    a.close()
                }
            }
        }

        if (audioSrc != null && !audioFromVideo) {
            val a = TrackReader(ctx, audioSrc, false)
            if (!a.open()) {
                a.close()
                for (r in list) r.close()
                return "File audio tidak valid"
            }
            list.add(a)
        }

        if (list.isEmpty()) return "Tidak ada file yang dipilih"

        for (r in list) {
            if (r.loopLen <= 0L) {
                for (x in list) x.close()
                return "Durasi file tidak terbaca"
            }
            if (r.mime.startsWith("audio/") && r.mime != "audio/mp4a-latm") {
                for (x in list) x.close()
                return "Audio harus berformat AAC"
            }
        }

        val muxer = MediaMuxer(out.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var error: String? = null
        val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var lastReport = 0L

        try {
            if (rotation != 0) muxer.setOrientationHint(rotation)
            for (r in list) {
                r.muxerTrack = muxer.addTrack(r.format!!)
            }
            muxer.start()
            muxerStarted = true

            while (true) {
                if (RenderState.cancelRequested) break

                var best: TrackReader? = null
                var bestT = Long.MAX_VALUE
                for (r in list) {
                    val t = r.nextTime()
                    if (t < bestT) {
                        bestT = t
                        best = r
                    }
                }
                if (best == null || bestT >= targetUs) break

                val size = best.ex.readSampleData(buffer, 0)
                if (size < 0) {
                    best.ex.advance()
                    continue
                }
                val f = best.ex.sampleFlags
                val flags = if ((f and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, bestT, flags)
                muxer.writeSampleData(best.muxerTrack, buffer, info)
                best.ex.advance()

                val now = System.currentTimeMillis()
                if (now - lastReport > 400) {
                    lastReport = now
                    RenderState.report((bestT * 100 / targetUs).toInt(), startMs, "Menulis file...")
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "Gagal menulis file"
        } finally {
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    if (error == null) error = "Gagal menutup file"
                }
            }
            try {
                muxer.release()
            } catch (e: Exception) {
            }
            for (r in list) r.close()
        }

        if (error == null && RenderState.cancelRequested) error = "Dibatalkan"
        return error
    }
}
