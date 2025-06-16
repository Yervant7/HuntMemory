package com.yervant.huntmem.backend

import android.content.Context
import android.util.Log
import com.yervant.huntmem.ui.menu.MatchInfo
import com.yervant.huntmem.ui.menu.getCurrentScanOption
import com.yervant.huntmem.ui.menu.getCustomFilter
import com.yervant.huntmem.ui.menu.getSelectedRegions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class Memory {

    fun listMatches(maxCount: Int): List<MatchInfo> {
        return synchronized(matches) {
            matches.take(maxCount).toList()
        }
    }

    suspend fun readMemory(pid: Int, addr: Long, datatype: String, context: Context): Number {
        return when (datatype.lowercase()) {
            "int" -> {
                val byteCount = 4
                val bytes = HuntMem().readMem(pid, addr, byteCount.toLong(), context).getOrNull()
                if (bytes != null && bytes.size == byteCount) {
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
                } else {
                    Log.w(TAG, "Failed to read Int at $addr. Returning -1.")
                    -1
                }
            }
            "long" -> {
                val byteCount = 8
                val bytes = HuntMem().readMem(pid, addr, byteCount.toLong(), context).getOrNull()
                if (bytes != null && bytes.size == byteCount) {
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
                } else {
                    Log.w(TAG, "Failed to read Long at $addr. Returning -1L.")
                    -1L
                }
            }
            "float" -> {
                val byteCount = 4
                val bytes = HuntMem().readMem(pid, addr, byteCount.toLong(), context).getOrNull()
                if (bytes != null && bytes.size == byteCount) {
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
                } else {
                    Log.w(TAG, "Failed to read Float at $addr. Returning -1.0f.")
                    -1.0f
                }
            }
            "double" -> {
                val byteCount = 8
                val bytes = HuntMem().readMem(pid, addr, byteCount.toLong(), context).getOrNull()
                if (bytes != null && bytes.size == byteCount) {
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).double
                } else {
                    Log.w(TAG, "Failed to read Double at $addr. Returning -1.0.")
                    -1.0
                }
            }
            else -> {
                Log.w(TAG, "Unsupported data type: $datatype. Returning 0.")
                -1
            }
        }
    }

    suspend fun gotoOffset(address: String, context: Context) {
        val cleanedaddr = address.removePrefix("0x").toLong(16)
        val scanOptions = getCurrentScanOption()
        val pid = AttachedProcessRepository.getAttachedPid() ?: return

        val value = readMemory(pid, cleanedaddr, scanOptions.valueType, context)
        val matchs: MutableList<MatchInfo> = mutableListOf()
        val size = when (scanOptions.valueType.lowercase()) {
            "int", "float" -> 4
            "long", "double" -> 8
            else -> Log.e(TAG, "Unsupported data type: ${scanOptions.valueType}.")
        }
        matchs.add(MatchInfo(
            id = UUID.randomUUID().toString(),
            pid,
            address = cleanedaddr,
            prevValue = value,
            valueType = scanOptions.valueType,
            size = size
        ))
        synchronized(matches) {
            matches.clear()
            matches.addAll(matchs)
        }
    }

    suspend fun gotoAddrOffset(values: String, context: Context) {
        val offs = values.split("+")

        val cleanedaddr = offs[0].removePrefix("0x").toLong(16)
        val cleanedoffset = if (offs[1].startsWith("0x")) {
            offs[1].removePrefix("0x").toLong(16)
        } else {
            offs[1].toLong(16)
        }

        val scanOptions = getCurrentScanOption()
        val pid = AttachedProcessRepository.getAttachedPid() ?: return

        val value = readMemory(pid, cleanedaddr + cleanedoffset, scanOptions.valueType, context)
        val matchs: MutableList<MatchInfo> = mutableListOf()
        val size = when (scanOptions.valueType.lowercase()) {
            "int", "float" -> 4
            "long", "double" -> 8
            else -> Log.e(TAG, "Unsupported data type: ${scanOptions.valueType}.")
        }
        matchs.add(MatchInfo(
            id = UUID.randomUUID().toString(),
            pid = pid,
            address = cleanedaddr + cleanedoffset,
            prevValue = value,
            valueType = scanOptions.valueType,
            size = size
        ))
        synchronized(matches) {
            matches.clear()
            matches.addAll(matchs)
        }
    }

    suspend fun scanValues(numValStr: String, context: Context) {
        try {
            val pid = AttachedProcessRepository.getAttachedPid() ?: throw Exception("pid is null")
            val results: MutableList<MatchInfo> = mutableListOf()
            val localMatches = synchronized(matches) { matches.toList() }
            val scanOptions = getCurrentScanOption()
            val selectedRegions = getSelectedRegions()
            val customFilter = getCustomFilter()
            val regions = MemoryScanner(pid).getMemoryRegions(selectedRegions, customFilter)

            if (numValStr.contains(";") && numValStr.contains(":")) {
                if (localMatches.isEmpty()) {
                    val res = HuntMem().searchGroup(
                                pid,
                                numValStr,
                                scanOptions.valueType.lowercase(),
                                context,
                                regions
                            ).getOrNull()
                    if (!res.isNullOrEmpty()) {
                        results.addAll(res)
                    }
                } else {
                    val split = numValStr.split(":")
                    val values = split[0].split(";")
                    val res = MemoryScanner(pid).filterGroupAddressesAuto(localMatches, values, context, scanOptions.operator)
                    results.addAll(res)
                }
            } else if (numValStr.contains("..")) {
                val values = numValStr.split("..")
                if (localMatches.isEmpty()) {
                    val res = HuntMem().searchRange(
                                pid,
                                values[0],
                                values[1],
                                scanOptions.valueType.lowercase(),
                                context,
                                regions
                            ).getOrNull()
                    if (!res.isNullOrEmpty()) {
                        results.addAll(res)
                    }
                } else {
                    val res = MemoryScanner(pid).filterRangeAuto(
                        localMatches,
                        values[0],
                        values[1],
                        context
                    )
                    results.addAll(res)
                }
            } else {
                if (localMatches.isEmpty()) {
                    val res = HuntMem().search(pid, numValStr, scanOptions.valueType.lowercase(), context, regions).getOrNull()
                    if (!res.isNullOrEmpty()) {
                        results.addAll(res)
                    }
                } else {
                    val matchs = MemoryScanner(pid).filterAddressesAuto(localMatches, numValStr, context, scanOptions.operator)
                    results.addAll(matchs)
                }
            }
            if (results.isEmpty()) {
                Log.d("Memory", "No results to write")
            }
            synchronized(matches) {
                matches.clear()
                matches.addAll(results)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("MemoryScan", "An error occurred: ${e.message}")
        }
    }

    companion object {
        const val TAG = "Memory"
        var matches: MutableList<MatchInfo> = mutableListOf()
    }
}

class HuntSettings {
    companion object {
        const val maxShownMatchesCount: Int = 1000
    }
}