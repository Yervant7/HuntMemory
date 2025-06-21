package com.yervant.huntmem.backend

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.topjohnwu.superuser.Shell
import com.yervant.huntmem.ui.menu.MatchInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MemoryScanner(private val pid: Int) {

    enum class MemoryRegion {
        ALLOC,
        BSS,
        DATA,
        HEAP,
        JAVA_HEAP,
        ANONYMOUS,
        CODE_SYSTEM,
        STACK,
        ASHMEM,
        LIBS,
        CUSTOM;

        companion object {
            fun fromStr(s: String): MemoryRegion? {
                return entries.find { it.name == s.uppercase() }
            }
        }

        fun matches(entry: MemoryRegions, customFilter: String? = null): Boolean {
            return when (this) {
                ALLOC -> entry.path.contains("[anon:libc_malloc]")
                BSS -> entry.path.contains("[anon:.bss]")
                DATA -> entry.path.contains("/data/app/")
                HEAP -> entry.path.contains("[heap]")
                JAVA_HEAP -> entry.path.contains("/dev/ashmem")
                ANONYMOUS -> entry.path.isEmpty()
                CODE_SYSTEM -> entry.path.contains("/system")
                STACK -> entry.path.contains("[stack]")
                ASHMEM -> entry.path.contains("/dev/ashmem/dalvik")
                LIBS -> entry.path.contains("libil2cpp.so") || entry.path.contains("libUE4.so")
                CUSTOM -> customFilter?.let { entry.path.contains(it) } ?: false
            }
        }
    }

    @Keep
    data class MemoryRegions(
        val start: Long,
        val end: Long,
        val permissions: String,
        val offset: Long,
        val device: String,
        val inode: Long,
        val path: String
    )

    fun readMemoryMaps(): List<MemoryRegions> {
        val output = Shell.cmd("cat /proc/$pid/maps").exec()

        return output.out.map { line ->
            val lineParts = line.trim().split("\\s+".toRegex(), limit = 6)
            val addressRange = lineParts[0].split("-")

            MemoryRegions(
                start = addressRange[0].toLong(16),
                end = addressRange[1].toLong(16),
                permissions = lineParts[1],
                offset = lineParts[2].toLong(16),
                device = lineParts[3],
                inode = lineParts[4].toLongOrNull() ?: 0L,
                path = if (lineParts.size > 5) lineParts[5].trim() else ""
            )
        }
    }

    fun findExecutableMemoryRange(address: Long): Pair<Long, Long>? {
        val memoryRegions = readMemoryMaps()

        val region = memoryRegions.find { region ->
            address >= region.start && address < region.end
        } ?: return null

        if (!region.permissions.contains('x')) {
            return null
        }

        val addressesBeforeLimit = minOf(250, ((address - region.start) / 4).toInt())
        val addressesAfterLimit = minOf(250, ((region.end - address) / 4).toInt() - 1)

        val startAddress = address - (addressesBeforeLimit * 4L)
        val totalAddresses = addressesBeforeLimit + 1 + addressesAfterLimit
        val size = totalAddresses * 4L

        return Pair(startAddress, size)
    }

    fun getMemoryRegions(regions: List<MemoryRegion>, customFilter: String?): List<MemoryRegions> {
        val memoryRegions = readMemoryMaps()
        return memoryRegions
            .filter { it.permissions.contains("r") }
            .filter { region ->
                regions.any { regionType ->
                    regionType.matches(region, customFilter)
                }
            }
    }

    private fun <T> compareValues(value: T, target: T, operator: String): Boolean
            where T : Comparable<T> {
        return when (operator) {
            "=" -> value == target
            "==" -> value == target
            "!=" -> value != target
            ">" -> value > target
            "<" -> value < target
            ">=" -> value >= target
            "<=" -> value <= target
            else -> false
        }
    }

    suspend fun filterAddressesAuto(
        matches: List<MatchInfo>,
        targetValue: String,
        context: Context,
        operator: String = "="
    ): List<MatchInfo> = coroutineScope {
        matches
            .map { match ->
                async {
                    val isValid = when (match.valueType.lowercase()) {
                        "int" -> readAndCompare<Int>(match, 4, targetValue, context, operator)
                        "long" -> readAndCompare<Long>(match, 8, targetValue, context, operator)
                        "float" -> readAndCompare<Float>(match, 4, targetValue, context, operator)
                        "double" -> readAndCompare<Double>(match, 8, targetValue, context, operator)
                        else -> false
                    }
                    isValid to match
                }
            }
            .awaitAll()
            .filter { it.first }
            .map { it.second }
    }

    private suspend inline fun <reified T> readAndCompare(
        match: MatchInfo,
        bytes: Int,
        target: String,
        context: Context,
        operator: String = "="
    ): Boolean where T : Number, T : Comparable<T> {
        return HuntMem().readMem(pid, match.address, bytes.toLong(), context)
            .fold(
                onSuccess = { memoryBytes ->
                    if (memoryBytes.size < bytes) return false

                    try {
                        val buffer = ByteBuffer.wrap(memoryBytes).order(ByteOrder.LITTLE_ENDIAN)

                        val value: T = when (T::class) {
                            Int::class -> buffer.int as T
                            Long::class -> buffer.long as T
                            Float::class -> buffer.float as T
                            Double::class -> buffer.double as T
                            else -> return false
                        }

                        val targetValue: T = when (T::class) {
                            Int::class -> target.toIntOrNull() as T?
                            Long::class -> target.toLongOrNull() as T?
                            Float::class -> target.toFloatOrNull() as T?
                            Double::class -> target.toDoubleOrNull() as T?
                            else -> null
                        } ?: return false

                        compareValues(value, targetValue, operator)

                    } catch (e: Exception) {
                        Log.e("MemoryScanner", "Error comparing values: ${e.message}", e)
                        false
                    }
                },
                onFailure = { false }
            )
    }

    private fun <T> compareRangeValues(value: T, target: T, target2: T): Boolean
            where T : Comparable<T> {
        return value in target..target2
    }

    suspend fun filterRangeAuto(
        matches: List<MatchInfo>,
        targetValue: String,
        targetValue2: String,
        context: Context,
    ): List<MatchInfo> = coroutineScope {
        matches
            .map { match ->
                async {
                    val isValid = when (match.valueType.lowercase()) {
                        "int" -> readAndCompareRange<Int>(match, 4, targetValue, targetValue2, context)
                        "long" -> readAndCompareRange<Long>(match, 8, targetValue, targetValue2, context)
                        "float" -> readAndCompareRange<Float>(match, 4, targetValue, targetValue2, context)
                        "double" -> readAndCompareRange<Double>(match, 8, targetValue, targetValue2, context)
                        else -> false
                    }
                    isValid to match
                }
            }
            .awaitAll()
            .filter { it.first }
            .map { it.second }
    }

    private suspend inline fun <reified T> readAndCompareRange(
        match: MatchInfo,
        bytes: Int,
        target: String,
        target2: String,
        context: Context,
    ): Boolean where T : Number, T : Comparable<T> {
        return HuntMem().readMem(pid, match.address, bytes.toLong(), context)
            .fold(
                onSuccess = { memoryBytes ->
                    if (memoryBytes.size < bytes) return false

                    try {
                        val buffer = ByteBuffer.wrap(memoryBytes).order(ByteOrder.LITTLE_ENDIAN)

                        val value: T = when (T::class) {
                            Int::class -> buffer.int as T
                            Long::class -> buffer.long as T
                            Float::class -> buffer.float as T
                            Double::class -> buffer.double as T
                            else -> return false
                        }

                        val targetValue: T = when (T::class) {
                            Int::class -> target.toIntOrNull() as T?
                            Long::class -> target.toLongOrNull() as T?
                            Float::class -> target.toFloatOrNull() as T?
                            Double::class -> target.toDoubleOrNull() as T?
                            else -> null
                        } ?: return false

                        val targetValue2: T = when (T::class) {
                            Int::class -> target2.toIntOrNull() as T?
                            Long::class -> target2.toLongOrNull() as T?
                            Float::class -> target2.toFloatOrNull() as T?
                            Double::class -> target2.toDoubleOrNull() as T?
                            else -> null
                        } ?: return false

                        compareRangeValues(value, targetValue, targetValue2)

                    } catch (e: Exception) {
                        Log.e("MemoryScanner", "Error comparing values: ${e.message}", e)
                        false
                    }
                },
                onFailure = { false }
            )
    }

    private fun <T> compareGroupValues(value: T, targets: List<T>, operator: String): Boolean
            where T : Comparable<T> {
        val list = targets.filter {
            when (operator) {
                "=" -> value == it
                "==" -> value == it
                "!=" -> value != it
                ">" -> value > it
                "<" -> value < it
                ">=" -> value >= it
                "<=" -> value <= it
                else -> false
            }
        }
        return list.isNotEmpty()
    }

    suspend fun filterGroupAddressesAuto(
        matches: List<MatchInfo>,
        targetValues: List<String>,
        context: Context,
        operator: String = "="
    ): List<MatchInfo> = coroutineScope {
        matches
            .map { match ->
                async {
                    val isValid = when (match.valueType.lowercase()) {
                        "int" -> readAndCompareGroup<Int>(match, 4, targetValues, context, operator)
                        "long" -> readAndCompareGroup<Long>(match, 8, targetValues, context, operator)
                        "float" -> readAndCompareGroup<Float>(match, 4, targetValues, context, operator)
                        "double" -> readAndCompareGroup<Double>(match, 8, targetValues, context, operator)
                        else -> false
                    }
                    isValid to match
                }
            }
            .awaitAll()
            .filter { it.first }
            .map { it.second }
    }

    private suspend inline fun <reified T> readAndCompareGroup(
        match: MatchInfo,
        bytes: Int,
        targets: List<String>,
        context: Context,
        operator: String = "="
    ): Boolean where T : Number, T : Comparable<T> {
        return HuntMem().readMem(pid, match.address, bytes.toLong(), context)
            .fold(
                onSuccess = { memoryBytes ->
                    if (memoryBytes.size < bytes) return false

                    try {
                        val buffer = ByteBuffer.wrap(memoryBytes).order(ByteOrder.LITTLE_ENDIAN)

                        val value: T = when (T::class) {
                            Int::class -> buffer.int as T
                            Long::class -> buffer.long as T
                            Float::class -> buffer.float as T
                            Double::class -> buffer.double as T
                            else -> return false
                        }
                        val targetValues: MutableList<T> = mutableListOf()
                        targets.forEach { target ->
                            val tmpValue: T = when (T::class) {
                                Int::class -> target.toIntOrNull() as T?
                                Long::class -> target.toLongOrNull() as T?
                                Float::class -> target.toFloatOrNull() as T?
                                Double::class -> target.toDoubleOrNull() as T?
                                else -> null
                            } ?: return false
                            targetValues.add(tmpValue)
                        }

                        compareGroupValues(value, targetValues, operator)

                    } catch (e: Exception) {
                        Log.e("MemoryScanner", "Error comparing values: ${e.message}", e)
                        false
                    }
                },
                onFailure = { false }
            )
    }
}