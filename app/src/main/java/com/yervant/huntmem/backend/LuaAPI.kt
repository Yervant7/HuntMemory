package com.yervant.huntmem.backend

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.luaj.vm2.*
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean


object FreezeService {
    private const val TAG = "FreezeService"

    data class FreezeData(
        val id: String,
        val pid: Int,
        val address: Long,
        val value: String,
        val valueType: String,
        val interval: Long = 100L,
        val job: Job,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val activeFreezes = ConcurrentHashMap<String, FreezeData>()
    private val isServiceRunning = AtomicBoolean(false)
    private var serviceScope: CoroutineScope? = null
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        if (isServiceRunning.compareAndSet(false, true)) {
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            Log.d(TAG, "FreezeService started and initialized.")
        }
    }

    fun stopService() {
        if (isServiceRunning.compareAndSet(true, false)) {
            stopAllFreezes()
            serviceScope?.cancel()
            serviceScope = null
            applicationContext = null
            Log.d(TAG, "FreezeService stopped.")
        }
    }

    fun startFreeze(
        pid: Int,
        address: Long,
        value: String,
        valueType: String,
        interval: Long = 100L
    ): String {
        val scope = serviceScope ?: return "Error: FreezeService is not running."

        try {
            val freezeId = UUID.randomUUID().toString()
            val job = scope.launch {
                var consecutiveErrors = 0
                val maxErrors = 5

                while (isActive && consecutiveErrors < maxErrors) {
                    try {
                        val success = writeMemoryValue(pid, address, value, valueType)
                        if (success) {
                            consecutiveErrors = 0
                        } else {
                            consecutiveErrors++
                            Log.w(
                                TAG,
                                "Failed to write for freeze $freezeId (attempt $consecutiveErrors)"
                            )
                        }
                        delay(interval)
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Freeze $freezeId cancelled.")
                        break
                    } catch (e: Exception) {
                        consecutiveErrors++
                        Log.e(TAG, "Error in freeze loop for $freezeId: ${e.message}")
                        if (consecutiveErrors >= maxErrors) {
                            Log.e(
                                TAG,
                                "Freeze $freezeId stopped after $maxErrors consecutive errors."
                            )
                            break
                        }
                        delay(interval)
                    }
                }

                activeFreezes.remove(freezeId)
            }

            val freezeData = FreezeData(freezeId, pid, address, value, valueType, interval, job)
            activeFreezes[freezeId] = freezeData

            Log.d(TAG, "Freeze started: $freezeId for address 0x${address.toString(16)}")
            return freezeId

        } catch (e: Exception) {
            Log.e(TAG, "Error starting freeze: ${e.message}", e)
            return "Error: ${e.message}"
        }
    }

    fun stopFreeze(freezeId: String): Boolean {
        val freezeData = activeFreezes.remove(freezeId)
        return if (freezeData != null) {
            freezeData.job.cancel()
            Log.d(TAG, "Freeze stopped: $freezeId")
            true
        } else {
            Log.w(TAG, "Freeze not found: $freezeId")
            false
        }
    }

    fun stopAllFreezes() {
        if (activeFreezes.isEmpty()) return
        Log.d(TAG, "Stopping all ${activeFreezes.size} freezes...")
        activeFreezes.values.forEach { it.job.cancel() }
        activeFreezes.clear()
        Log.d(TAG, "All freezes have been stopped.")
    }

    fun getActiveFreezes(): List<FreezeData> = activeFreezes.values.toList()

    fun isFreezeActive(freezeId: String): Boolean = activeFreezes.containsKey(freezeId)

    private suspend fun writeMemoryValue(pid: Int, address: Long, value: String, valueType: String): Boolean {
        val context = applicationContext ?: return false
        return try {
            HuntMem().writeMem(pid, address, valueType, value, context).isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to memory: ${e.message}")
            false
        }
    }
}


object LuaAPI {
    private const val TAG = "LuaAPI"

