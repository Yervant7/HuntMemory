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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
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
import com.yervant.huntmem.ui.overlay.tabs.ProcessViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OverlayUiState(
    val isMenuVisible: Boolean = false,
    val iconPosition: IntOffset = IntOffset(0, 100),
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

    private lateinit var iconParams: WindowManager.LayoutParams
    private lateinit var menuParams: WindowManager.LayoutParams

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

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

        windowManager.addView(menuView, menuParams)
        windowManager.addView(iconView, iconParams)

        observeStateAndApplyChanges()

        setupNotification()
    }

    private fun setupViews() {
        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        iconParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = _uiState.value.iconPosition.x
            y = _uiState.value.iconPosition.y
        }

        menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        iconView = createComposeView {
            FloatingIcon(
                onToggleMenu = ::toggleMenu,
                onUpdatePosition = ::updateIconPositionBy,
                onDragEnd = {}
            )
        }

        menuView = createComposeView {
            val currentUiState by uiState.collectAsState()
            MenuOverlayContent(
                uiState = currentUiState,
                viewModel = processViewModel,
                context = applicationContext,
                dialogCallback = this@OverlayService,
                onToggleMenu = ::toggleMenu,
                onTabSelected = { tabIndex -> _uiState.update { it.copy(selectedTab = tabIndex) } },
            )
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
    }

    private fun updateIconPositionBy(dragAmount: IntOffset) {
        val maxX = (screenWidth - iconView.width).coerceAtLeast(0)
        val maxY = (screenHeight - iconView.height).coerceAtLeast(0)

        val newX = (iconParams.x + dragAmount.x).coerceIn(0, maxX)
        val newY = (iconParams.y + dragAmount.y).coerceIn(0, maxY)

        iconParams.x = newX
        iconParams.y = newY
        windowManager.updateViewLayout(iconView, iconParams)

        _uiState.update { it.copy(iconPosition = IntOffset(newX, newY)) }
    }

    private fun toggleMenu() {
        _uiState.update { it.copy(isMenuVisible = !it.isMenuVisible) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenDimensions()
        updateIconPositionBy(IntOffset.Zero)
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
        if (iconView.isAttachedToWindow) windowManager.removeView(iconView)
        if (menuView.isAttachedToWindow) windowManager.removeView(menuView)
    }
}
