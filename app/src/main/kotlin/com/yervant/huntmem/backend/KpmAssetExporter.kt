/*
 * HuntMemory - Process Memory Editor & Scanner for Android
 * Copyright (C) 2026 Yervant7
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yervant.huntmem.backend

import android.content.Context
import android.net.Uri
import com.yervant.huntmem.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Exports the bundled HMKPM module asset from the APK to a user-selected
 * Storage Access Framework destination URI on device internal storage.
 */
object KpmAssetExporter {

    /**
     * Copies the bundled HMKPM asset (`BuildConfig.HMKPM_ASSET_NAME`) to [destinationUri].
     * Returns the number of bytes written on success.
     */
    suspend fun exportBundledKpm(context: Context, destinationUri: Uri): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val appContext = context.applicationContext
                val outputStream = appContext.contentResolver.openOutputStream(destinationUri)
                    ?: throw IOException("Unable to open destination URI for writing")

                val bytesWritten = appContext.assets.open(BuildConfig.HMKPM_ASSET_NAME).use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }

                if (bytesWritten <= 0L) {
                    throw IOException("Exported HMKPM asset is empty")
                }
                bytesWritten
            }
        }
}
