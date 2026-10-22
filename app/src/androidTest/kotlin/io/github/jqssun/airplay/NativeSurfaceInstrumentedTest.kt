package io.github.jqssun.airplay

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.diagnostics.SurfaceProbeActivity
import io.github.jqssun.airplay.renderer.VideoPipeline
import io.github.jqssun.airplay.renderer.VideoRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class NativeSurfaceInstrumentedTest {
    @Test fun packagedNativeReceiverLibraryLoads() {
        System.loadLibrary("airplay_native")
    }

    @Test fun busyUiThreadDoesNotBlockVideoFrameDelivery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SurfaceProbeActivity>(Intent(context, SurfaceProbeActivity::class.java)).use { scenario ->
            lateinit var activity: SurfaceProbeActivity
            scenario.onActivity { activity = it }
            waitFor { activity.changedSize != null }
            val pipeline = VideoPipeline()
            val unblockUi = CountDownLatch(1)
            try {
                pipeline.setVideoSize(320, 240)
                pipeline.start()
                pipeline.setDisplaySurface(activity.videoView.holder.surface)
                val uiBlocked = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    uiBlocked.countDown()
                    unblockUi.await(4, TimeUnit.SECONDS)
                }
                assertTrue(uiBlocked.await(1, TimeUnit.SECONDS))
                val canvas = pipeline.inputSurface!!.lockCanvas(null)
                canvas.drawColor(Color.WHITE)
                pipeline.inputSurface!!.unlockCanvasAndPost(canvas)
                val deadline = SystemClock.uptimeMillis() + 1_000
                while (pipeline.renderedFrames == 0L && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
                assertTrue("Video must render while the UI thread is still blocked", pipeline.renderedFrames > 0)
            } finally {
                unblockUi.countDown()
                pipeline.release()
            }
        }
    }

    @Test fun lastDecodedPictureIsDrainedWithoutAnotherNetworkPacket() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encoded = encodeOneFrame()
        ActivityScenario.launch<SurfaceProbeActivity>(Intent(context, SurfaceProbeActivity::class.java)).use { scenario ->
            lateinit var activity: SurfaceProbeActivity
            scenario.onActivity { activity = it }
            waitFor { activity.changedSize != null }
            val renderer = VideoRenderer(context)
            try {
                renderer.selectDecoders(320, 240, 30, false)
                renderer.scheduledOutputBufferRelease = false
                renderer.setResolution(320, 240)
                renderer.setSurface(activity.videoView.holder.surface)
                renderer.startSession()
                renderer.feedFrame(encoded, System.nanoTime(), false)
                // Deliberately send no more data; the output pump must finish this frame.
                waitFor { renderer.renderedFrames > 0 }
                assertEquals(0L, renderer.droppedFrames)
            } finally {
                renderer.release()
            }
        }
    }

    @Test fun fullPanelPortraitAndPreviewUseNativeSurfaceBuffers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SurfaceProbeActivity>(Intent(context, SurfaceProbeActivity::class.java)).use { scenario ->
            lateinit var activity: SurfaceProbeActivity
            scenario.onActivity { activity = it }
            val panel = context.realDisplaySize()
            waitFor { activity.changedSize == panel }
            assertEquals("Fullscreen video buffers must match the physical panel", panel, activity.changedSize)

            // Same shape as the observed iPhone mirror; the containing UI can stay 1080p.
            val portraitWidth = (activity.videoView.height * 994.0 / 2160).toInt()
            scenario.onActivity { it.resizeVideo(portraitWidth, it.videoView.height) }
            waitFor { activity.changedSize?.second == panel.second && activity.changedSize?.first != panel.first }
            assertEquals(panel.second, activity.changedSize!!.second)

            scenario.onActivity { it.resizeVideo(-1, -1) }
            waitFor { activity.changedSize == panel }
            assertEquals("Returning from portrait must restore the full native buffer", panel, activity.changedSize)
        }
    }

    @Test fun fullscreenPipelineFitsWideAndPortraitImagesWithBlackBars() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SurfaceProbeActivity>(Intent(context, SurfaceProbeActivity::class.java)).use { scenario ->
            lateinit var activity: SurfaceProbeActivity
            scenario.onActivity { activity = it }
            waitFor { activity.changedSize == context.realDisplaySize() }
            val pipeline = VideoPipeline()
            try {
                pipeline.start()
                pipeline.setDisplaySurface(activity.videoView.holder.surface)
                for ((width, height) in listOf(3840 to 1768, 994 to 2160)) {
                    pipeline.setVideoSize(width, height)
                    val before = pipeline.renderedFrames
                    val canvas = pipeline.inputSurface!!.lockCanvas(null)
                    canvas.drawColor(Color.WHITE)
                    pipeline.inputSurface!!.unlockCanvasAndPost(canvas)
                    waitFor { pipeline.renderedFrames > before }
                    val pixels = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888)
                    val copied = CountDownLatch(1)
                    var result = -1
                    PixelCopy.request(activity.videoView, pixels, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                    assertTrue(copied.await(3, TimeUnit.SECONDS))
                    assertEquals(PixelCopy.SUCCESS, result)
                    assertTrue("Source center must remain visible", Color.red(pixels.getPixel(96, 54)) > 210)
                    if (width > height) {
                        assertTrue("Wide source needs black bars above and below", Color.red(pixels.getPixel(96, 2)) < 40)
                        assertTrue(Color.red(pixels.getPixel(96, 105)) < 40)
                        assertTrue(Color.red(pixels.getPixel(2, 54)) > 210)
                    } else {
                        assertTrue("Portrait source needs black side bars", Color.red(pixels.getPixel(2, 54)) < 40)
                        assertTrue(Color.red(pixels.getPixel(189, 54)) < 40)
                        assertTrue(Color.red(pixels.getPixel(96, 2)) > 210)
                    }
                    assertEquals("Video shape must not resize the native fullscreen surface", context.realDisplaySize(), activity.changedSize)
                    pixels.recycle()
                }
            } finally {
                pipeline.release()
            }
        }
    }

    @Test fun eglPipelineRendersAfterSurfaceResizeWithoutRestartingInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SurfaceProbeActivity>(Intent(context, SurfaceProbeActivity::class.java)).use { scenario ->
            lateinit var activity: SurfaceProbeActivity
            scenario.onActivity { activity = it }
            waitFor { activity.changedSize != null }
            val pipeline = VideoPipeline()
            try {
                pipeline.setVideoSize(1280, 720)
                pipeline.start()
                pipeline.setDisplaySurface(activity.videoView.holder.surface)
                repeat(3) {
                    val canvas = pipeline.inputSurface!!.lockCanvas(null)
                    canvas.drawColor(Color.WHITE)
                    canvas.drawRect(0f, 0f, 640f, 720f, Paint().apply { color = Color.BLACK })
                    pipeline.inputSurface!!.unlockCanvasAndPost(canvas)
                    SystemClock.sleep(50)
                }
                scenario.onActivity { it.resizeVideo(it.videoView.width / 2, it.videoView.height / 2) }
                waitFor { activity.changedSize?.first == context.realDisplaySize().first / 2 }
                pipeline.setDisplaySurface(activity.videoView.holder.surface)
                val framesBefore = pipeline.renderedFrames
                val canvas = pipeline.inputSurface!!.lockCanvas(null)
                canvas.drawColor(Color.WHITE)
                canvas.drawRect(0f, 0f, 640f, 720f, Paint().apply { color = Color.BLACK })
                pipeline.inputSurface!!.unlockCanvasAndPost(canvas)
                waitFor { pipeline.renderedFrames > framesBefore }
                val pixels = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                var result = -1
                val copyDeadline = SystemClock.uptimeMillis() + 3_000
                do {
                    val copied = CountDownLatch(1)
                    PixelCopy.request(activity.videoView, pixels, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                    assertTrue("PixelCopy did not complete", copied.await(3, TimeUnit.SECONDS))
                    if (result != PixelCopy.ERROR_SOURCE_NO_DATA) break
                    SystemClock.sleep(25)
                } while (SystemClock.uptimeMillis() < copyDeadline)
                assertEquals(PixelCopy.SUCCESS, result)
                assertTrue("Left side of resized video must remain black", Color.red(pixels.getPixel(4, 16)) < 40)
                assertTrue("Right side must retain the full source image, not a cropped old viewport", Color.red(pixels.getPixel(28, 16)) > 210)
                pixels.recycle()
            } finally {
                pipeline.release()
            }
        }
    }

    @Test fun landscapeFillRemovesPhonePillarboxingAndCanRepaintPausedFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SurfaceProbeActivity>(Intent(context, SurfaceProbeActivity::class.java)).use { scenario ->
            lateinit var activity: SurfaceProbeActivity
            scenario.onActivity { activity = it }
            waitFor { activity.changedSize == context.realDisplaySize() }
            val panel = activity.changedSize!!
            val pipeline = VideoPipeline()
            try {
                pipeline.setVideoSize(3840, 1768)
                pipeline.start()
                pipeline.setDisplaySurface(activity.videoView.holder.surface, panel.first, panel.second)
                val canvas = pipeline.inputSurface!!.lockCanvas(null)
                canvas.drawColor(Color.BLACK)
                val inset = (3840f - 1768f * 16f / 9f) / 2f
                canvas.drawRect(inset, 0f, 3840f - inset, 1768f, Paint().apply { color = Color.WHITE })
                pipeline.inputSurface!!.unlockCanvasAndPost(canvas)
                waitFor { pipeline.renderedFrames > 0 }
                for (fill in listOf(true, false, true)) {
                    val before = pipeline.renderedFrames
                    pipeline.setFillLandscape(fill)
                    waitFor { pipeline.renderedFrames > before }
                    // No new input picture: changing framing must repaint the paused one.
                    val pixels = copyVideo(activity)
                    val expectedWhite = fill
                    for ((x, y) in listOf(2 to 2, 189 to 2, 2 to 105, 189 to 105)) {
                        assertEquals("Framing change must affect all four corners", expectedWhite, Color.red(pixels.getPixel(x, y)) > 210)
                    }
                    assertTrue(Color.red(pixels.getPixel(96, 54)) > 210)
                    pixels.recycle()
                }
            } finally {
                pipeline.release()
            }
        }
    }

    @Test fun widePortraitAndResizeTransitionsRestoreAllFourCanvasCorners() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SurfaceProbeActivity>(Intent(context, SurfaceProbeActivity::class.java)).use { scenario ->
            lateinit var activity: SurfaceProbeActivity
            scenario.onActivity { activity = it }
            waitFor { activity.changedSize == context.realDisplaySize() }
            val panel = activity.changedSize!!
            val uiWidth = activity.videoView.width
            val uiHeight = activity.videoView.height
            val pipeline = VideoPipeline()
            try {
                pipeline.start()
                repeat(2) {
                    pipeline.setVideoSize(3840, 1768)
                    pipeline.setDisplaySurface(activity.videoView.holder.surface, panel.first, panel.second)
                    var before = pipeline.renderedFrames
                    var canvas = pipeline.inputSurface!!.lockCanvas(null)
                    canvas.drawColor(Color.WHITE)
                    pipeline.inputSurface!!.unlockCanvasAndPost(canvas)
                    waitFor { pipeline.renderedFrames > before }

                    scenario.onActivity { it.resizeVideo(uiWidth / 2, uiHeight / 2) }
                    waitFor { activity.changedSize?.first == panel.first / 2 }
                    pipeline.setVideoSize(994, 2160)
                    val small = activity.changedSize!!
                    pipeline.setDisplaySurface(activity.videoView.holder.surface, small.first, small.second)
                    before = pipeline.renderedFrames
                    canvas = pipeline.inputSurface!!.lockCanvas(null)
                    canvas.drawColor(Color.WHITE)
                    pipeline.inputSurface!!.unlockCanvasAndPost(canvas)
                    waitFor { pipeline.renderedFrames > before }

                    scenario.onActivity { it.resizeVideo(-1, -1) }
                    waitFor { activity.changedSize == panel }
                    pipeline.setVideoSize(3840, 2160)
                    pipeline.setDisplaySurface(activity.videoView.holder.surface, panel.first, panel.second)
                    before = pipeline.renderedFrames
                    canvas = pipeline.inputSurface!!.lockCanvas(null)
                    canvas.drawColor(Color.WHITE)
                    pipeline.inputSurface!!.unlockCanvasAndPost(canvas)
                    waitFor { pipeline.renderedFrames > before }
                    val pixels = copyVideo(activity)
                    for ((x, y) in listOf(2 to 2, 189 to 2, 2 to 105, 189 to 105)) {
                        assertTrue("Returning to fullscreen must paint every corner", Color.red(pixels.getPixel(x, y)) > 210)
                    }
                    pixels.recycle()
                }
            } finally {
                pipeline.release()
            }
        }
    }

    @Test fun previewSizingDropsNativePanelBuffersAndCanRestoreFullscreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SurfaceProbeActivity>(Intent(context, SurfaceProbeActivity::class.java)).use { scenario ->
            lateinit var activity: SurfaceProbeActivity
            scenario.onActivity { activity = it }
            waitFor { activity.changedSize == context.realDisplaySize() }
            val uiSize = activity.videoView.width to activity.videoView.height
            scenario.onActivity { (it.videoView as io.github.jqssun.airplay.ui.NativeVideoSurface).nativeResolutionEnabled = false }
            waitFor { activity.changedSize == uiSize }
            scenario.onActivity { (it.videoView as io.github.jqssun.airplay.ui.NativeVideoSurface).nativeResolutionEnabled = true }
            waitFor { activity.changedSize == context.realDisplaySize() }
        }
    }

    @Test fun directDecoderOutputSurvivesParkingAndReattachingWithoutFourKGlCanvas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encoded = encodeOneFrame()
        val intent = Intent(context, SurfaceProbeActivity::class.java).putExtra("native_resolution", false)
        ActivityScenario.launch<SurfaceProbeActivity>(intent).use { scenario ->
            lateinit var activity: SurfaceProbeActivity
            scenario.onActivity {
                activity = it
            }
            waitFor { activity.changedSize == (activity.videoView.width to activity.videoView.height) }
            val size = activity.changedSize!!
            val surface = activity.videoView.holder.surface
            val renderer = VideoRenderer(context)
            try {
                renderer.preferDirectOutput = true
                renderer.selectDecoders(320, 240, 30, false)
                renderer.scheduledOutputBufferRelease = false
                renderer.setResolution(320, 240)
                renderer.setSurface(surface, size.first, size.second)
                renderer.startSession()
                renderer.feedFrame(encoded, System.nanoTime(), false)
                waitFor { renderer.renderedFrames > 0 }
                assertTrue("The decoder must own the visible Surface directly", renderer.directOutputActive)
                renderer.clearSurface(surface)
                assertFalse(renderer.directOutputActive)
                renderer.setSurface(surface, size.first, size.second)
                assertTrue("Reattaching must preserve direct output", renderer.directOutputActive)
                val before = renderer.renderedFrames
                renderer.feedFrame(encoded, System.nanoTime(), false)
                waitFor { renderer.renderedFrames > before }
                SystemClock.sleep(100)
                val pixels = copyVideo(activity)
                assertTrue("The direct decoder picture must be visible", Color.red(pixels.getPixel(96, 54)) > 70)
                pixels.recycle()
            } finally {
                renderer.release()
            }
        }
    }

    private fun copyVideo(activity: SurfaceProbeActivity): Bitmap {
        val pixels = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888)
        var result = -1
        val deadline = SystemClock.uptimeMillis() + 3_000
        do {
            val copied = CountDownLatch(1)
            PixelCopy.request(activity.videoView, pixels, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue(copied.await(3, TimeUnit.SECONDS))
            if (result != PixelCopy.ERROR_SOURCE_NO_DATA) break
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(PixelCopy.SUCCESS, result)
        return pixels
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertTrue("Timed out waiting for the real Surface dimensions", condition())
    }

    private fun encodeOneFrame(): ByteArray {
        val encoder = MediaCodec.createEncoderByType("video/avc")
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 320, 240).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val index = encoder.dequeueInputBuffer(2_000_000)
            check(index >= 0) { "No encoder input buffer" }
            val frame = ByteArray(320 * 240 * 3 / 2) { 0x80.toByte() }
            encoder.getInputBuffer(index)!!.apply { clear(); put(frame) }
            encoder.queueInputBuffer(index, 0, frame.size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val output = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                val decoded = encoder.dequeueOutputBuffer(info, 100_000)
                if (decoded < 0) continue
                if (info.size > 0) {
                    val buffer = encoder.getOutputBuffer(decoded)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    output.write(bytes)
                }
                encoder.releaseOutputBuffer(decoded, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            return output.toByteArray().also { check(it.isNotEmpty()) { "Encoder returned no video" } }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
    }
}
