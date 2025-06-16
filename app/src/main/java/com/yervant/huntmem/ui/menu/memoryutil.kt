package com.yervant.huntmem.ui.menu

import android.content.Context
import com.yervant.huntmem.backend.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScanOptions(
    val inputVal: String,
    val valueType: String,
    val operator: String
)

suspend fun onNextScanClicked(
    scanOptions: ScanOptions,
    context: Context,
    onBeforeScanStart: () -> Unit,
    onScanDone: () -> Unit,
    onScanError: (e: Exception) -> Unit,
) {
    onBeforeScanStart()
    try {
        withContext(Dispatchers.IO) {
            val mem = Memory()
            if (!((scanOptions.inputVal.contains(";") && scanOptions.inputVal.contains(":")) || scanOptions.inputVal.contains("0x") || scanOptions.inputVal.contains(".."))) {
                when (scanOptions.valueType.lowercase()) {
                    "int" -> scanOptions.inputVal.toIntOrNull()
                        ?: throw Exception("Input value is not valid for data type")

                    "long" -> scanOptions.inputVal.toLongOrNull()
                        ?: throw Exception("Input value is not valid for data type")

                    "float" -> scanOptions.inputVal.toFloatOrNull()
                        ?: throw Exception("Input value is not valid for data type")

                    "double" -> scanOptions.inputVal.toDoubleOrNull()
                        ?: throw Exception("Input value is not valid for data type")
                }
            }
            if (scanOptions.inputVal.contains(" ")) {
                throw Exception("Input value cannot contain spaces")
            } else if (scanOptions.inputVal.contains("0x")) {
                if (scanOptions.inputVal.contains("+")) {
                    mem.gotoAddrOffset(scanOptions.inputVal, context)
                } else {
                    mem.gotoOffset(scanOptions.inputVal, context)
                }
            } else {
                mem.scanValues(
                    scanOptions.inputVal,
                    context
                )
            }
        }
    } catch (e: Exception) {
        onScanError(e)
        onScanDone()
    } finally {
        onScanDone()
    }
}