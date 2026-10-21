package io.github.jqssun.airplay.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.SurfaceView
import android.view.SurfaceHolder
import java.util.concurrent.atomic.AtomicInteger
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.jqssun.airplay.R
import android.view.Surface
import android.view.WindowManager
import android.view.KeyEvent
import io.github.jqssun.airplay.MainActivity
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.service.AirPlayService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real-view regression: retain the decoder's display surface while its UI slot moves. */
@OptIn(InternalComposeUiApi::class)
class SurfaceContinuityInstrumentation : Instrumentation() {
    private var skipNavigation = false
    private var testCase: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        testCase = arguments?.getString("test_case")
        skipNavigation = arguments?.getString("skip_navigation") == "true"
        start()
    }

    override fun onStart() {
        if (testCase == "codec_warmup") {
            CodecWarmupRegression.run(this)
            return
        }
        var bound = false
        var service: AirPlayService? = null
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as AirPlayService.LocalBinder).service
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val result = Bundle()
        try {
            val prefs = targetContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            val direct = prefs.getBoolean("direct_surface", false)
            check(prefs.getBoolean("idle_preview", false)) { "Enable idle_preview before running" }
            progress("Starting receiver activity")
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            progress("Activity started; binding service")
            bound = targetContext.bindService(Intent(targetContext, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound && connected.await(20, TimeUnit.SECONDS)) { "Service did not bind" }
            progress("Service bound; waiting for preview surface")
            val renderer = service!!.videoRenderer
            val field = renderer.javaClass.getDeclaredField("displaySurface").apply { isAccessible = true }
            fun surface(): Surface? {
                var value: Surface? = null
                runOnMainSync { value = field.get(renderer) as? Surface }
                return value
            }
            val deadline = SystemClock.uptimeMillis() + 20000
            while (surface()?.isValid != true && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            progress("Server=${service!!.serverState.value}; focus=${activity.hasWindowFocus()}; shown=${activity.window.decorView.isShown}")
            val original = checkNotNull(surface()) { "Preview surface was not created" }
            check(original.isValid) { "Preview surface is invalid" }
            val surfaceReplacements = AtomicInteger()
            runOnMainSync {
                fun find(view: View): SurfaceView? {
                    if (view is SurfaceView && view.holder.surface === original) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) {
                        find(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                val view = checkNotNull(find(activity.window.decorView)) { "Renderer SurfaceView not attached" }
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReplacements.incrementAndGet() }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReplacements.incrementAndGet() }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                })
            }
            awaitTab(activity, R.string.tab_overview)
            progress("Preview ready; selecting Logs")
            // TV starts with Stop focused. Down reaches the rightmost navigation
            // item; Left selects Logs. Exercise the remote path, not accessibility.
            if (!skipNavigation) {
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            }
            awaitTab(activity, R.string.tab_logs)
            if (direct) check(surface() === original && original.isValid && surfaceReplacements.get() == 0) { "Logs tab destroyed/replaced the mirroring surface" }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitTab(activity, R.string.tab_overview)
            val returnDeadline = SystemClock.uptimeMillis() + 10000
            while (surface()?.isValid != true && SystemClock.uptimeMillis() < returnDeadline) SystemClock.sleep(100)
            check(surface()?.isValid == true) { "Returning to Overview did not restore a valid surface" }
            if (direct) check(surface() === original && original.isValid && surfaceReplacements.get() == 0) { "Returning to Overview replaced the surface" }
            result.putString("stream", if (direct) "PASS: same valid Surface retained without native lifecycle replacement through Overview -> Logs -> Overview\n"
                else "PASS: default GL preview restored through Overview -> Logs -> Overview\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            if (bound) targetContext.unbindService(connection)
        }
    }

    private fun awaitTab(activity: Activity, labelRes: Int) {
        val label = activity.getString(labelRes)
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var selected = false
            runOnMainSync {
                fun nodes(view: View): List<SemanticsNode> = when (view) {
                    is ViewRootForTest -> listOf(view.semanticsOwner.rootSemanticsNode)
                    is ViewGroup -> (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) }
                    else -> emptyList()
                }
                fun matches(node: SemanticsNode): Boolean {
                    val text = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                    val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
                    return ((text || description) && node.config.getOrNull(SemanticsProperties.Selected) == true) ||
                        node.children.any { matches(it) }
                }
                selected = nodes(activity.window.decorView).any { matches(it) }
            }
            if (selected) {
                progress("Verified selected tab: $label")
                return
            }
            SystemClock.sleep(100)
        }
        error("Navigation did not select tab: $label")
    }

    private fun progress(message: String) {
        sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
}
