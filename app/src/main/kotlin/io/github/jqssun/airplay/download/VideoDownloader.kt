package io.github.jqssun.airplay.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import io.github.jqssun.airplay.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// transformer requires a looper thread
class VideoDownloader(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null

    // null = idle, -1 = unknown, else 0..100
    private val _progress = MutableStateFlow<Int?>(null)
    val progress = _progress.asStateFlow()

    private val _progressTick = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val holder = ProgressHolder()
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                _progress.value = holder.progress
            }
            mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    fun start(location: String) = mainHandler.post {
        if (transformer != null) return@post
        _progress.value = -1
        val file = File(context.cacheDir, TEMP_NAME)
        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    _reset()
                    thread {
                        val path = _publish(file)
                        file.delete()
                        _toast(
                            if (path != null) context.getString(R.string.download_saved, path)
                            else context.getString(R.string.download_failed)
                        )
                    }
                }
                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    Log.w(TAG, "export failed", exception)
                    _reset()
                    file.delete()
                    _toast(context.getString(R.string.download_failed))
                }
            })
            .build()
        transformer = t
        t.start(MediaItem.fromUri(location), file.absolutePath)
        mainHandler.postDelayed(_progressTick, PROGRESS_INTERVAL_MS)
    }

    fun cancel() = mainHandler.post {
        transformer?.cancel() ?: return@post
        _reset()
        File(context.cacheDir, TEMP_NAME).delete()
    }

    private fun _reset() {
        mainHandler.removeCallbacks(_progressTick)
        transformer = null
        _progress.value = null
    }

    private fun _publish(file: File): String? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "AirPlay_$stamp.mp4"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                val path = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                return path ?: "${Environment.DIRECTORY_DOWNLOADS}/$name"
            } else {
                // pre-q public storage needs a runtime permission
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
                val dest = File(dir, name)
                file.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
                return dest.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "publish failed", e)
            return null
        }
    }

    private fun _toast(msg: String) = mainHandler.post {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "VideoDownloader"
        private const val TEMP_NAME = "video_download.mp4"
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
