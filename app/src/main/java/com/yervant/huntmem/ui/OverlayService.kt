package com.yervant.huntmem.ui

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class OverlayUiState(
    val isMenuVisible: Boolean = false,
    val iconPosition: IntOffset = IntOffset(0, 100),
    val selectedTab: Int = 0,
    val dialogState: DialogState = DialogState.Hidden,
    val activeMenu: MenuType = MenuType.HUNTING
)

sealed interface DialogState {
    object Hidden : DialogState
    data class Info(val title: String, val message: String, val onConfirm: () -> Unit, val onDismiss: () -> Unit ) : DialogState
    data class Input(val title: String, val defaultValue: String, val onConfirm: (String) -> Unit, val onDismiss: () -> Unit ) : DialogState
}

class OverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner, DialogCallback {
    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView

    private var lastIconX: Int = 0
    private var lastIconY: Int = 100
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var iconSize: Int = 0

    private val viewModels = mutableMapOf<MenuType, ProcessViewModel>()

    private fun getViewModelForMenu(menuType: MenuType): ProcessViewModel {
        return viewModels.getOrPut(menuType) {
            ProcessViewModel(application.packageManager)
        }
    }

    override val viewModelStore = ViewModelStore()
    private lateinit var savedStateRegistryController: SavedStateRegistryController
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val _uiState = MutableStateFlow(OverlayUiState(isMenuVisible = false))
    val uiState = _uiState.asStateFlow()

    private val windowManagerParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = lastIconX
        y = lastIconY
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        intent?.getStringExtra("MENU_TYPE_EXTRA")?.let { menuTypeName ->
            val menuType = MenuType.valueOf(menuTypeName)

            _uiState.update { currentState ->
                currentState.copy(
                    activeMenu = menuType,
                    selectedTab = 0
                )
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performRestore(null)
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        updateScreenDimensions()

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)

            setContent {
                val currentUiState by uiState.collectAsState()

                val currentViewModel = getViewModelForMenu(currentUiState.activeMenu)

                OverlayScreen(
                    uiState = currentUiState,
                    viewModel = currentViewModel,
                    context = context,
                    dialogCallback = this@OverlayService,
                    onUpdateIconPosition = { dragAmount ->
                        moveIcon(dragAmount)
                    },
                    onToggleMenu = {
                        toggleMenu()
                    },
                    onTabSelected = { tabIndex ->
                        _uiState.update { it.copy(selectedTab = tabIndex) }
                    },
                    onSwitchMenu = { newMenuType ->
                        switchMenu(newMenuType)
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

    fun switchMenu(newMenu: MenuType) {
        _uiState.update {
            it.copy(activeMenu = newMenu, selectedTab = 0)
        }
    }

    private fun moveIcon(dragAmount: IntOffset) {
        val newX = windowManagerParams.x + dragAmount.x
        val newY = windowManagerParams.y + dragAmount.y

        windowManagerParams.x = newX.coerceIn(0, screenWidth - iconSize)
        windowManagerParams.y = newY.coerceIn(0, screenHeight - iconSize)

        lastIconX = windowManagerParams.x
        lastIconY = windowManagerParams.y

        if (composeView.isAttachedToWindow) {
            windowManager.updateViewLayout(composeView, windowManagerParams)
        }
    }

    private fun toggleMenu() {
        val isCurrentlyVisible = uiState.value.isMenuVisible
        _uiState.update { it.copy(isMenuVisible = !isCurrentlyVisible) }
    }

    private fun updateScreenDimensions() {
        iconSize = (64 * resources.displayMetrics.density).roundToInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }
    }

    private fun observeUiStateForWindowChanges() {
        lifecycleScope.launch {
            uiState.map { it.isMenuVisible }.distinctUntilChanged().collect { isVisible ->
                if (isVisible) {
                    updateWindowToMenuState()
                } else {
                    updateWindowToIconState()
                }
            }
        }
    }

    private fun updateWindowToMenuState() {
        lastIconX = windowManagerParams.x
        lastIconY = windowManagerParams.y

        windowManagerParams.apply {
            x = 0
            y = 0
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT

            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (composeView.isAttachedToWindow) {
            windowManager.updateViewLayout(composeView, windowManagerParams)
        }
    }

    private fun updateWindowToIconState() {
        windowManagerParams.apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT

            x = lastIconX
            y = lastIconY

            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        if (composeView.isAttachedToWindow) {
            windowManager.updateViewLayout(composeView, windowManagerParams)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val wasMenuVisible = uiState.value.isMenuVisible

        if (composeView.isAttachedToWindow) {
            windowManager.removeView(composeView)
        }
        updateScreenDimensions()

        val clampedX = lastIconX.coerceIn(0, screenWidth - iconSize)
        val clampedY = lastIconY.coerceIn(0, screenHeight - iconSize)
        lastIconX = clampedX
        lastIconY = clampedY

        if (wasMenuVisible) {
            windowManagerParams.apply {
                x = 0
                y = 0
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } else {
            windowManagerParams.apply {
                x = lastIconX
                y = lastIconY
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        windowManager.addView(composeView, windowManagerParams)
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