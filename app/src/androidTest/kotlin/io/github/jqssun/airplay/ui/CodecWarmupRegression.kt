package io.github.jqssun.airplay.ui

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import io.github.jqssun.airplay.renderer.VideoRenderer
import java.io.DataInputStream

/** Real MStar decoder regression: do not lose reference frames during cold startup. */
object CodecWarmupRegression {
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val result = Bundle()
        try {
            val frames = mutableListOf<ByteArray>()
            DataInputStream(context.assets.open("warmup-fixture.bin")).use { input ->
                while (input.available() > 0) {
                    val size = input.readInt()
                    check(size in 1..8_000_000) { "Invalid access unit size" }
                    frames += ByteArray(size).also { input.readFully(it) }
                }
            }
            check(frames.size == 30) { "Fixture must contain 30 access units" }
            repeat(3) { attempt ->
                val renderer = VideoRenderer(targetContext)
                try {
                    renderer.setResolution(3840, 1768)
                    check(renderer.selectDecoders(3840, 1768, 30, true)) { "HEVC decoder unavailable" }
                    renderer.scheduledOutputBufferRelease = false
                    renderer.startSession()
                    val origin = System.nanoTime()
                    // The real TCP sender can deliver a burst after codec setup.
                    // Keep every reference access unit; drain pressure belongs to the receiver.
                    frames.forEachIndexed { index, frame ->
                        renderer.feedFrame(frame, origin + index * 33_333_333L, true)
                    }
                    val drain = renderer.javaClass.getDeclaredMethod("drainOutput").apply { isAccessible = true }
                    val lastOutput = renderer.javaClass.getDeclaredField("_lastOutputFrameNs").apply { isAccessible = true }
                    val deadline = SystemClock.uptimeMillis() + 3000
                    while (lastOutput.getLong(renderer) == 0L && SystemClock.uptimeMillis() < deadline) {
                        drain.invoke(renderer)
                        SystemClock.sleep(10)
                    }
                    check(lastOutput.getLong(renderer) > 0L) { "Real decoder produced no output" }
                    check(renderer.droppedFrames == 0L) {
                        "Cold start ${attempt + 1} discarded ${renderer.droppedFrames} compressed reference frame(s)"
                    }
                    sendStatus(0, Bundle().apply { putString("stream", "Cold start ${attempt + 1}: real output, no compressed frames discarded\n") })
                } finally {
                    renderer.release()
                }
            }
            result.putString("stream", "PASS: three real HEVC cold starts without discarded compressed frames\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.cause?.message ?: error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
