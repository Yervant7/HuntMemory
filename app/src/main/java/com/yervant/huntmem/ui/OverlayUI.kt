package com.yervant.huntmem.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yervant.huntmem.R
import com.yervant.huntmem.ui.menu.AddressTableMenu
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

        var localIconPosition by remember { mutableStateOf(uiState.iconPosition) }

        LaunchedEffect(uiState.iconPosition) {
            localIconPosition = uiState.iconPosition
        }

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

                            val newPosition = localIconPosition + IntOffset(
                                dragAmount.x.roundToInt(),
                                dragAmount.y.roundToInt()
                            )

                            localIconPosition = newPosition
                            onUpdateIconPosition(newPosition)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onToggleMenu() })
                    }
            ) {
                Image(
                    painter = painterResource(id = R.drawable.overlay_icon),
                    contentDescription = "Open Menu",
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
        MenuType.HUNTING -> listOf("Processes", "Memory", "Editor", "Settings")
        MenuType.SCRIPTS -> listOf("Manager", "Settings")
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
                                contentDescription = "Close Menu",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    windowInsets = WindowInsets(0.dp)
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
                            1 -> InitialMemoryMenu(context, dialogCallback = dialogCallback)
                            2 -> AddressTableMenu(context, dialogCallback = dialogCallback)
                            3 -> HuntSettings(activeMenu = activeMenu, onSwitchMenu = onSwitchMenu)
                        }
                    }
                    MenuType.SCRIPTS -> {
                        when (selectedTab) {
                            0 -> ScriptMenu(context, dialogCallback)
                            1 -> SettingsMenu(activeMenu = activeMenu, onSwitchMenu = onSwitchMenu)
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
    onSwitchMenu: (MenuType) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Current Menu: ${activeMenu.title}", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(16.dp))

        Text("Switch to another menu:", style = MaterialTheme.typography.titleSmall)

        MenuType.entries.forEach { menuType ->
            if (menuType != activeMenu) {
                Button(
                    onClick = { onSwitchMenu(menuType) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Switch to ${menuType.title}")
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
                            Text("OK")
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
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { dialogState.onConfirm(text) }) {
                            Text("Confirm")
                        }
                    }
                }
            }
        }
    }
}