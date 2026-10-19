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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var service: AirPlayService? = null
    val isInPip = mutableStateOf(false)
    // receiver-launched session, not a manual open
    private var sessionSummoned = false
    private var summonPending = false
    private var wasStopped = false
    private var returnPending = false
    private var returnDeadline = 0L
    private val retreatHandler = Handler(Looper.getMainLooper())
    private val logCallback: (String) -> Unit = { viewModel.addLog(it) }
    private val pinCallback: (String?) -> Unit = { viewModel.showPin(it) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            service = svc
            svc.logCallback = logCallback
            svc.pinCallback = pinCallback
            viewModel.bindService(svc)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) _refreshForeground()
            _autoStart()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            viewModel.unbindService()
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (it.isNotEmpty()) _syncLocalNetwork(requested = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val summoned = intent?.getBooleanExtra(AirPlayService.EXTRA_SESSION_SUMMON, false) == true
        sessionSummoned = summoned
        summonPending = summoned
        Log.i(SUMMON_TAG, "create: summon=$summoned")
        savedInstanceState?.let { state ->
            sessionSummoned = state.getBoolean(STATE_SESSION_SUMMONED, sessionSummoned)
            if (state.getBoolean(STATE_RETURN_PENDING, false) && sessionSummoned) {
                val remaining = (state.getLong(STATE_RETURN_DEADLINE, 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                Log.i(SUMMON_TAG, "restore: returning in ${remaining}ms")
                _armReturn(remaining)
            }
        }

        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !_granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!hasLocalNetwork()) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())

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
                combine(viewModel.pref(Prefs.KEEP_SCREEN_ON), viewModel.connectionCount) { keep, conns ->
                    keep && conns > 0
                }.collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previous: Int? = null
                viewModel.connectionCount.collect { count ->
                    val was = previous
                    previous = count
                    when {
                        was != null && was > 0 && count == 0 -> _scheduleReturnToPrevious()
                        count > 0 -> _cancelReturnToPrevious("new sender inside grace, staying")
                    }
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
                    onPip = { enterPip() },
                    onLocalNetwork = ::_requestLocalNetwork
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val summoned = intent.getBooleanExtra(AirPlayService.EXTRA_SESSION_SUMMON, false)
        Log.i(SUMMON_TAG, "new intent: summon=$summoned")
        if (summoned) {
            sessionSummoned = true
            summonPending = true
        }
    }

    private fun _granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun _autoStart() {
        val svc = service ?: return
        if (viewModel.pref(Prefs.AUTO_START).value && svc.serverState.value == AirPlayService.ServerState.STOPPED && hasLocalNetwork()) {
            viewModel.startServer()
        }
    }
    
    private fun _refreshForeground() {
        if (service?.serverState?.value == AirPlayService.ServerState.RUNNING) {
            startService(Intent(this, AirPlayService::class.java))
        }
    }

    private fun _scheduleReturnToPrevious() {
        if (!sessionSummoned) {
            Log.i(SUMMON_TAG, "session ended: not summoned, staying")
            return
        }
        if (!viewModel.pref(Prefs.RETURN_ON_DISCONNECT).value) {
            Log.i(SUMMON_TAG, "session ended: setting off, staying")
            return
        }
        Log.i(SUMMON_TAG, "session ended: returning in ${SESSION_END_RETURN_GRACE_MS}ms")
        _armReturn(SESSION_END_RETURN_GRACE_MS)
    }

    private fun _armReturn(delayMs: Long) {
        retreatHandler.removeCallbacksAndMessages(null)
        returnPending = true
        returnDeadline = SystemClock.elapsedRealtime() + delayMs
        retreatHandler.postDelayed({
            val ready = returnPending && sessionSummoned && !isFinishing && !isDestroyed &&
                viewModel.connectionCount.value == 0 &&
                viewModel.pref(Prefs.RETURN_ON_DISCONNECT).value
            if (ready) {
                returnPending = false
                returnDeadline = 0L
                sessionSummoned = false
                Log.i(SUMMON_TAG, "moving task to back")
                moveTaskToBack(true)
            }
        }, delayMs)
    }

    private fun _cancelReturnToPrevious(reason: String) {
        if (!returnPending) return
        returnPending = false
        returnDeadline = 0L
        Log.i(SUMMON_TAG, reason)
        retreatHandler.removeCallbacksAndMessages(null)
    }

    private fun _syncLocalNetwork(requested: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        val p = Manifest.permission.ACCESS_LOCAL_NETWORK
        val prev = viewModel.localNetwork.value
        val now = when {
            hasLocalNetwork() -> LocalNetworkAccess.GRANTED
            shouldShowRequestPermissionRationale(p) -> LocalNetworkAccess.ASK
            requested || prev == LocalNetworkAccess.SETTINGS -> LocalNetworkAccess.SETTINGS
            else -> LocalNetworkAccess.ASK
        }
        viewModel.setLocalNetwork(now)
        if (now == LocalNetworkAccess.GRANTED && prev != LocalNetworkAccess.GRANTED) _autoStart()
    }

    private fun _requestLocalNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        if (viewModel.localNetwork.value == LocalNetworkAccess.SETTINGS) {
            startActivity(appSettingsIntent())
        } else {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK))
        }
    }

    override fun onResume() {
        super.onResume()
        isOnScreen = true
        Log.i(SUMMON_TAG, "resume: summonPending=$summonPending sessionSummoned=$sessionSummoned wasStopped=$wasStopped")
        // a manual resume clears a stale summon
        if (!summonPending && wasStopped) {
            sessionSummoned = false
            _cancelReturnToPrevious("manual open inside grace, staying")
        }
        summonPending = false
        wasStopped = false
    }

    override fun onPause() {
        super.onPause()
        isOnScreen = false
        summonPending = false
    }

    override fun onStart() {
        super.onStart()
        _syncLocalNetwork()
        _refreshForeground()
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
        wasStopped = true
        // never stop mid-session
        if (!viewModel.pref(Prefs.RUN_IN_BACKGROUND).value && !isChangingConfigurations &&
            viewModel.serverState.value == AirPlayService.ServerState.RUNNING &&
            viewModel.connectionCount.value == 0) {
            viewModel.stopServer()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SESSION_SUMMONED, sessionSummoned)
        outState.putBoolean(STATE_RETURN_PENDING, returnPending)
        outState.putLong(STATE_RETURN_DEADLINE, returnDeadline)
    }

    override fun onDestroy() {
        service?.let {
            if (it.logCallback === logCallback) it.logCallback = null
            if (it.pinCallback === pinCallback) it.pinCallback = null
        }
        unbindService(connection)
        retreatHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        // hold off so a quick stop/play does not flash the screen below
        private const val SESSION_END_RETURN_GRACE_MS = 2_000L
        private const val STATE_SESSION_SUMMONED = "session_summoned"
        private const val STATE_RETURN_PENDING = "return_pending"
        private const val STATE_RETURN_DEADLINE = "return_deadline"
        private const val SUMMON_TAG = "AirPlaySummon"
        @Volatile var isOnScreen = false
    }
}
