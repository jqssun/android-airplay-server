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

    // true while this activity is frontmost only because a session summoned it
    // (service start carrying EXTRA_SESSION_SUMMON); cleared when the user opens
    // the app themselves or interacts with it outside a session
    private var summonedBySession = false
    private var sessionEndJob: Job? = null
    private var lastSessionEndedAt = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            service = svc
            svc.logCallback = logCallback
            svc.pinCallback = pinCallback
            viewModel.bindService(svc)
            _watchSessionEnd(svc)
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

        summonedBySession = savedInstanceState?.getBoolean(STATE_SUMMONED_BY_SESSION)
            ?: (intent?.getBooleanExtra(EXTRA_SESSION_SUMMON, false) == true)

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

    // a summon only counts when it actually brings the activity forward; any other
    // relaunch (launcher, notification) is the user deliberately opening the app
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SESSION_SUMMON, false)) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                summonedBySession = true
            }
        } else {
            summonedBySession = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SUMMONED_BY_SESSION, summonedBySession)
    }

    // input while no session is active (and none just ended: the tail of the very
    // gesture that stopped one must not count) means the user wants to be here
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!summonedBySession) return
        val justEnded = SystemClock.elapsedRealtime() - lastSessionEndedAt < SESSION_END_INTERACTION_HOLDOFF_MS
        if (!_sessionActive(service) && !justEnded) {
            summonedBySession = false
        }
    }

    private fun _sessionActive(svc: AirPlayService?) =
        svc != null && (svc.videoPlaybackActive.value || svc.mirroringActive.value)

    private fun _watchSessionEnd(svc: AirPlayService) {
        sessionEndJob?.cancel()
        sessionEndJob = lifecycleScope.launch {
            var wasActive = false
            combine(svc.videoPlaybackActive, svc.mirroringActive) { video, mirror -> video || mirror }
                .distinctUntilChanged()
                .collect { active ->
                    if (!active && wasActive) {
                        lastSessionEndedAt = SystemClock.elapsedRealtime()
                        _onSessionEnded(svc)
                    }
                    wasActive = active
                }
        }
    }

    private fun _onSessionEnded(svc: AirPlayService) {
        if (!summonedBySession || !viewModel.returnToPreviousApp.value || isInPip.value) return
        lifecycleScope.launch {
            // grace period: a sender switching queue items stops one video and plays
            // the next on the same connection 1-2s later, which re-summons and must
            // cancel the step-aside instead of flashing through the previous app
            delay(SESSION_END_RETURN_GRACE_MS)
            if (_sessionActive(svc)) return@launch
            if (!summonedBySession || isInPip.value) return@launch
            summonedBySession = false
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

    override fun onStop() {
        super.onStop()
        // never stop mid-session
        if (!viewModel.runInBackground.value && !isChangingConfigurations &&
            viewModel.serverState.value == AirPlayService.ServerState.RUNNING &&
            viewModel.connectionCount.value == 0) {
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
        const val EXTRA_SESSION_SUMMON = "io.github.jqssun.airplay.extra.SESSION_SUMMON"
        private const val STATE_SUMMONED_BY_SESSION = "summonedBySession"
        // long enough that a queue-item switch cancels the pending step-aside,
        // short enough that a real stop still returns promptly
        private const val SESSION_END_RETURN_GRACE_MS = 2_500L
        // ignore "user interaction" this long after a session ends, so the stopping
        // gesture's tail or impatient presses can't clear summonedBySession
        private const val SESSION_END_INTERACTION_HOLDOFF_MS = 4_000L
    }
}
