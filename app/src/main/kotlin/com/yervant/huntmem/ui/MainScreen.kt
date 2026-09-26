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
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.topjohnwu.superuser.Shell
import com.yervant.huntmem.BuildConfig
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.HMemServiceConnection
import com.yervant.huntmem.backend.NativeBridge.getHmkpmVersionInfo
import com.yervant.huntmem.backend.NativeBridge.isHmkpmAvailable
import com.yervant.huntmem.ui.compat.OemCompatibilityHelper
import com.yervant.huntmem.ui.theme.ComponentStyles
import com.yervant.huntmem.ui.theme.ErrorRose
import com.yervant.huntmem.ui.theme.SuccessEmerald
import com.yervant.huntmem.ui.theme.TertiaryAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToCredits: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showLanguageMenu by remember { mutableStateOf(false) }

    var isCheckingHmkpm by remember { mutableStateOf(true) }
    var isHmkpmReady by remember { mutableStateOf<Boolean?>(null) }
    var hmkpmVersion by remember { mutableStateOf<com.yervant.huntmem.backend.NativeBridge.HmkpmVersion?>(null) }
    var hmkpmRefreshTrigger by remember { mutableIntStateOf(0) }
    var showHmkpmInfoDialog by remember { mutableStateOf(false) }
    var showOemInfoDialog by remember { mutableStateOf(false) }
    var isRootGranted by remember { mutableStateOf<Boolean?>(null) }
    var hasOverlayPermission by remember { mutableStateOf(OemCompatibilityHelper.hasOverlayPermission(ctx)) }
    var hasPopupPermission by remember { mutableStateOf(OemCompatibilityHelper.hasPopupPermission(ctx)) }
    var hasAutostartPermission by remember { mutableStateOf(OemCompatibilityHelper.hasAutostartPermission(ctx)) }
    var isIgnoringBattery by remember { mutableStateOf(OemCompatibilityHelper.isIgnoringBatteryOptimizations(ctx)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = OemCompatibilityHelper.hasOverlayPermission(ctx)
                hasPopupPermission = OemCompatibilityHelper.hasPopupPermission(ctx)
                hasAutostartPermission = OemCompatibilityHelper.hasAutostartPermission(ctx)
                isIgnoringBattery = OemCompatibilityHelper.isIgnoringBatteryOptimizations(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val openOverlaySettings: () -> Unit = {
        OemCompatibilityHelper.openOverlayPermissionSettings(ctx)
    }

    val isServiceRunning by OverlayService.isServiceRunning.collectAsState()
    val attachedPid by AttachedProcessRepository.attachedProcessPid.collectAsState()
    val isRootServiceConnected by HMemServiceConnection.isConnected.collectAsState()

    LaunchedEffect(Unit) {
        val rootResult = withContext(Dispatchers.IO) {
            try {
                Shell.isAppGrantedRoot() ?: Shell.getShell().isRoot
            } catch (_: Throwable) {
                false
            }
        }
        isRootGranted = rootResult
    }

    LaunchedEffect(isRootGranted, isRootServiceConnected, hmkpmRefreshTrigger) {
        when (isRootGranted) {
            true -> {
                if (!isRootServiceConnected) {
                    isCheckingHmkpm = true
                    HMemServiceConnection.bind(ctx, force = hmkpmRefreshTrigger > 0)
                    val connected = withContext(Dispatchers.IO) {
                        var waitedMs = 0L
                        while (!HMemServiceConnection.isConnected.value && waitedMs < 3500L) {
                            delay(100L.milliseconds)
                            waitedMs += 100L
                        }
                        HMemServiceConnection.isConnected.value
                    }
                    if (!connected) {
                        isCheckingHmkpm = false
                        isHmkpmReady = false
                        return@LaunchedEffect
                    }
                }

                isCheckingHmkpm = true
                val ver = withContext(Dispatchers.IO) {
                    try {
                        getHmkpmVersionInfo()
                    } catch (_: Throwable) {
                        null
                    }
                }
                hmkpmVersion = ver
                isHmkpmReady = ver?.available == true
                isCheckingHmkpm = false
            }
            false -> {
                isCheckingHmkpm = false
                isHmkpmReady = false
                hmkpmVersion = null
            }
            null -> {
                isCheckingHmkpm = true
                isHmkpmReady = null
                hmkpmVersion = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} • ARM64",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                fontSize = 10.sp
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToCredits, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(id = R.string.main_screen_credits_button_cd),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Box {
                        IconButton(onClick = { showLanguageMenu = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = stringResource(id = R.string.main_screen_change_language_button),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.main_screen_system_default_dropdown)) },
                                onClick = {
                                    LocaleManager.setLocale("")
                                    showLanguageMenu = false
                                    activity?.recreate()
                                }
                            )

                            LocaleManager.getSupportedLanguages().forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(language.nativeName) },
                                    onClick = {
                                        LocaleManager.setLocale(language.code)
                                        showLanguageMenu = false
                                        activity?.recreate()
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top Section: 2x2 Status Grid
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                Text(
                    text = stringResource(id = R.string.main_screen_overview_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    letterSpacing = 1.sp
                )

                // Row 1 of 2x2 Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactStatusCard(
                        icon = Icons.Default.Security,
                        title = stringResource(id = R.string.main_screen_status_root_access),
                        statusText = when (isRootGranted) {
                            true -> stringResource(id = R.string.main_screen_status_root_granted)
                            false -> stringResource(id = R.string.main_screen_status_root_missing)
                            null -> stringResource(id = R.string.main_screen_status_root_checking)
                        },
                        isOk = isRootGranted == true,
                        isLoading = isRootGranted == null,
                        modifier = Modifier.weight(1f)
                    )

                    val isHmkpmLockless = isHmkpmReady == true && (hmkpmVersion?.isLockless == true || hmkpmVersion?.supportsWrite == false)
                    CompactStatusCard(
                        icon = Icons.Default.Memory,
                        title = stringResource(id = R.string.main_screen_status_kernelpatch),
                        statusText = when {
                            isCheckingHmkpm -> stringResource(id = R.string.main_screen_hmkpm_checking)
                            isHmkpmReady == true -> {
                                val verStr = hmkpmVersion?.versionStr
                                when {
                                    isHmkpmLockless && !verStr.isNullOrBlank() -> "v$verStr (${stringResource(id = R.string.main_screen_hmkpm_lockless_tag)})"
                                    isHmkpmLockless -> stringResource(id = R.string.main_screen_hmkpm_lockless)
                                    !verStr.isNullOrBlank() -> "v$verStr"
                                    else -> stringResource(id = R.string.main_screen_hmkpm_available)
                                }
                            }
                            else -> stringResource(id = R.string.main_screen_hmkpm_not_available)
                        },
                        isOk = isHmkpmReady == true,
                        isWarning = isHmkpmLockless,
                        isLoading = isCheckingHmkpm,
                        onClick = if (isHmkpmReady == true) {
                            { showHmkpmInfoDialog = true }
                        } else null,
                        onRefresh = if (isRootGranted == true) {
                            { if (!isCheckingHmkpm) hmkpmRefreshTrigger++ }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 2 of 2x2 Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isOverlayOk = hasOverlayPermission && (!OemCompatibilityHelper.isXiaomi() || hasPopupPermission)
                    val isOverlayWarning = hasOverlayPermission && OemCompatibilityHelper.isXiaomi() && !hasPopupPermission

                    CompactStatusCard(
                        icon = Icons.Default.Tune,
                        title = stringResource(id = R.string.main_screen_status_overlay_engine),
                        statusText = when {
                            !hasOverlayPermission -> stringResource(id = R.string.main_screen_status_overlay_permission_missing)
                            isOverlayWarning -> stringResource(id = R.string.main_screen_status_popup_permission_missing)
                            isServiceRunning -> stringResource(id = R.string.main_screen_status_overlay_active)
                            else -> stringResource(id = R.string.main_screen_status_overlay_permission_granted)
                        },
                        isOk = isOverlayOk,
                        isWarning = isOverlayWarning,
                        isLoading = false,
                        onClick = openOverlaySettings,
                        modifier = Modifier.weight(1f)
                    )

                    CompactStatusCard(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(id = R.string.main_screen_status_target_process),
                        statusText = if (attachedPid != null) {
                            stringResource(id = R.string.main_screen_status_target_pid, attachedPid!!)
                        } else {
                            stringResource(id = R.string.main_screen_status_target_none)
                        },
                        isOk = attachedPid != null,
                        isLoading = false,
                        isMonospace = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Lockless Mode Informative Banner
                val isHmkpmLockless = isHmkpmReady == true && (hmkpmVersion?.isLockless == true || hmkpmVersion?.supportsWrite == false)
                if (isHmkpmLockless) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showHmkpmInfoDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = TertiaryAmber.copy(alpha = 0.12f)
                        ),
                        border = BorderStroke(1.dp, TertiaryAmber.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = TertiaryAmber.copy(alpha = 0.20f),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = TertiaryAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(id = R.string.lockless_banner_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TertiaryAmber
                                )
                                Text(
                                    text = stringResource(id = R.string.lockless_banner_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { showHmkpmInfoDialog = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(id = R.string.lockless_dialog_learn_more),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TertiaryAmber
                                )
                            }
                        }
                    }
                }
            }

            // OEM System Compatibility & Background Optimization Banner
            if (OemCompatibilityHelper.isCustomOem() || !isIgnoringBattery) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.40f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.20f),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(
                                        id = R.string.oem_compat_banner_title,
                                        OemCompatibilityHelper.currentRomType.displayName
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = if (OemCompatibilityHelper.isXiaomi()) {
                                        stringResource(id = R.string.oem_compat_hyperos_desc)
                                    } else {
                                        stringResource(id = R.string.oem_compat_battery_desc)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { showOemInfoDialog = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(id = R.string.lockless_dialog_learn_more),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        // Granular OEM Permission Verification Rows
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (OemCompatibilityHelper.isXiaomi()) {
                                    OemPermissionRow(
                                        name = stringResource(id = R.string.oem_compat_item_popup),
                                        isGranted = hasPopupPermission,
                                        actionText = stringResource(id = R.string.oem_compat_btn_overlay),
                                        onAction = { OemCompatibilityHelper.openOverlayPermissionSettings(ctx) }
                                    )
                                }

                                if (OemCompatibilityHelper.isCustomOem()) {
                                    OemPermissionRow(
                                        name = stringResource(id = R.string.oem_compat_item_autostart),
                                        isGranted = if (OemCompatibilityHelper.isXiaomi()) hasAutostartPermission else isIgnoringBattery,
                                        actionText = stringResource(id = R.string.oem_compat_btn_autostart),
                                        onAction = { OemCompatibilityHelper.openAutostartSettings(ctx) }
                                    )
                                }

                                OemPermissionRow(
                                    name = stringResource(id = R.string.oem_compat_item_battery),
                                    isGranted = isIgnoringBattery,
                                    actionText = stringResource(id = R.string.oem_compat_btn_battery),
                                    onAction = { OemCompatibilityHelper.requestIgnoreBatteryOptimizations(ctx) }
                                )
                            }
                        }
                    }
                }
            }

            // Middle Section: Hero Start / Stop Button
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isServiceRunning) {
                    Button(
                        onClick = {
                            if (isRootGranted != true) {
                                val rootMissingMsg = ctx.getString(R.string.main_activity_error_root_access_missing)
                                Toast.makeText(ctx, rootMissingMsg, Toast.LENGTH_SHORT).show()
                            } else if (!hasOverlayPermission) {
                                val overlayDeniedMsg = ctx.getString(R.string.main_activity_permission_overlay_denied)
                                Toast.makeText(ctx, overlayDeniedMsg, Toast.LENGTH_SHORT).show()
                                openOverlaySettings()
                            } else if (OemCompatibilityHelper.isXiaomi() && !hasPopupPermission) {
                                val popupDeniedMsg = ctx.getString(R.string.main_activity_permission_popup_denied)
                                Toast.makeText(ctx, popupDeniedMsg, Toast.LENGTH_LONG).show()
                                openOverlaySettings()
                            } else {
                                val serviceIntent = Intent(ctx, OverlayService::class.java)
                                ctx.startForegroundService(serviceIntent)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentStyles.ActionButton.height),
                        shape = ComponentStyles.ActionButton.shape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = ComponentStyles.ActionButton.elevation)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(ComponentStyles.ActionButton.iconSize)
                            )
                            Text(
                                text = stringResource(id = R.string.main_screen_start_hunting_button),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            val stopIntent = Intent(ctx, OverlayService::class.java)
                            ctx.stopService(stopIntent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentStyles.ActionButton.height),
                        shape = ComponentStyles.ActionButton.shape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = ComponentStyles.ActionButton.elevation)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(ComponentStyles.ActionButton.iconSize)
                            )
                            Text(
                                text = stringResource(id = R.string.main_screen_stop_hunting_button),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Bottom Section: Community & Tech Architecture Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val officialChannelText = stringResource(R.string.main_screen_official_channel)
                    val websiteText = stringResource(R.string.main_screen_website)
                    val sourceCodeText = stringResource(R.string.main_screen_source_code)

                    val linkStyles = TextLinkStyles(
                        style = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline
                        )
                    )

                    val annotatedString = buildAnnotatedString {
                        withLink(
                            link = LinkAnnotation.Url(
                                url = "https://t.me/HuntMemory",
                                styles = linkStyles
                            )
                        ) {
                            append(officialChannelText)
                        }
                        append("  •  ")
                        withLink(
                            link = LinkAnnotation.Url(
                                url = "https://yervant7.github.io/HuntMemory/",
                                styles = linkStyles
                            )
                        ) {
                            append(websiteText)
                        }
                        append("  •  ")
                        withLink(
                            link = LinkAnnotation.Url(
                                url = "https://github.com/Yervant7/HuntMemory",
                                styles = linkStyles
                            )
                        ) {
                            append(sourceCodeText)
                        }
                    }

                    SelectionContainer {
                        Text(
                            annotatedString,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { onNavigateToCredits() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = stringResource(R.string.main_screen_credits_link),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                textDecoration = TextDecoration.Underline,
                                fontSize = 11.sp
                            )
                        )
                    }

                    Text(
                        text = "Native Engine • Kernel Memory Bridge • ARM64-v8a",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        }
    }

    if (showHmkpmInfoDialog && hmkpmVersion != null) {
        HmkpmInfoDialog(
            versionInfo = hmkpmVersion!!,
            onDismiss = { showHmkpmInfoDialog = false }
        )
    }

    if (showOemInfoDialog) {
        OemCompatibilityDialog(
            onDismiss = { showOemInfoDialog = false },
            onOpenOverlay = { OemCompatibilityHelper.openOverlayPermissionSettings(ctx) },
            onOpenAutostart = { OemCompatibilityHelper.openAutostartSettings(ctx) },
            onOpenBattery = { OemCompatibilityHelper.requestIgnoreBatteryOptimizations(ctx) }
        )
    }
}

