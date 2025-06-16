package com.yervant.huntmem.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yervant.huntmem.R
import com.yervant.huntmem.ui.menu.ProcessViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OverlayUiState(
    val isMenuVisible: Boolean = false,
    val iconPosition: IntOffset = IntOffset(0, 100),
    val selectedTab: Int = 0,
    val dialogState: DialogState = DialogState.Hidden
)

sealed interface DialogState {
    object Hidden : DialogState
    data class Info(val title: String, val message: String, val onConfirm: () -> Unit, val onDismiss: () -> Unit ) : DialogState
    data class Input(val title: String, val defaultValue: String, val onConfirm: (String) -> Unit, val onDismiss: () -> Unit ) : DialogState
}

class OverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner, DialogCallback {
    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var viewModel: ProcessViewModel

    override val viewModelStore = ViewModelStore()
    private lateinit var savedStateRegistryController: SavedStateRegistryController
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val _uiState = MutableStateFlow(OverlayUiState())
    private val uiState = _uiState.asStateFlow()

    private val windowManagerParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START

        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        x = uiState.value.iconPosition.x
        y = uiState.value.iconPosition.y
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performRestore(null)
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        viewModel = ProcessViewModel(application.packageManager)

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)

            setContent {
                val currentUiState by uiState.collectAsState()
                OverlayScreen(
                    uiState = currentUiState,
                    viewModel = viewModel,
                    context = context,
                    dialogCallback = this@OverlayService,
                    onUpdateIconPosition = { newPosition ->
                        _uiState.update { it.copy(iconPosition = newPosition) }
                    },
                    onToggleMenu = {
                        _uiState.update { it.copy(isMenuVisible = !it.isMenuVisible) }
                    },
                    onTabSelected = { tabIndex ->
                        _uiState.update { it.copy(selectedTab = tabIndex) }
                    }
                )
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
                    windowManager.addView(composeView, windowManagerParams)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 100)

        observeUiStateForWindowChanges()
        setupNotification()
    }

    private fun observeUiStateForWindowChanges() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                uiState.collect { state ->
                    updateWindowParameters(state.isMenuVisible, state.iconPosition)
                }
            }
        }
    }

    private fun updateWindowParameters(isMenuVisible: Boolean, iconPosition: IntOffset) {

        val params = windowManagerParams
        if (isMenuVisible) {

            params.x = 0
            params.y = 0
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT

            params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {

            params.x = iconPosition.x
            params.y = iconPosition.y
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT

            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        if (composeView.isAttachedToWindow) {
            windowManager.updateViewLayout(composeView, params)
        }
    }

    private fun setupNotification() {
        val channel = NotificationChannel("overlay_channel", "Overlay Service", NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channel.id)
            .setContentTitle("HuntMem")
            .setContentText("Overlay is executing.")
            .setSmallIcon(R.drawable.overlay_icon)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun hideDialog() {
        _uiState.update { it.copy(dialogState = DialogState.Hidden) }
    }

    override fun showInfoDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
        val confirmAndHide = {
            onConfirm()
            hideDialog()
        }
        _uiState.update {
            it.copy(
                dialogState = DialogState.Info(
                    title = title,
                    message = message,
                    onConfirm = confirmAndHide,
                    onDismiss = { onDismiss(); hideDialog() }
                )
            )
        }
    }

    override fun showInputDialog(title: String, defaultValue: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
        val confirmAndHide = { result: String ->
            onConfirm(result)
            hideDialog()
        }
        _uiState.update {
            it.copy(
                dialogState = DialogState.Input(
                    title = title,
                    defaultValue = defaultValue,
                    onConfirm = confirmAndHide,
                    onDismiss = { onDismiss(); hideDialog() }
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (composeView.isAttachedToWindow) {
            windowManager.removeView(composeView)
        }
    }
}