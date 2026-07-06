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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var service: AirPlayService? = null
    val isInPip = mutableStateOf(false)
    private val logCallback: (String) -> Unit = { viewModel.addLog(it) }
    private val pinCallback: (String?) -> Unit = { viewModel.showPin(it) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            service = svc
            svc.logCallback = logCallback
            svc.pinCallback = pinCallback
            viewModel.bindService(svc)
            if (viewModel.autoStart.value && svc.serverState.value == AirPlayService.ServerState.STOPPED) {
                viewModel.startServer()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
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

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val aspect = viewModel.videoAspect.value
        val rational = Rational(
            (aspect * 1000).toInt().coerceIn(1, 2390),
            1000.coerceIn(1, 2390)
        )
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(rational)
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (viewModel.serverState.value == AirPlayService.ServerState.RUNNING &&
            viewModel.connectionCount.value > 0) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        isInPip.value = inPip
    }

    override fun onDestroy() {
        service?.let {
            if (it.logCallback === logCallback) it.logCallback = null
            if (it.pinCallback === pinCallback) it.pinCallback = null
        }
        unbindService(connection)
        super.onDestroy()
    }
}
