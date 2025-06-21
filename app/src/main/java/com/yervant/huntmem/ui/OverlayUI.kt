package com.yervant.huntmem.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.R
import com.yervant.huntmem.ui.menu.AddressTableMenu
import com.yervant.huntmem.ui.menu.DynamicMenuScreen
import com.yervant.huntmem.ui.menu.HuntSettings
import com.yervant.huntmem.ui.menu.InitialMemoryMenu
import com.yervant.huntmem.ui.menu.ProcessScreen
import com.yervant.huntmem.ui.menu.ProcessViewModel
import com.yervant.huntmem.ui.menu.ScriptMenu
import com.yervant.huntmem.ui.theme.HuntMemTheme
import kotlin.math.roundToInt

@Composable
fun OverlayScreen(
    uiState: OverlayUiState,
    viewModel: ProcessViewModel,
    context: Context,
    dialogCallback: DialogCallback,
    onUpdateIconPosition: (IntOffset) -> Unit,
    onToggleMenu: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onSwitchMenu: (MenuType) -> Unit
) {
    HuntMemTheme(darkTheme = true) {

        if (uiState.isMenuVisible) {
            MenuContent(
                activeMenu = uiState.activeMenu,
                selectedTab = uiState.selectedTab,
                onTabSelected = onTabSelected,
                viewModel = viewModel,
                context = context,
                dialogCallback = dialogCallback,
                onClose = onToggleMenu,
                onSwitchMenu = onSwitchMenu
            )
        } else {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onUpdateIconPosition(
                                IntOffset(
                                    dragAmount.x.roundToInt(),
                                    dragAmount.y.roundToInt()
                                )
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onToggleMenu() })
                    }
            ) {
                Image(
                    painter = painterResource(id = R.drawable.overlay_icon),
                    contentDescription = stringResource(id = R.string.overlay_ui_open_menu_icon_description),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        DialogManager(dialogState = uiState.dialogState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuContent(
    activeMenu: MenuType,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    viewModel: ProcessViewModel,
    context: Context,
    dialogCallback: DialogCallback,
    onClose: () -> Unit,
    onSwitchMenu: (MenuType) -> Unit
) {
    val tabs = when (activeMenu) {
        MenuType.HUNTING -> listOf(
            stringResource(id = R.string.overlay_ui_processes_tab),
            stringResource(id = R.string.overlay_ui_memory_tab),
            stringResource(id = R.string.overlay_ui_editor_tab),
            stringResource(id = R.string.overlay_ui_settings_tab_and_title)
        )
        MenuType.SCRIPTS -> listOf(
            stringResource(id = R.string.overlay_ui_manager_tab),
            stringResource(id = R.string.overlay_ui_script_menu_tab),
            stringResource(id = R.string.overlay_ui_settings_tab_and_title)
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        PrimaryTabRow(selectedTabIndex = selectedTab) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { onTabSelected(index) },
                                    text = { Text(title, fontSize = 12.sp) },
                                    selectedContentColor = MaterialTheme.colorScheme.primary,
                                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(id = R.string.overlay_ui_close_menu_icon_description),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier
                .padding(innerPadding)
                .padding(8.dp)) {

                when (activeMenu) {
                    MenuType.HUNTING -> {
                        when (selectedTab) {
                            0 -> ProcessScreen(viewModel = viewModel)
                            1 -> InitialMemoryMenu(
                                context,
                                dialogCallback = dialogCallback
                                )
                            2 -> AddressTableMenu(context, dialogCallback = dialogCallback)
                            3 -> HuntSettings(activeMenu = activeMenu, onSwitchMenu = onSwitchMenu, context)
                        }
                    }
                    MenuType.SCRIPTS -> {
                        when (selectedTab) {
                            0 -> ScriptMenu(context, dialogCallback)
                            1 -> DynamicMenuScreen()
                            2 -> SettingsMenu(activeMenu = activeMenu, onSwitchMenu = onSwitchMenu, context)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsMenu(
    activeMenu: MenuType,
    onSwitchMenu: (MenuType) -> Unit,
    context: Context
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(id = R.string.overlay_ui_settings_tab_and_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        val menu = context.getString(R.string.overlay_ui_current_menu_label, activeMenu.title)
        Text(menu, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)

        Spacer(modifier = Modifier.height(16.dp))

        Text(stringResource(id = R.string.overlay_ui_switch_to_another_menu_label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)

        MenuType.entries.forEach { menuType ->
            if (menuType != activeMenu) {
                Button(
                    onClick = { onSwitchMenu(menuType) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val m = context.getString(R.string.overlay_ui_switch_to_menu_button, menuType.title)
                    Text(m)
                }
            }
        }
    }
}

@Composable
fun DialogManager(dialogState: DialogState) {
    when (dialogState) {
        is DialogState.Hidden -> { }
        is DialogState.Info -> {
            CustomDialog(onDismissRequest = dialogState.onDismiss) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = dialogState.title,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = dialogState.message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = dialogState.onConfirm) {
                            Text(stringResource(R.string.overlay_ui_dialog_ok_button))
                        }
                    }
                }
            }
        }
        is DialogState.Input -> {
            var text by remember { mutableStateOf(dialogState.defaultValue) }
            CustomDialog(onDismissRequest = dialogState.onDismiss) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = dialogState.title,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = dialogState.onDismiss) {
                            Text(stringResource(R.string.overlay_ui_dialog_cancel_button))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { dialogState.onConfirm(text) }) {
                            Text(stringResource(R.string.overlay_ui_dialog_confirm_button))
                        }
                    }
                }
            }
        }
    }
}