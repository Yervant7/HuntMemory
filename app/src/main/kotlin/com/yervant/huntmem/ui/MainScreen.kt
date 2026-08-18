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

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.yervant.huntmem.R
import com.yervant.huntmem.backend.AttachedProcessRepository
import com.yervant.huntmem.backend.NativeBridge.isHmkpmAvailable
import com.yervant.huntmem.ui.theme.SuccessEmerald
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    var hmkpmRefreshTrigger by remember { mutableIntStateOf(0) }
    var isRootGranted by remember { mutableStateOf<Boolean?>(null) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val openOverlaySettings = {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${ctx.packageName}".toUri()
            )
            ctx.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                ctx.startActivity(fallbackIntent)
            } catch (_: Exception) {}
        }
    }

    val isServiceRunning by OverlayService.isServiceRunning.collectAsState()
    val attachedPid by AttachedProcessRepository.attachedProcessPid.collectAsState()

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

    LaunchedEffect(isRootGranted, hmkpmRefreshTrigger) {
        when (isRootGranted) {
            true -> {
                isCheckingHmkpm = true
                if (hmkpmRefreshTrigger == 0) {
                    delay(3000L)
                }
                isHmkpmReady = withContext(Dispatchers.IO) {
                    try {
                        isHmkpmAvailable()
                    } catch (_: Throwable) {
                        false
                    }
                }
                isCheckingHmkpm = false
            }
            false -> {
                isCheckingHmkpm = false
                isHmkpmReady = false
            }
            null -> {
                isCheckingHmkpm = true
                isHmkpmReady = null
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
                                text = "v3.0 • ARM64",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
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

                    CompactStatusCard(
                        icon = Icons.Default.Memory,
                        title = stringResource(id = R.string.main_screen_status_kernelpatch),
                        statusText = when {
                            isCheckingHmkpm -> stringResource(id = R.string.main_screen_hmkpm_checking)
                            isHmkpmReady == true -> stringResource(id = R.string.main_screen_hmkpm_available)
                            else -> stringResource(id = R.string.main_screen_hmkpm_not_available)
                        },
                        isOk = isHmkpmReady == true,
                        isLoading = isCheckingHmkpm,
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
                    CompactStatusCard(
                        icon = Icons.Default.Tune,
                        title = stringResource(id = R.string.main_screen_status_overlay_engine),
                        statusText = when {
                            !hasOverlayPermission -> stringResource(id = R.string.main_screen_status_overlay_permission_missing)
                            isServiceRunning -> stringResource(id = R.string.main_screen_status_overlay_active)
                            else -> stringResource(id = R.string.main_screen_status_overlay_permission_granted)
                        },
                        isOk = hasOverlayPermission,
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
                            } else {
                                val serviceIntent = Intent(ctx, OverlayService::class.java)
                                ctx.startForegroundService(serviceIntent)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
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
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
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

@Composable
private fun CompactStatusCard(
    icon: ImageVector,
    title: String,
    statusText: String,
    isOk: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val borderColor = when {
        isLoading -> MaterialTheme.colorScheme.outlineVariant
        isOk -> SuccessEmerald.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }

    val cardModifier = if (onClick != null) {
        modifier
            .height(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    } else {
        modifier.height(76.dp)
    }

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isOk) SuccessEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isOk) SuccessEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
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
                    Text(
                        text = statusText,
                        style = if (isMonospace) {
                            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        } else {
                            MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        },
                        color = if (isOk) SuccessEmerald else MaterialTheme.colorScheme.onSurface,
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