@Composable
private fun CompactStatusCard(
    icon: ImageVector,
    title: String,
    statusText: String,
    isOk: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false,
    isMonospace: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val borderColor = when {
        isLoading -> MaterialTheme.colorScheme.outlineVariant
        isWarning -> TertiaryAmber.copy(alpha = 0.65f)
        isOk -> SuccessEmerald.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }

    val cardModifier = if (onClick != null) {
        modifier
            .height(ComponentStyles.StatusCard.height)
            .clip(ComponentStyles.StatusCard.shape)
            .clickable(onClick = onClick)
    } else {
        modifier.height(ComponentStyles.StatusCard.height)
    }

    Card(
        modifier = cardModifier,
        shape = ComponentStyles.StatusCard.shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(ComponentStyles.StatusCard.borderWidth, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = ComponentStyles.StatusCard.horizontalPadding,
                    vertical = ComponentStyles.StatusCard.verticalPadding
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val iconContainerColor = when {
                    isLoading -> MaterialTheme.colorScheme.surfaceVariant
                    isWarning -> TertiaryAmber.copy(alpha = 0.15f)
                    isOk -> SuccessEmerald.copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Surface(
                    shape = ComponentStyles.StatusCard.iconContainerShape,
                    color = iconContainerColor,
                    modifier = Modifier.size(ComponentStyles.StatusCard.iconContainerSize)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            val iconColor = when {
                                isWarning -> TertiaryAmber
                                isOk -> SuccessEmerald
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(ComponentStyles.StatusCard.iconSize)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val statusColor = when {
                        isWarning -> TertiaryAmber
                        isOk -> SuccessEmerald
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = statusText,
                        style = if (isMonospace) {
                            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        } else {
                            MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        },
                        color = statusColor,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (onRefresh != null) {
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(id = R.string.main_screen_refresh_cd),
                        tint = if (isLoading) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HmkpmInfoDialog(
    versionInfo: com.yervant.huntmem.backend.NativeBridge.HmkpmVersion,
    onDismiss: () -> Unit
) {
    val isLockless = versionInfo.isLockless || !versionInfo.supportsWrite
    val modeColor = if (isLockless) TertiaryAmber else SuccessEmerald
    val modeBg = if (isLockless) TertiaryAmber.copy(alpha = 0.12f) else SuccessEmerald.copy(alpha = 0.12f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = modeBg,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = modeColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = stringResource(id = R.string.hmkpm_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "v${versionInfo.versionStr.ifBlank { "2.8.0" }} • ARM64 MMU Engine",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Operating Mode Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = modeBg),
                    border = BorderStroke(1.dp, modeColor.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isLockless) Icons.Default.Shield else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = modeColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isLockless) {
                                    stringResource(id = R.string.hmkpm_dialog_mode_lockless)
                                } else {
                                    stringResource(id = R.string.hmkpm_dialog_mode_full)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = modeColor
                            )
                        }
                        Text(
                            text = if (isLockless) {
                                stringResource(id = R.string.hmkpm_dialog_mode_lockless_desc)
                            } else {
                                stringResource(id = R.string.hmkpm_dialog_mode_full_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // If Lockless, explain WHY writes are disabled
                if (isLockless) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.hmkpm_dialog_why_lockless_title),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(id = R.string.hmkpm_dialog_why_lockless_desc),
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Features breakdown list
                Text(
                    text = stringResource(id = R.string.hmkpm_dialog_features_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FeatureRow(
                            label = stringResource(id = R.string.hmkpm_dialog_feature_read),
                            enabled = versionInfo.supportsRead
                        )
                        FeatureRow(
                            label = stringResource(id = R.string.hmkpm_dialog_feature_v2p),
                            enabled = versionInfo.supportsV2pBatch
                        )
                        FeatureRow(
                            label = stringResource(id = R.string.hmkpm_dialog_feature_kernel_scan),
                            enabled = versionInfo.supportsKernelScan
                        )
                        FeatureRow(
                            label = stringResource(id = R.string.hmkpm_dialog_feature_write),
                            enabled = versionInfo.supportsWrite,
                            disabledReason = if (isLockless) stringResource(id = R.string.main_screen_hmkpm_lockless_tag) else null
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(id = R.string.hmkpm_dialog_mmap_offset),
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (versionInfo.mmapLockOffset >= 0) {
                                    "0x${versionInfo.mmapLockOffset.toString(16).uppercase()}"
                                } else {
                                    stringResource(id = R.string.hmkpm_dialog_status_unresolved)
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                fontSize = 11.sp,
                                color = if (versionInfo.mmapLockOffset >= 0) SuccessEmerald else TertiaryAmber
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(id = R.string.hmkpm_dialog_close),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun FeatureRow(
    label: String,
    enabled: Boolean,
    disabledReason: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (enabled) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (enabled) SuccessEmerald else ErrorRose,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = when {
                enabled -> stringResource(id = R.string.hmkpm_dialog_status_enabled)
                !disabledReason.isNullOrBlank() -> disabledReason
                else -> stringResource(id = R.string.hmkpm_dialog_status_disabled)
            },
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            fontSize = 10.sp,
            color = if (enabled) SuccessEmerald else TertiaryAmber
        )
    }
}

@Composable
fun OemCompatibilityDialog(
    onDismiss: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenAutostart: () -> Unit,
    onOpenBattery: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = stringResource(id = R.string.oem_compat_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${OemCompatibilityHelper.currentRomType.displayName} • Stability Guide",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.oem_compat_dialog_desc_1),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Item 1: Overlay
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(id = R.string.oem_compat_dialog_item_overlay_title),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(id = R.string.oem_compat_dialog_item_overlay_desc),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Item 2: Autostart
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(id = R.string.oem_compat_dialog_item_autostart_title),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(id = R.string.oem_compat_dialog_item_autostart_desc),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Item 3: Battery
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(id = R.string.oem_compat_dialog_item_battery_title),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(id = R.string.oem_compat_dialog_item_battery_desc),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(id = R.string.oem_compat_dialog_dismiss),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun OemPermissionRow(
    name: String,
    isGranted: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) SuccessEmerald else TertiaryAmber,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (isGranted) {
                    stringResource(id = R.string.oem_compat_status_granted)
                } else {
                    stringResource(id = R.string.oem_compat_status_missing)
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                fontSize = 10.sp,
                color = if (isGranted) SuccessEmerald else TertiaryAmber
            )
        }

        Button(
            onClick = onAction,
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                contentColor = if (isGranted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                text = actionText,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