    private val scriptData = ConcurrentHashMap<String, Any>()
    private val isInitialized = AtomicBoolean(false)

    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        if (isInitialized.get()) return
        applicationContext = context.applicationContext
        FreezeService.initialize(context)
        isInitialized.set(true)
        Log.d(TAG, "LuaAPI initialized.")
    }

    suspend fun executeScript(luaCode: String): String = withContext(Dispatchers.IO) {
        if (!isInitialized.get()) {
            return@withContext "Error: LuaAPI not initialized. Call initialize() first."
        }
        try {
            val globals = JsePlatform.standardGlobals()

            registerMemoryFunctions(globals)
            registerUtilityFunctions(globals)
            registerDataFunctions(globals)
            registerFreezeFunctions(globals)

            val chunk = globals.load(luaCode)
            val result = chunk.call()

            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Lua script: ${e.message}", e)
            "Error: ${e.message}"
        }
    }

    private fun registerMemoryFunctions(globals: Globals) {
        val context = applicationContext ?: throw IllegalStateException("ApplicationContext not available.")

        globals.set("searchMemory", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                return try {
                    val value = args.checkjstring(1)
                    val valueType = args.checkjstring(2)
                    val operator = "="
                    runBlocking {
                        val memory = Memory()
                        memory.scanValues(value, valueType, operator, context)
                        val matches = memory.listMatches(1000)
                        val table = LuaTable()
                        matches.forEachIndexed { index, match ->
                            val matchTable = LuaTable()
                            matchTable.set("address", LuaString.valueOf(match.address.toString(16)))
                            matchTable.set("value", LuaString.valueOf(match.prevValue.toString()))
                            matchTable.set("type", LuaString.valueOf(match.valueType))
                            table.set(index + 1, matchTable)
                        }
                        table
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in searchMemory: ${e.message}")
                    LuaValue.NIL
                }
            }
        })

        globals.set("readMemory", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                return try {
                    val address = args.checkjstring(1).toLong(16)
                    val valueType = args.optjstring(2, "int")
                    val pid = AttachedProcessRepository.getAttachedPid() ?: return LuaValue.NIL

                    runBlocking {
                        val memory = Memory()
                        val value = memory.readMemory(pid, address, valueType, context)
                        when (valueType.lowercase()) {
                            "int" -> LuaInteger.valueOf(value.toInt())
                            "long" -> LuaInteger.valueOf(value.toLong())
                            "float" -> LuaDouble.valueOf(value.toDouble())
                            "double" -> LuaDouble.valueOf(value.toDouble())
                            else -> LuaString.valueOf(value.toString())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in readMemory: ${e.message}")
                    LuaValue.NIL
                }
            }
        })

        globals.set("writeMemory", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                return try {
                    val address = args.checkjstring(1).toLong(16)
                    val value = args.checkjstring(2)
                    val valueType = args.optjstring(3, "int")
                    val pid = AttachedProcessRepository.getAttachedPid() ?: return LuaValue.FALSE

                    runBlocking {
                        val success = HuntMem().writeMem(pid, address, valueType, value, context).isSuccess
                        if (success) LuaValue.TRUE else LuaValue.FALSE
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in writeMemory: ${e.message}")
                    LuaValue.FALSE
                }
            }
        })

        globals.set("gotoAddress", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                return try {
                    val address = args.checkjstring(1)

                    runBlocking {
                        val memory = Memory()
                        if (address.contains("+")) {
                            memory.gotoAddrOffset(address, context)
                        } else {
                            memory.gotoOffset(address, context)
                        }
                        val matches = memory.listMatches(1)
                        if (matches.isNotEmpty()) {
                            val match = matches[0]
                            val table = LuaTable()
                            table.set("address", LuaString.valueOf(match.address.toString(16)))
                            table.set("value", LuaString.valueOf(match.prevValue.toString()))
                            table.set("type", LuaString.valueOf(match.valueType))
                            table
                        } else {
                            LuaValue.NIL
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in gotoAddress: ${e.message}")
                    LuaValue.NIL
                }
            }
        })

        globals.set("filterResults", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                return try {
                    val value = args.checkjstring(1)
                    val valueType = args.checkjstring(2)
                    val operator = args.optjstring(3, "=")

                    runBlocking {
                        val memory = Memory()
                        memory.scanValues(value, valueType, operator, context)
                        val matches = memory.listMatches(1000)

                        val table = LuaTable()
                        matches.forEachIndexed { index, match ->
                            val matchTable = LuaTable()
                            matchTable.set("address", LuaString.valueOf(match.address.toString(16)))
                            matchTable.set("value", LuaString.valueOf(match.prevValue.toString()))
                            matchTable.set("type", LuaString.valueOf(match.valueType))
                            table.set(index + 1, matchTable)
                        }
                        table
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in filterResults: ${e.message}")
                    LuaValue.NIL
                }
            }
        })
    }

    private fun registerUtilityFunctions(globals: Globals) {
        globals.set("log", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val message = args.checkjstring(1)
                Log.d("LuaScript", message)
                return LuaValue.NIL
            }
        })

        globals.set("getAttachedPid", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val pid = AttachedProcessRepository.getAttachedPid()
                return pid?.let { LuaInteger.valueOf(it) } ?: LuaValue.NIL
            }
        })
    }

    private fun registerDataFunctions(globals: Globals) {
        globals.set("setData", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                scriptData[args.checkjstring(1)] = args.checkjstring(2)
                return LuaValue.NIL
            }
        })

        globals.set("getData", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val value = scriptData[args.checkjstring(1)]
                return value?.let { LuaString.valueOf(it.toString()) } ?: LuaValue.NIL
            }
        })
    }

    private fun registerFreezeFunctions(globals: Globals) {
        globals.set("startFreeze", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                return try {
                    val address = args.checkjstring(1).removePrefix("0x").toLong(16)
                    val value = args.checkjstring(2)
                    val valueType = args.optjstring(3, "int")
                    val interval = args.optlong(4, 100L)
                    val pid = AttachedProcessRepository.getAttachedPid()
                        ?: return LuaValue.valueOf("Error: No process attached")

                    val freezeId = FreezeService.startFreeze(pid, address, value, valueType, interval)
                    LuaValue.valueOf(freezeId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in Lua startFreeze: ${e.message}")
                    LuaValue.valueOf("Error: ${e.message}")
                }
            }
        })

        globals.set("stopFreeze", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                return try {
                    val freezeId = args.checkjstring(1)
                    if (FreezeService.stopFreeze(freezeId)) LuaValue.TRUE else LuaValue.FALSE
                } catch (e: Exception) {
                    Log.e(TAG, "Error in Lua stopFreeze: ${e.message}")
                    LuaValue.FALSE
                }
            }
        })

        globals.set("stopAllFreezes", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                FreezeService.stopAllFreezes()
                return LuaValue.NIL
            }
        })

        globals.set("getActiveFreezes", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val freezes = FreezeService.getActiveFreezes()
                val table = LuaTable()
                freezes.forEachIndexed { index, freeze ->
                    val freezeTable = LuaTable()
                    freezeTable.set("id", LuaValue.valueOf(freeze.id))
                    freezeTable.set("address", LuaValue.valueOf("0x${freeze.address.toString(16)}"))
                    // ... add other fields if needed
                    table.set(index + 1, freezeTable)
                }
                return table
            }
        })
    }

    fun cleanup() {
        FreezeService.stopService()
        scriptData.clear()
        isInitialized.set(false)
        applicationContext = null
        Log.d(TAG, "LuaAPI cleaned up.")
    }
}
