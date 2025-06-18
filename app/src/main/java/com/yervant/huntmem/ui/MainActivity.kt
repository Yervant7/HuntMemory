package com.yervant.huntmem.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import androidx.core.net.toUri
import com.yervant.huntmem.ui.theme.HuntMemTheme


class MainActivity : ComponentActivity() {

    private val getContentBoot = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleBootImport(it) }
    }

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleFileImport(it) }
    }

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (Settings.canDrawOverlays(this)) {
                showMainScreen()
            } else {
                Toast.makeText(this, "Permission to display overlay denied.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }

        if (!suCheck()) {
            Toast.makeText(this, "ERROR Root access missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val dir = File(filesDir, "patch")
        if (dir.isDirectory) {
            executeSuCommand("rm -rf ${dir.absolutePath}/*")
        } else {
            dir.mkdirs()
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            showMainScreen()
        }
    }

    private fun showMainScreen() {
        setContent {
            HuntMemTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        openBootPicker = { getContentBoot.launch("*/*") },
                        openFilePicker = { getContent.launch("*/*") }
                    )
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }

    private fun suCheck(): Boolean {
        val output = executeSuCommand("id")
        if (output.isEmpty()) {
            Log.d("MainActivity", "APP Not Have Root Access")
        } else if (output[0].startsWith("uid=0")) {
            Log.d("MainActivity", "APP Have Root Access")
            return true
        } else {
            Log.d("MainActivity", "APP Not Have Root Access")
        }
        return false
    }

    private fun executeSuCommand(command: String): List<String> {
        val output = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    output.add(line)
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return output
    }

    private fun handleBootImport(uri: Uri) {
        val fileName = getFileName(uri)
        if (fileName.endsWith(".img")) {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            inputStream?.let {
                val file = File(filesDir, "boot.img")
                if (file.exists()) {
                    file.delete()
                }
                val outputStream = FileOutputStream(file)
                it.copyTo(outputStream)
                outputStream.close()
                it.close()
            }
        } else {
            Toast.makeText(this@MainActivity, "Only import files .img", Toast.LENGTH_SHORT).show()
            throw Exception("not a img file")
        }
    }

    private fun handleFileImport(uri: Uri) {
        val fileName = getFileName(uri)
        if (fileName.endsWith(".lua")) {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            inputStream?.let {
                val folder = File(filesDir, "scripts")
                if (!folder.exists()) {
                    folder.mkdirs()
                }
                val file = File(folder, fileName)
                val outputStream = FileOutputStream(file)
                it.copyTo(outputStream)
                outputStream.close()
                it.close()
            }
        } else {
            Toast.makeText(this@MainActivity, "Only import files .lua", Toast.LENGTH_SHORT).show()
            throw Exception("not a lua file")
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "unknown"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
        return name
    }
}