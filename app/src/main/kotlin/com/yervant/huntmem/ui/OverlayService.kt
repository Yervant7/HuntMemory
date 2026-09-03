/*
 * HuntMemory - Process Memory Editor & Scanner for Android
 * Copyright (C) 2026 Yervant7
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import com.yervant.huntmem.ui.keyboard.KeyboardType
import com.yervant.huntmem.ui.overlay.LuaCanvasOverlay
import com.yervant.huntmem.ui.overlay.tabs.LuaUiBridge
import com.yervant.huntmem.ui.overlay.tabs.ProcessViewModel
import com.yervant.huntmem.ui.theme.HuntMemTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class OverlayUiState(
    val isMenuVisible: Boolean = false,
    val selectedTab: Int = 0,
    val dialogState: DialogState = DialogState.Hidden,
)

sealed interface DialogState {
    object Hidden : DialogState
    data class Info(
        val title: String,
        val message: String,
        val onConfirm: () -> Unit,
        val onDismiss: () -> Unit
    ) : DialogState

    data class Input(
        val title: String,
        val defaultValue: String,
        val keyboardType: KeyboardType = KeyboardType.QWERTY,
        val onConfirm: (String) -> Unit,
        val onDismiss: () -> Unit
    ) : DialogState
}

@SuppressLint("ClickableViewAccessibility")
class OverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner, DialogCallback {

    companion object {
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()
    }

    private lateinit var windowManager: WindowManager

    private lateinit var iconView: ComposeView
    private lateinit var menuView: ComposeView
    private lateinit var canvasView: ComposeView

    private lateinit var iconParams: WindowManager.LayoutParams
    private lateinit var menuParams: WindowManager.LayoutParams
    private lateinit var canvasParams: WindowManager.LayoutParams

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var cachedIconSize: Int = 0

    override val viewModelStore = ViewModelStore()
    private lateinit var savedStateRegistryController: SavedStateRegistryController
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val processViewModel: ProcessViewModel by lazy {
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ProcessViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ProcessViewModel(packageManager) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        )[ProcessViewModel::class.java]
    }

    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState = _uiState.asStateFlow()

    override fun onCreate() {
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performRestore(null)
        super.onCreate()

        _isServiceRunning.value = true

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenDimensions()

        setupViews()

        windowManager.addView(canvasView, canvasParams)
        windowManager.addView(menuView, menuParams)
        windowManager.addView(iconView, iconParams)

        observeStateAndApplyChanges()

        setupNotification()
    }

    private fun setupViews() {
        cachedIconSize = (54 * resources.displayMetrics.density).toInt()
        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        iconParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 100
        }

        menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        val canvasFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            canvasFlag,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        canvasView = createComposeView {
            LuaCanvasOverlay()
        }.apply {
            visibility = View.GONE
        }

        iconView = createComposeView {
            HuntMemTheme(darkTheme = true) {
                FloatingIcon()
            }
        }.apply {
            setupFloatingIconTouchListener(this)
        }

        menuView = createComposeView {
            val currentUiState by uiState.collectAsState()
            if (currentUiState.isMenuVisible) {
                MenuOverlayContent(
                    uiState = currentUiState,
                    viewModel = processViewModel,
                    context = applicationContext,
                    dialogCallback = this@OverlayService,
                    onToggleMenu = ::toggleMenu,
                    onTabSelected = { tabIndex -> _uiState.update { it.copy(selectedTab = tabIndex) } },
                )
            }
        }.apply {
            visibility = View.GONE
        }
    }

    private fun createComposeView(content: @Composable () -> Unit): ComposeView {
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent(content)
        }
    }

    private fun observeStateAndApplyChanges() {
        lifecycleScope.launch {
            uiState.map { it.isMenuVisible }.distinctUntilChanged().collect { isMenuVisible ->
                if (isMenuVisible) {
                    iconView.visibility = View.GONE
                    menuView.visibility = View.VISIBLE
                } else {
                    iconView.visibility = View.VISIBLE
                    menuView.visibility = View.GONE
                }
            }
        }
        lifecycleScope.launch {
            LuaUiBridge.isCanvasVisible.collect { isCanvasVisible ->
                canvasView.visibility = if (isCanvasVisible) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupFloatingIconTouchListener(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val touchSlopSquare = touchSlop * touchSlop

        var initialWindowX = 0
        var initialWindowY = 0
        var touchDownRawX = 0f
        var touchDownRawY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            // Prevent multi-touch gesture confusion or coordinate jumping
            if (event.pointerCount > 1 && !isDragging) {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialWindowX = iconParams.x
                    initialWindowY = iconParams.y
                    touchDownRawX = event.rawX
                    touchDownRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) {
                        // Ignore multi-touch move events to prevent teleporting
                        return@setOnTouchListener true
                    }

                    val deltaX = event.rawX - touchDownRawX
                    val deltaY = event.rawY - touchDownRawY

                    if (!isDragging && (deltaX * deltaX + deltaY * deltaY > touchSlopSquare)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        if (cachedIconSize == 0) {
                            cachedIconSize = (54 * resources.displayMetrics.density).toInt()
                        }
                        val iconW = if (iconView.width > 0) iconView.width else cachedIconSize
                        val iconH = if (iconView.height > 0) iconView.height else cachedIconSize

                        val maxX = (screenWidth - iconW).coerceAtLeast(0)
                        val maxY = (screenHeight - iconH).coerceAtLeast(0)

                        val targetX = (initialWindowX + deltaX.roundToInt()).coerceIn(0, maxX)
                        val targetY = (initialWindowY + deltaY.roundToInt()).coerceIn(0, maxY)

                        if (targetX != iconParams.x || targetY != iconParams.y) {
                            iconParams.x = targetX
                            iconParams.y = targetY
                            if (iconView.isAttachedToWindow) {
                                runCatching { windowManager.updateViewLayout(iconView, iconParams) }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        v.performClick()
                        toggleMenu()
                    }
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun clampIconPosition() {
        if (cachedIconSize == 0) {
            cachedIconSize = (54 * resources.displayMetrics.density).toInt()
        }
        val iconW = if (iconView.width > 0) iconView.width else cachedIconSize
        val iconH = if (iconView.height > 0) iconView.height else cachedIconSize

        val maxX = (screenWidth - iconW).coerceAtLeast(0)
        val maxY = (screenHeight - iconH).coerceAtLeast(0)

        val newX = iconParams.x.coerceIn(0, maxX)
        val newY = iconParams.y.coerceIn(0, maxY)

        if (newX != iconParams.x || newY != iconParams.y) {
            iconParams.x = newX
            iconParams.y = newY
            if (iconView.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(iconView, iconParams) }
            }
        }
    }

    private fun toggleMenu() {
        _uiState.update { it.copy(isMenuVisible = !it.isMenuVisible) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cachedIconSize = (54 * resources.displayMetrics.density).toInt()
        updateScreenDimensions()
        clampIconPosition()
    }

    private fun updateScreenDimensions() {
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
        LuaUiBridge.updateScreenDimensions(screenWidth, screenHeight)
    }

    private fun setupNotification() {
        val channelName = getString(R.string.overlay_service_notification_channel_name)
        val channel = NotificationChannel("overlay_channel", channelName, NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channel.id)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_service_notification_text))
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
        showInputDialog(title, defaultValue, KeyboardType.QWERTY, onConfirm, onDismiss)
    }

    override fun showInputDialog(
        title: String,
        defaultValue: String,
        keyboardType: KeyboardType,
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit
    ) {
        val confirmAndHide = { result: String ->
            onConfirm(result)
            hideDialog()
        }
        _uiState.update {
            it.copy(
                dialogState = DialogState.Input(
                    title = title,
                    defaultValue = defaultValue,
                    keyboardType = keyboardType,
                    onConfirm = confirmAndHide,
                    onDismiss = { onDismiss(); hideDialog() }
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        viewModelStore.clear()
        if (::iconView.isInitialized && iconView.isAttachedToWindow) runCatching { windowManager.removeView(iconView) }
        if (::menuView.isInitialized && menuView.isAttachedToWindow) runCatching { windowManager.removeView(menuView) }
        if (::canvasView.isInitialized && canvasView.isAttachedToWindow) runCatching { windowManager.removeView(canvasView) }
    }
}
