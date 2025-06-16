package com.yervant.huntmem.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

class ShellCommandException(message: String) : Exception(message)

@Composable
fun MainScreen(openBootPicker: () -> Unit) {
    val ctx = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Home", "Patch Boot")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    text = { Text(title) },
                    selected = selectedTab == index,
                    onClick = { selectedTab = index }
                )
            }
        }

        when (selectedTab) {
            0 -> HomeTab(ctx)
            1 -> PatchBootTab(openBootPicker, ctx)
        }
    }
}

@Composable
private fun HomeTab(ctx: Context) {
    var textInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Happy Hunting!",
            style = MaterialTheme.typography.headlineMedium
        )

        OutlinedTextField(
            value = textInput,
            onValueChange = { textInput = it },
            label = { Text("Enter a key") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                saveSharedKey(ctx, "user_key", textInput)
                Toast.makeText(ctx, "Key saved", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Key")
        }

        Button(
            onClick = {
                val serviceIntent = Intent(ctx, OverlayService::class.java)
                ctx.startForegroundService(serviceIntent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Hunting")
        }

        Button(
            onClick = {
                val stopServiceIntent = Intent(ctx, OverlayService::class.java)
                ctx.stopService(stopServiceIntent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop Hunting")
        }
    }
}

@Composable
private fun PatchBootTab(
    openBootPicker: () -> Unit,
    ctx: Context
) {
    val logs = remember { mutableStateListOf<String>() }
    var isPatching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Boot Image Patching", style = MaterialTheme.typography.titleMedium)

                Button(
                    onClick = openBootPicker,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isPatching
                ) {
                    Text("Select Boot Image")
                }

                Button(
                    onClick = {
                        scope.launch {
                            isPatching = true
                            logs.clear()
                            logs.add("🚀 Starting patch process...")
                            try {
                                var key = "yervant7github"
                                val skey = getSharedKey(ctx, "user_key")
                                if (!skey.isNullOrEmpty()) {
                                    key = skey
                                }

                                val result = patchBootImage(ctx, key, logs)

                                if (result) {
                                    logs.add("✅ Patching completed successfully!")
                                    logs.add("   Saving patched_boot.img to Downloads folder...")
                                    copyFileToDownloads(ctx, "patched_boot.img")
                                    logs.add("   File saved successfully.")
                                } else {
                                    logs.add("❌ Patching failed! Check logs for details.")
                                }
                            } finally {
                                isPatching = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isPatching
                ) {
                    if (isPatching) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Start Patching")
                    }
                }
            }
        }

        OutlinedCard(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                reverseLayout = true
            ) {
                items(logs.size) { index ->
                    val log = logs.reversed()[index]
                    val color = when {
                        log.startsWith("✅") -> Color(0xFF4CAF50)
                        log.startsWith("❌") -> Color(0xFFF44336)
                        log.startsWith("$>") -> Color.Gray
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
            }
        }
    }
}

private fun executeShell(cmd: String, logs: SnapshotStateList<String>, allowNonZeroExit: Boolean = false): String {
    logs.add("$> $cmd")

    val stdout: MutableList<String> = ArrayList()
    val stderr: MutableList<String> = ArrayList()

    val result = Shell.cmd(cmd).to(stdout, stderr).exec()

    if (stdout.isNotEmpty()) {
        logs.add(stdout.joinToString("\n"))
    }
    if (stderr.isNotEmpty()) {
        logs.add("stderr: ${stderr.joinToString("\n")}")
    }

    if (result.code != 0 && !allowNonZeroExit) {
        val errorMsg = "Command failed with exit code ${result.code}"
        logs.add("❌ $errorMsg")
        throw ShellCommandException("$errorMsg\n---STDERR---\n${stderr.joinToString("\n")}")
    }

    return stdout.joinToString("\n")
}

private suspend fun patchBootImage(
    context: Context,
    superkey: String,
    logs: SnapshotStateList<String>
): Boolean {
    return withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "patch")
        try {
            executeShell("rm -f ${dir.absolutePath}/*", logs, allowNonZeroExit = true)

            preparePatchEnvironment(context, dir, logs)

            executeShell("cp ${context.filesDir.absolutePath}/boot.img ${dir.absolutePath}/", logs)

            executeShell("cd ${dir.absolutePath} && ${dir.absolutePath}/magiskboot unpack ${dir.absolutePath}/boot.img", logs)

            val checkResult = executeShell("${dir.absolutePath}/kptools -c -i ${dir.absolutePath}/kernel", logs, allowNonZeroExit = true)
            val kpimgver = if (checkResult.contains("is PATCHED.")) {
                logs.add("ℹ️ Kernel already patched. Using kpimg-with-kp.")
                "kpimg-with-kp"
            } else {
                logs.add("ℹ️ Kernel not patched. Using kpimg.")
                "kpimg"
            }

            executeShell("${dir.absolutePath}/kptools -p -i ${dir.absolutePath}/kernel -S \"$superkey\" -k ${dir.absolutePath}/$kpimgver -o ${dir.absolutePath}/new-kernel", logs)
            executeShell("rm ${dir.absolutePath}/kernel", logs)
            executeShell("mv ${dir.absolutePath}/new-kernel ${dir.absolutePath}/kernel", logs)
            executeShell("cd ${dir.absolutePath} && ${dir.absolutePath}/magiskboot repack ${dir.absolutePath}/boot.img", logs)
            executeShell("mv ${dir.absolutePath}/new-boot.img ${dir.absolutePath}/patched_boot.img", logs)

            true
        } catch (e: ShellCommandException) {
            Log.e("PatchBoot", "A shell command failed: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e("PatchBoot", "An unexpected error occurred during patching", e)
            logs.add("❌ Unexpected Error: ${e.message}")
            false
        }
    }
}

private fun copyAssetToFile(context: Context, assetName: String, destFile: File) {
    if (!destFile.exists()) {
        context.assets.open(assetName).use { inputStream ->
            destFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }
}

private fun preparePatchEnvironment(context: Context, dir: File, logs: SnapshotStateList<String>) {
    logs.add("ℹ️ Preparing environment...")
    try {
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val binaries = listOf("magiskboot", "kptools")
        val otherFiles = listOf("kpimg", "kpimg-with-kp")

        (binaries + otherFiles).forEach { fileName ->
            val destFile = File(dir, fileName)
            logs.add("   - Checking $fileName...")
            copyAssetToFile(context, fileName, destFile)

            if (binaries.contains(fileName)) {
                if (destFile.setExecutable(true, false)) {
                    logs.add("     ✓ Set as executable.")
                } else {
                    logs.add("     ⚠️ Failed to set as executable.")
                }
            }
        }
        logs.add("✅ Environment ready.")
    } catch (e: Exception) {
        logs.add("❌ Failed to prepare environment: ${e.message}")
        Log.e("PrepareEnv", "Error preparing patch environment", e)
        throw e
    }
}

fun copyFileToDownloads(context: Context, fileName: String) {
    val dir = File(context.filesDir, "patch")
    val sourceFile = File(dir, fileName)

    if (!sourceFile.exists()) {
        Log.e("CopyToDownloads", "File not found!")
        return
    }

    val resolver = context.contentResolver
    val fileMimeType = "*/*"

    val outputStream: OutputStream?

    val contentValues = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, fileMimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }

    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

    outputStream = uri?.let { resolver.openOutputStream(it) }

    outputStream?.use { output ->
        FileInputStream(sourceFile).use { input ->
            input.copyTo(output)
        }
    }
}

fun saveSharedKey(context: Context, key: String, value: String) {
    val sharedPreferences = context.getSharedPreferences(
        "hg_prefs",
        Context.MODE_PRIVATE
    )
    with(sharedPreferences.edit()) {
        putString(key, value)
        apply()
    }
}

fun getSharedKey(context: Context, key: String): String? {
    val sharedPreferences = context.getSharedPreferences(
        "hg_prefs",
        Context.MODE_PRIVATE
    )
    return sharedPreferences.getString(key, null)
}