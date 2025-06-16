package com.yervant.huntmem.backend

import android.content.Context
import android.util.Log
import com.yervant.huntmem.ui.menu.AddressInfo
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class Editor {
    companion object {
        private val writeJobs = ConcurrentHashMap<String, Job>()
        private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private const val TAG = "HuntMemEditor"
    }

    suspend fun writeall(addrs: MutableList<AddressInfo>, value: String, context: Context) {
        val pid = AttachedProcessRepository.getAttachedPid() ?: return
        val huntmem = HuntMem()

        addrs.forEach { addressInfo ->
            var skip = false
            when (addressInfo.matchInfo.valueType.lowercase()) {
                "int" -> value.toIntOrNull() ?: run { skip = true }
                "long" -> value.toLongOrNull() ?: run { skip = true }
                "float" -> value.toFloatOrNull() ?: run { skip = true }
                "double" -> value.toDoubleOrNull() ?: run { skip = true }
            }
            if (!skip) {
                huntmem.writeMem(
                    pid = pid,
                    address = addressInfo.matchInfo.address,
                    datatype = addressInfo.matchInfo.valueType,
                    value = value,
                    context = context
                )
            }
        }
    }

    private fun writeContinuouslyInBackground(
        addressInfo: AddressInfo,
        value: String,
        context: Context,
        intervalMs: Long = 100
    ) {
        val pid = AttachedProcessRepository.getAttachedPid() ?: return
        val huntmem = HuntMem()
        val address = addressInfo.matchInfo.address.toString(16)
        val dataType = addressInfo.matchInfo.valueType

        cancelWritingToAddress(address)

        val job = coroutineScope.launch {
            while (isActive) {
                try {
                    if (!Process().processIsRunning(pid.toString())) {
                        Log.w(TAG, "Process is no longer running, canceling write to 0x$address")
                        break
                    }

                    var skip = false
                    when (dataType.lowercase()) {
                        "int" -> value.toIntOrNull() ?: run { skip = true }
                        "long" -> value.toLongOrNull() ?: run { skip = true }
                        "float" -> value.toFloatOrNull() ?: run { skip = true }
                        "double" -> value.toDoubleOrNull() ?: run { skip = true }
                    }

                    if (!skip) {
                        huntmem.writeMem(
                            pid = pid,
                            address = address.toLong(16),
                            datatype = dataType,
                            value = value,
                            context = context
                        )
                    } else {
                        Log.w(TAG, "Invalid value for type $dataType: $value")
                    }

                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in continuous writing for 0x$address: ${e.message}")
                    break
                }
            }

            withContext(Dispatchers.Main) {
                addressInfo.isFrozen = false
            }
            writeJobs.remove(address)
        }

        writeJobs[address] = job
        addressInfo.isFrozen = true
    }

    private fun cancelWritingToAddress(address: String): Boolean {
        val job = writeJobs[address]
        if (job != null && job.isActive) {
            job.cancel()
            writeJobs.remove(address)
            return true
        }
        return false
    }

    private fun writeContinuouslyToMultipleAddresses(
        addressList: List<AddressInfo>,
        value: String,
        context: Context,
        intervalMs: Long = 100
    ) {
        addressList.forEach { addressInfo ->
            writeContinuouslyInBackground(
                addressInfo = addressInfo,
                value = value,
                context = context,
                intervalMs = intervalMs
            )
        }
    }

    private fun cancelAllBackgroundWrites(): Int {
        var cancelCount = 0

        writeJobs.forEach { (_, job) ->
            if (job.isActive) {
                job.cancel()
                cancelCount++
            }
        }

        writeJobs.clear()
        return cancelCount
    }

    fun freezeAddress(addressInfo: AddressInfo, context: Context) {
        writeContinuouslyInBackground(
            addressInfo = addressInfo,
            value = addressInfo.matchInfo.prevValue.toString(),
            context = context
        )
    }

    fun unfreezeAddress(addressInfo: AddressInfo) {
        val address = addressInfo.matchInfo.address.toString(16)
        cancelWritingToAddress(address)
        addressInfo.isFrozen = false
    }

    fun freezeall(addressList: List<AddressInfo>, value: String, context: Context) {
        writeContinuouslyToMultipleAddresses(
            addressList = addressList,
            value = value,
            context = context
        )
    }

    fun unfreezeall(addressList: List<AddressInfo>) {
        cancelAllBackgroundWrites()
        addressList.forEach { it.isFrozen = false }
    }

    fun syncFreezeState(addressList: List<AddressInfo>) {
        addressList.forEach { addressInfo ->
            val address = addressInfo.matchInfo.address.toString(16)
            val isActivelyWriting = writeJobs.containsKey(address) &&
                    writeJobs[address]?.isActive == true

            if (addressInfo.isFrozen != isActivelyWriting) {
                addressInfo.isFrozen = isActivelyWriting
            }
        }
    }
}