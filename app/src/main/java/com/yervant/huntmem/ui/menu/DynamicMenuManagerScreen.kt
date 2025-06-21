package com.yervant.huntmem.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.luaj.vm2.LuaValue
import kotlin.math.roundToInt

@Composable
fun DynamicMenuScreen() {
    val components = DynamicMenuManager.components
    val coroutineScope = rememberCoroutineScope()

    if (components.isNotEmpty()) {
        Card(
            modifier = Modifier
                .width(280.dp)
                .wrapContentHeight(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            LazyColumn(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(components, key = { it.id }) { component ->
                    when (component) {
                        is DynamicMenuComponent.Button -> MenuButton(component, coroutineScope)
                        is DynamicMenuComponent.Switch -> MenuSwitch(component, coroutineScope)
                        is DynamicMenuComponent.Label -> MenuLabel(component)
                        is DynamicMenuComponent.Slider -> MenuSlider(component, coroutineScope)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuButton(component: DynamicMenuComponent.Button, scope: kotlinx.coroutines.CoroutineScope) {
    val label by remember { component.label }
    Button(
        onClick = {
            component.onClick?.let { luaFunc ->
                scope.launch(Dispatchers.IO) {
                    luaFunc.call()
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = label)
    }
}

@Composable
private fun MenuSwitch(component: DynamicMenuComponent.Switch, scope: kotlinx.coroutines.CoroutineScope) {
    val label by remember { component.label }
    var isChecked by remember { component.checked }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = isChecked,
            onCheckedChange = { newCheckedState ->
                isChecked = newCheckedState
                component.onToggle?.let { luaFunc ->
                    scope.launch(Dispatchers.IO) {
                        luaFunc.call(LuaValue.valueOf(newCheckedState))
                    }
                }
            }
        )
    }
}

@Composable
private fun MenuLabel(component: DynamicMenuComponent.Label) {
    val text by remember { component.text }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

@Composable
private fun MenuSlider(component: DynamicMenuComponent.Slider, scope: kotlinx.coroutines.CoroutineScope) {
    val label by remember { component.label }
    var sliderValue by remember { component.value }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (component.steps > 0) sliderValue.roundToInt().toString() else "%.1f".format(sliderValue),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { newValue ->
                sliderValue = newValue
            },
            onValueChangeFinished = {
                component.onValueChange?.let { luaFunc ->
                    scope.launch(Dispatchers.IO) {
                        luaFunc.call(LuaValue.valueOf(sliderValue.toDouble()))
                    }
                }
            },
            valueRange = component.valueRange,
            steps = if (component.steps > 0) component.steps - 1 else 0,
            modifier = Modifier.fillMaxWidth()
        )
    }
}