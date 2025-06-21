package com.yervant.huntmem.ui.menu

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import java.util.UUID

sealed class DynamicMenuComponent {
    abstract val id: String

    data class Button(
        override val id: String = UUID.randomUUID().toString(),
        val label: MutableState<String>,
        val onClick: LuaFunction?
    ) : DynamicMenuComponent()

    data class Switch(
        override val id: String = UUID.randomUUID().toString(),
        val label: MutableState<String>,
        val checked: MutableState<Boolean>,
        val onToggle: LuaFunction?
    ) : DynamicMenuComponent()

    data class Label(
        override val id: String = UUID.randomUUID().toString(),
        val text: MutableState<String>
    ) : DynamicMenuComponent()

    data class Slider(
        override val id: String = UUID.randomUUID().toString(),
        val label: MutableState<String>,
        val value: MutableState<Float>,
        val valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
        val steps: Int = 0,
        val onValueChange: LuaFunction?
    ) : DynamicMenuComponent()
}

object DynamicMenuManager {
    private val _components = mutableListOf<DynamicMenuComponent>().toMutableStateList()
    val components: SnapshotStateList<DynamicMenuComponent> = _components

    fun clearMenu() {
        _components.clear()
    }

    fun removeItem(id: String) {
        _components.removeAll { it.id == id }
    }

    fun updateText(id: String, newText: String) {
        val component = _components.find { it.id == id }
        when (component) {
            is DynamicMenuComponent.Button -> component.label.value = newText
            is DynamicMenuComponent.Switch -> component.label.value = newText
            is DynamicMenuComponent.Label -> component.text.value = newText
            is DynamicMenuComponent.Slider -> component.label.value = newText
            else -> {  }
        }
    }

    fun updateValue(id: String, newValue: Float) {
        val component = _components.find { it.id == id }
        if (component is DynamicMenuComponent.Slider) {
            component.value.value = newValue
        }
    }

    private fun addButton(label: String, onClick: LuaFunction?): String {
        val component = DynamicMenuComponent.Button(label = mutableStateOf(label), onClick = onClick)
        _components.add(component)
        return component.id
    }

    private fun addSwitch(label: String, initialValue: Boolean, onToggle: LuaFunction?): String {
        val component = DynamicMenuComponent.Switch(
            label = mutableStateOf(label),
            checked = mutableStateOf(initialValue),
            onToggle = onToggle
        )
        _components.add(component)
        return component.id
    }

    private fun addLabel(text: String): String {
        val component = DynamicMenuComponent.Label(text = mutableStateOf(text))
        _components.add(component)
        return component.id
    }

    private fun addSlider(
        label: String,
        initialValue: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int,
        onValueChange: LuaFunction?
    ): String {
        val component = DynamicMenuComponent.Slider(
            label = mutableStateOf(label),
            value = mutableStateOf(initialValue),
            valueRange = valueRange,
            steps = steps,
            onValueChange = onValueChange
        )
        _components.add(component)
        return component.id
    }

    fun exportToLua(globals: Globals) {
        globals.set("clear_menu", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                clearMenu()
                return LuaValue.NIL
            }
        })

        globals.set("add_button", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val label = args.checkjstring(1)
                val onClick = args.optfunction(2, null)
                val buttonId = addButton(label, onClick)
                return LuaValue.valueOf(buttonId)
            }
        })

        globals.set("add_switch", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val label = args.checkjstring(1)
                val initialValue = args.checkboolean(2)
                val onToggle = args.optfunction(3, null)
                val switchId = addSwitch(label, initialValue, onToggle)
                return LuaValue.valueOf(switchId)
            }
        })

        globals.set("add_label", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val text = args.checkjstring(1)
                val labelId = addLabel(text)
                return LuaValue.valueOf(labelId)
            }
        })

        globals.set("add_slider", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val label = args.checkjstring(1)
                val initialValue = args.checkdouble(2).toFloat()
                val minValue = args.optdouble(3, 0.0).toFloat()
                val maxValue = args.optdouble(4, 100.0).toFloat()
                val steps = args.optint(5, 0)
                val onValueChange = args.optfunction(6, null)

                val sliderId = addSlider(
                    label = label,
                    initialValue = initialValue,
                    valueRange = minValue..maxValue,
                    steps = steps,
                    onValueChange = onValueChange
                )
                return LuaValue.valueOf(sliderId)
            }
        })

        globals.set("remove_item", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val id = args.checkjstring(1)
                removeItem(id)
                return LuaValue.NIL
            }
        })

        globals.set("update_text", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val id = args.checkjstring(1)
                val newText = args.checkjstring(2)
                updateText(id, newText)
                return LuaValue.NIL
            }
        })

        globals.set("update_value", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val id = args.checkjstring(1)
                val newValue = args.checkdouble(2).toFloat()
                updateValue(id, newValue)
                return LuaValue.NIL
            }
        })
    }
}