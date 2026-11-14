package com.studioasmr.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Transcoder {

    // Menjalankan Transformer di thread utama lalu menunggu sampai selesai.
    // Hasil: null = sukses, selain itu = pesan error.
    private fun run(
        ctx: Context,
        item: EditedMediaItem,
        outPath: String,
        configure: (Transformer.Builder) -> Transformer.Builder,
        onProgress: (Int) -> Unit
    ): String? {
        val appCtx = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val errorRef = arrayOfNulls<String>(1)
        val transformerRef = arrayOfNulls<Transformer>(1)
        val progress = ProgressHolder()

        main.post {
            try {
                val listener = object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        errorRef[0] = exportException.message ?: "Proses gagal"
                        latch.countDown()
                    }
                }
                val t = configure(Transformer.Builder(appCtx)).addListener(listener).build()
                transformerRef[0] = t
                t.start(item, outPath)
            } catch (e: Exception) {
                errorRef[0] = e.message ?: "Proses gagal"
                latch.countDown()
            }
        }

        while (!latch.await(500, TimeUnit.MILLISECONDS)) {
            if (RenderState.cancelRequested) {
                main.post {
                    try {
                        transformerRef[0]?.cancel()
                    } catch (e: Exception) {
                    }
                }
                return "Dibatalkan"
            }
            main.post {
                try {
                    val t = transformerRef[0]
                    if (t != null) {
                        val state = t.getProgress(progress)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progress.progress)
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
        return errorRef[0]
    }

    // Kompres video ke bitrate tertentu. Resolusi tidak diubah, audio dipertahankan.
    fun compress(
        ctx: Context,
        src: Uri,
        outPath: String,
        videoBps: Int,
        useHevc: Boolean,
        onProgress: (Int) -> Unit
    ): String? {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(src)).build()
        val videoSettings = VideoEncoderSettings.Builder().setBitrate(videoBps).build()
        val factory = DefaultEncoderFactory.Builder(ctx.applicationContext)
            .setRequestedVideoEncoderSettings(videoSettings)
            .build()
        return run(ctx, item, outPath, { b ->
            b.setEncoderFactory(factory)
            b.setVideoMimeType(if (useHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            b
        }, onProgress)
    }

    // Ubah audio apa pun (MP3, WAV, OGG, dll) menjadi AAC supaya bisa di-loop tanpa render ulang.
    fun audioToAac(
        ctx: Context,
        src: Uri,
        outPath: String,
        kbps: Int,
        onProgress: (Int) -> Unit
    ): String? {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(src))
            .setRemoveVideo(true)
            .build()
        val audioSettings = AudioEncoderSettings.Builder().setBitrate(kbps * 1000).build()
        val factory = DefaultEncoderFactory.Builder(ctx.applicationContext)
            .setRequestedAudioEncoderSettings(audioSettings)
            .build()
        return run(ctx, item, outPath, { b ->
            b.setEncoderFactory(factory)
            b.setAudioMimeType(MimeTypes.AUDIO_AAC)
            b
        }, onProgress)
    }
}
