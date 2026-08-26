package io.github.jqssun.airplay

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.ui.MainScreen
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var service: AirPlayService? = null
    val isInPip = mutableStateOf(false)
    private val logCallback: (String) -> Unit = { viewModel.addLog(it) }
    private val pinCallback: (String?) -> Unit = { viewModel.showPin(it) }

    /**
     * True while this activity is in the foreground only because an AirPlay session
     * summoned it: the service's "Open this application on connect" start, marked
     * with [EXTRA_SESSION_SUMMON], brought it up over whatever the user was watching
     * (an HDMI input, another app).
     *
     * When a summoned session ends -- the sender stopping/disconnecting, the video
     * finishing, or a local TV-remote BACK/STOP -- and the "Return to previous app
     * after casting" setting is on, the task is sent to the back so Android restores
     * what was on screen before. The flag is cleared (falling back to the normal
     * stay-on-the-main-page behaviour) when the user opens the app themselves
     * (launcher/notification intent without the extra) or interacts with it while no
     * session is active, and it is NOT set when a session starts while the activity
     * was already on screen -- in both cases the user chose to be here.
     *
     * "Interacts outside a session" deliberately excludes a holdoff window right
     * after a session ends ([SESSION_END_INTERACTION_HOLDOFF_MS]): the tail of the
     * very gesture that stopped the session (the UP half of a BACK press) or an
     * impatient extra press must not clear the flag before the return-to-previous
     * grace period gets to read it.
     */
    private var summonedBySession = false
        set(value) {
            if (field != value) Log.i(TAG_SUMMON, "summonedBySession = $value")
            field = value
        }
    private var sessionEndJob: Job? = null

    // when the last video/mirroring session ended (elapsedRealtime), for the
    // interaction-clearing holdoff described on summonedBySession
    private var lastSessionEndedAt = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            service = svc
            svc.logCallback = logCallback
            svc.pinCallback = pinCallback
            viewModel.bindService(svc)
            watchSessionEnd(svc)
            if (viewModel.autoStart.value && svc.serverState.value == AirPlayService.ServerState.STOPPED) {
                viewModel.startServer()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            sessionEndJob?.cancel()
            sessionEndJob = null
            service = null
            viewModel.unbindService()
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, service works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // fresh instance: session-summoned iff started by the service's on-connect
        // intent; on recreation (config change / process death) restore the tracked
        // state instead of re-reading the possibly-stale original intent
        summonedBySession = savedInstanceState?.getBoolean(STATE_SUMMONED_BY_SESSION)
            ?: (intent?.getBooleanExtra(EXTRA_SESSION_SUMMON, false) == true)
        Log.i(TAG_SUMMON, "onCreate: summoned=$summonedBySession restored=${savedInstanceState != null}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        bindService(Intent(this, AirPlayService::class.java), connection, BIND_AUTO_CREATE)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.updateFromService()
                    delay(200)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.keepScreenOn, viewModel.connectionCount) { keep, conns ->
                    keep && conns > 0
                }.collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // mirrors launchMainActivity()'s auto-launch-on-connect: send ourselves back
                // to whatever was showing before (e.g. Home Assistant) once the session that
                // brought us to the front actually ends, instead of just sitting on screen.
                var wasConnected = viewModel.connectionCount.value > 0
                viewModel.connectionCount.collect { conns ->
                    val isConnected = conns > 0
                    if (wasConnected && !isConnected && viewModel.launchOnConnect.value) {
                        moveTaskToBack(true)
                    }
                    wasConnected = isConnected
                }
            }
        }

        // auto-enter pre-declared in pip params
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(
                        viewModel.serverState, viewModel.connectionCount,
                        viewModel.videoPlaybackActive, viewModel.videoPlaybackAspect,
                        viewModel.videoAspect
                    ) { _, _, _, _, _ -> }.collect { setPictureInPictureParams(_pipParams()) }
                }
            }
        }

        setContent {
            AirPlayTheme {
                MainScreen(
                    viewModel = viewModel,
                    isInPip = isInPip.value,
                    onSurfaceAvailable = { viewModel.onSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onSurfaceDestroyed(it) },
                    onPip = { enterPip() }
                )
            }
        }
    }

    // singleTop relaunches: a session-connect start only counts as a summon when it
    // actually brings the activity forward (below STARTED here means it was in the
    // background; onNewIntent runs before onRestart/onStart on that path). Any other
    // relaunch -- launcher, notification -- is the user deliberately opening the app.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SESSION_SUMMON, false)) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                Log.i(TAG_SUMMON, "onNewIntent: summon while backgrounded (state=${lifecycle.currentState})")
                summonedBySession = true
            } else {
                Log.i(TAG_SUMMON, "onNewIntent: summon while already visible (state=${lifecycle.currentState}); flag unchanged ($summonedBySession)")
            }
        } else {
            Log.i(TAG_SUMMON, "onNewIntent: user-initiated relaunch")
            summonedBySession = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SUMMONED_BY_SESSION, summonedBySession)
    }

    // any input while no AV session is active (and none just ended -- see the
    // holdoff note on summonedBySession) means the user is deliberately using the
    // app: from here on it behaves as user-opened, not session-summoned
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!summonedBySession) return
        val svc = service
        val sessionActive = svc != null &&
            (svc.videoPlaybackActive.value || svc.mirroringActive.value)
        val justEnded = SystemClock.elapsedRealtime() - lastSessionEndedAt < SESSION_END_INTERACTION_HOLDOFF_MS
        if (!sessionActive && !justEnded) {
            Log.i(TAG_SUMMON, "cleared by user interaction outside a session")
            summonedBySession = false
        }
    }

    /**
     * Watches AirPlay Video / mirroring activity ending so a session-summoned
     * activity can get out of the way again (see [summonedBySession]). The service
     * flows are collected directly (event-driven) so this also covers stops that
     * arrive while the UI poll isn't scheduled.
     */
    private fun watchSessionEnd(svc: AirPlayService) {
        sessionEndJob?.cancel()
        sessionEndJob = lifecycleScope.launch {
            var wasActive = false
            combine(svc.videoPlaybackActive, svc.mirroringActive) { video, mirror -> video || mirror }
                .distinctUntilChanged()
                .collect { active ->
                    if (!active && wasActive) {
                        lastSessionEndedAt = SystemClock.elapsedRealtime()
                        onSessionEnded(svc)
                    }
                    wasActive = active
                }
        }
    }

    // a session just ended (sender stop/disconnect, playback finished, or local
    // remote stop): if it summoned us here and the setting allows, return to
    // whatever the user had on screen before
    private fun onSessionEnded(svc: AirPlayService) {
        Log.i(TAG_SUMMON, "session ended: summoned=$summonedBySession " +
            "returnToPrevious=${viewModel.returnToPreviousApp.value} pip=${isInPip.value}")
        if (!summonedBySession || !viewModel.returnToPreviousApp.value || isInPip.value) return
        Log.i(TAG_SUMMON, "return-to-previous scheduled in ${SESSION_END_RETURN_GRACE_MS}ms")
        lifecycleScope.launch {
            // grace period: senders switching queue items stop one video and play
            // the next on the same connection a second or two later -- long enough
            // that the follow-up play (which does re-summon, see the service's
            // onVideoPlay) cancels the step-aside instead of flashing through it
            delay(SESSION_END_RETURN_GRACE_MS)
            if (svc.videoPlaybackActive.value || svc.mirroringActive.value) {
                Log.i(TAG_SUMMON, "return-to-previous aborted: session active again")
                return@launch
            }
            if (!summonedBySession || isInPip.value) {
                Log.i(TAG_SUMMON, "return-to-previous aborted: summoned=$summonedBySession pip=${isInPip.value}")
                return@launch
            }
            summonedBySession = false
            // the video UI has already been dismissed (videoPlaybackActive is
            // mirrored event-driven into the viewmodel); now step aside so Android
            // restores the previous app / TV input
            Log.i(TAG_SUMMON, "moveTaskToBack: returning to previous app")
            moveTaskToBack(true)
        }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        enterPictureInPictureMode(_pipParams())
    }

    private fun _pipParams(): PictureInPictureParams {
        val aspect = if (viewModel.videoPlaybackActive.value) viewModel.videoPlaybackAspect.value
            else viewModel.videoAspect.value
        val rational = Rational((aspect * 1000).toInt().coerceIn(1, 2390), 1000)
        val builder = PictureInPictureParams.Builder().setAspectRatio(rational)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(_shouldAutoPip())
        }
        return builder.build()
    }

    private fun _shouldAutoPip(): Boolean =
        viewModel.serverState.value == AirPlayService.ServerState.RUNNING &&
            viewModel.connectionCount.value > 0

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && _shouldAutoPip()) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        isInPip.value = inPip
    }

    // "Run in background" off: leaving the app (HOME, or BACK finishing the activity)
    // shuts the server down instead of leaving the foreground service running. Never
    // tear down an active session from a lifecycle callback though -- PiP normally
    // keeps the activity out of the stopped state during sessions, but not on every
    // device/path (and PiP itself can be dismissed).
    override fun onStop() {
        super.onStop()
        if (!viewModel.runInBackground.value &&
            !isChangingConfigurations &&
            viewModel.connectionCount.value == 0 &&
            viewModel.serverState.value == AirPlayService.ServerState.RUNNING) {
            viewModel.stopServer()
        }
    }

    override fun onDestroy() {
        service?.let {
            if (it.logCallback === logCallback) it.logCallback = null
            if (it.pinCallback === pinCallback) it.pinCallback = null
        }
        unbindService(connection)
        super.onDestroy()
    }

    companion object {
        /** Intent extra set by AirPlayService when a session summons this activity. */
        const val EXTRA_SESSION_SUMMON = "io.github.jqssun.airplay.extra.SESSION_SUMMON"
        private const val STATE_SUMMONED_BY_SESSION = "summonedBySession"
        /** Logcat tag for the summoned/return-to-previous state machine. */
        private const val TAG_SUMMON = "AirPlaySummon"
        // long enough that a sender switching queue items (stop, then play the next
        // video on the same connection ~1-2s later) cancels the pending step-aside
        // before it fires, short enough that a real stop still returns promptly
        private const val SESSION_END_RETURN_GRACE_MS = 2_500L
        // ignore "user interaction" for this long after a session ends, so the tail
        // of the stopping gesture / impatient presses don't clear summonedBySession
        private const val SESSION_END_INTERACTION_HOLDOFF_MS = 4_000L
    }
}
