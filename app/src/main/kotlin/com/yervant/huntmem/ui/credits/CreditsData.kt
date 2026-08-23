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

package com.yervant.huntmem.ui.credits

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.yervant.huntmem.BuildConfig
import com.yervant.huntmem.R

@Immutable
enum class DependencyCategory(@StringRes val titleRes: Int) {
    ALL(R.string.credits_category_all),
    KOTLIN_ANDROID(R.string.credits_category_kotlin_android),
    RUST_CORE(R.string.credits_category_rust_core),
    KERNEL_SYSTEM(R.string.credits_category_kernel_system)
}

@Immutable
data class DependencyCredit(
    val name: String,
    val version: String,
    val category: DependencyCategory,
    val license: String,
    val description: String,
    val url: String,
    val isCoreProject: Boolean = false
)

object CreditsRepository {
    val dependencies: List<DependencyCredit> = listOf(
        // Core Project & Architecture
        DependencyCredit(
            name = "HuntMemory",
            version = BuildConfig.VERSION_NAME,
            category = DependencyCategory.KERNEL_SYSTEM,
            license = "GPL-3.0-or-later",
            description = "Android process memory editor, scanner, and interactive floating overlay engine for ARM64 devices.",
            url = "https://github.com/Yervant7/HuntMemory",
            isCoreProject = true
        ),
        DependencyCredit(
            name = "HMKPM (HuntMemory KernelPatch Module)",
            version = "2.x.x",
            category = DependencyCategory.KERNEL_SYSTEM,
            license = "GPL-2.0-only",
            description = "KernelPatch module hooked via SYS_GETRESUID for high-speed direct kernel virtual and physical memory read/write operations.",
            url = "https://github.com/Yervant7/HuntMemory-KPM",
            isCoreProject = true
        ),
        DependencyCredit(
            name = "KernelPatch",
            version = "0.11+",
            category = DependencyCategory.KERNEL_SYSTEM,
            license = "GPL-2.0-only",
            description = "Universal Android kernel-space patching framework for ARM64 kernel runtime manipulation and module hooking.",
            url = "https://github.com/bmax121/KernelPatch"
        ),
        DependencyCredit(
            name = "Linux Kernel & AOSP Memory APIs",
            version = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / Linux ${System.getProperty("os.version") ?: "4.4+"}",
            category = DependencyCategory.KERNEL_SYSTEM,
            license = "GPL-2.0 / Apache-2.0",
            description = "Linux kernel procfs memory subsystems (/proc/pid/maps, /proc/pid/pagemap).",
            url = "https://source.android.com"
        ),

        // Android & Kotlin Dependencies
        DependencyCredit(
            name = "Jetpack Compose",
            version = "${BuildConfig.DEP_VERSION_COMPOSE_BOM} (BOM) / ${BuildConfig.DEP_VERSION_MATERIAL3}",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Modern declarative UI toolkit for Android, powering the main dashboard and floating overlay interface.",
            url = "https://developer.android.com/jetpack/compose"
        ),
        DependencyCredit(
            name = "Libsu (Core & Service)",
            version = BuildConfig.DEP_VERSION_LIBSU,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Robust Root IPC framework by topjohnwu for executing privileged root processes and managing background services.",
            url = "https://github.com/topjohnwu/libsu"
        ),
        DependencyCredit(
            name = "Kotlinx Coroutines Android",
            version = BuildConfig.DEP_VERSION_COROUTINES,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Asynchronous programming library powering background scanning, freeze polling, and non-blocking IO dispatchers.",
            url = "https://github.com/Kotlin/kotlinx.coroutines"
        ),
        DependencyCredit(
            name = "Coil Compose",
            version = BuildConfig.DEP_VERSION_COIL,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Fast, lightweight Kotlin-first image loader used for decoding and displaying running target application icons.",
            url = "https://github.com/coil-kt/coil"
        ),
        DependencyCredit(
            name = "AndroidX Activity & Activity Compose",
            version = BuildConfig.DEP_VERSION_ACTIVITY_COMPOSE,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Activity integrations for Jetpack Compose, overlay permissions launcher, and system back-press handling.",
            url = "https://developer.android.com/jetpack/androidx/releases/activity"
        ),
        DependencyCredit(
            name = "AndroidX Lifecycle",
            version = BuildConfig.DEP_VERSION_LIFECYCLE,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "LifecycleService, SavedStateRegistryOwner, and ViewModelStoreOwner bindings for Compose overlay services.",
            url = "https://developer.android.com/jetpack/androidx/releases/lifecycle"
        ),
        DependencyCredit(
            name = "AndroidX Core KTX",
            version = BuildConfig.DEP_VERSION_CORE_KTX,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Kotlin extensions providing idiomatic wrappers for core Android system frameworks.",
            url = "https://developer.android.com/jetpack/androidx/releases/core"
        ),
        DependencyCredit(
            name = "AndroidX AppCompat",
            version = BuildConfig.DEP_VERSION_APPCOMPAT,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Android support compatibility layer and per-app dynamic locale management.",
            url = "https://developer.android.com/jetpack/androidx/releases/appcompat"
        ),
        DependencyCredit(
            name = "Material Icons Extended",
            version = BuildConfig.DEP_VERSION_MATERIAL_ICONS,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Extended Material Design vector icon catalog for memory search tools, controls, and status indicators.",
            url = "https://developer.android.com/jetpack/androidx/releases/compose-material"
        ),
        DependencyCredit(
            name = "AndroidX Annotation",
            version = BuildConfig.DEP_VERSION_ANNOTATION,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Metadata annotations for compile-time validation, API level checking, and thread annotations.",
            url = "https://developer.android.com/jetpack/androidx/releases/annotation"
        ),
        DependencyCredit(
            name = "Kotlin Standard Library",
            version = KotlinVersion.CURRENT.toString(),
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Official Kotlin 2.x standard library runtime and collections primitives.",
            url = "https://kotlinlang.org"
        ),
        DependencyCredit(
            name = "Kotlinx Serialization JSON",
            version = BuildConfig.DEP_VERSION_SERIALIZATION,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Cross-platform Kotlin multiplatform serialization library for type-safe route handling and JSON parsing.",
            url = "https://github.com/Kotlin/kotlinx.serialization"
        ),
        DependencyCredit(
            name = "Android Gradle Plugin",
            version = BuildConfig.DEP_VERSION_AGP,
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Official build system plugin for Android applications using modern Gradle 9.x toolchain.",
            url = "https://developer.android.com/build"
        ),

        // Rust Core Dependencies (app/src/main/hmem/hmem_jni)
        DependencyCredit(
            name = "hmem_jni (Rust Native Core)",
            version = BuildConfig.RUST_VERSION_HMEM_JNI,
            category = DependencyCategory.RUST_CORE,
            license = "GPL-3.0-or-later",
            description = "Native ARM64 Rust 2024 core library implementing SIMD memory scanning, maps filtering, and JNI bridges.",
            url = "https://github.com/Yervant7/HuntMemory",
            isCoreProject = true
        ),
        DependencyCredit(
            name = "jni crate",
            version = BuildConfig.RUST_VERSION_JNI,
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "Type-safe Rust bindings to the Java Native Interface (JNI) for high-speed cross-boundary calls.",
            url = "https://crates.io/crates/jni"
        ),
        DependencyCredit(
            name = "libc crate",
            version = BuildConfig.RUST_VERSION_LIBC,
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "Raw Rust FFI bindings for Linux and Android bionic C library system calls and data structures.",
            url = "https://crates.io/crates/libc"
        ),
        DependencyCredit(
            name = "mlua crate",
            version = BuildConfig.RUST_VERSION_MLUA,
            category = DependencyCategory.RUST_CORE,
            license = "MIT",
            description = "Safe, high-level Lua 5.4 bindings for Rust powering memory search automation and custom scripting engine.",
            url = "https://crates.io/crates/mlua"
        ),
        DependencyCredit(
            name = "serde & serde_derive",
            version = BuildConfig.RUST_VERSION_SERDE,
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "High-performance zero-copy serialization and deserialization framework for Rust.",
            url = "https://crates.io/crates/serde"
        ),
        DependencyCredit(
            name = "serde_json",
            version = BuildConfig.RUST_VERSION_SERDE_JSON,
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "Fast JSON parser and serializer for exchanging complex memory scan metadata across JNI boundaries.",
            url = "https://crates.io/crates/serde_json"
        ),
    )
}
