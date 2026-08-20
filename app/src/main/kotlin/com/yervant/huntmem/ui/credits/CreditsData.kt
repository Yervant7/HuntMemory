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

import androidx.annotation.StringRes
import com.yervant.huntmem.R

enum class DependencyCategory(@StringRes val titleRes: Int) {
    ALL(R.string.credits_category_all),
    KOTLIN_ANDROID(R.string.credits_category_kotlin_android),
    RUST_CORE(R.string.credits_category_rust_core),
    KERNEL_SYSTEM(R.string.credits_category_kernel_system)
}

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
            version = "3.0.0",
            category = DependencyCategory.KERNEL_SYSTEM,
            license = "GPL-3.0-or-later",
            description = "Android process memory editor, scanner, and interactive floating overlay engine for ARM64 devices.",
            url = "https://github.com/Yervant7/HuntMemory",
            isCoreProject = true
        ),
        DependencyCredit(
            name = "HMKPM (HuntMemory KernelPatch Module)",
            version = "2.0.0",
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
            version = "API 29+ (Linux 4.14+/5.x/6.x)",
            category = DependencyCategory.KERNEL_SYSTEM,
            license = "GPL-2.0 / Apache-2.0",
            description = "Linux kernel procfs memory subsystems (/proc/pid/maps, /proc/pid/pagemap, process_vm_readv, process_vm_writev).",
            url = "https://source.android.com"
        ),

        // Android & Kotlin Dependencies
        DependencyCredit(
            name = "Jetpack Compose",
            version = "2026.08.00 (BOM) / 1.4.0",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Modern declarative UI toolkit for Android, powering the main dashboard and floating overlay interface.",
            url = "https://developer.android.com/jetpack/compose"
        ),
        DependencyCredit(
            name = "Libsu (Core & Service)",
            version = "6.0.0",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Robust Root IPC framework by topjohnwu for executing privileged root processes and managing background services.",
            url = "https://github.com/topjohnwu/libsu"
        ),
        DependencyCredit(
            name = "Kotlinx Coroutines Android",
            version = "1.11.0",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Asynchronous programming library powering background scanning, freeze polling, and non-blocking IO dispatchers.",
            url = "https://github.com/Kotlin/kotlinx.coroutines"
        ),
        DependencyCredit(
            name = "Coil Compose",
            version = "2.7.0",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Fast, lightweight Kotlin-first image loader used for decoding and displaying running target application icons.",
            url = "https://github.com/coil-kt/coil"
        ),
        DependencyCredit(
            name = "AndroidX Activity & Activity Compose",
            version = "1.13.0",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Activity integrations for Jetpack Compose, overlay permissions launcher, and system back-press handling.",
            url = "https://developer.android.com/jetpack/androidx/releases/activity"
        ),
        DependencyCredit(
            name = "AndroidX Lifecycle",
            version = "2.11.0",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "LifecycleService, SavedStateRegistryOwner, and ViewModelStoreOwner bindings for Compose overlay services.",
            url = "https://developer.android.com/jetpack/androidx/releases/lifecycle"
        ),
        DependencyCredit(
            name = "AndroidX Core KTX",
            version = "1.19.0",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Kotlin extensions providing idiomatic wrappers for core Android system frameworks.",
            url = "https://developer.android.com/jetpack/androidx/releases/core"
        ),
        DependencyCredit(
            name = "AndroidX AppCompat",
            version = "1.8.0",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Android support compatibility layer and per-app dynamic locale management.",
            url = "https://developer.android.com/jetpack/androidx/releases/appcompat"
        ),
        DependencyCredit(
            name = "Material Icons Extended",
            version = "1.7.8",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Extended Material Design vector icon catalog for memory search tools, controls, and status indicators.",
            url = "https://developer.android.com/jetpack/androidx/releases/compose-material"
        ),
        DependencyCredit(
            name = "AndroidX Annotation",
            version = "1.10.0",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Metadata annotations for compile-time validation, API level checking, and thread annotations.",
            url = "https://developer.android.com/jetpack/androidx/releases/annotation"
        ),
        DependencyCredit(
            name = "Kotlin Standard Library",
            version = "2.4.0",
            category = DependencyCategory.KOTLIN_ANDROID,
            license = "Apache-2.0",
            description = "Official Kotlin 2.x standard library runtime and collections primitives.",
            url = "https://kotlinlang.org"
        ),

        // Rust Core Dependencies (app/src/main/hmem/hmem_jni)
        DependencyCredit(
            name = "hmem_jni (Rust Native Core)",
            version = "0.1.0",
            category = DependencyCategory.RUST_CORE,
            license = "GPL-3.0-or-later",
            description = "Native ARM64 Rust 2024 core library implementing SIMD memory scanning, maps filtering, and JNI bridges.",
            url = "https://github.com/Yervant7/HuntMemory",
            isCoreProject = true
        ),
        DependencyCredit(
            name = "jni crate",
            version = "0.22.4",
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "Type-safe Rust bindings to the Java Native Interface (JNI) for high-speed cross-boundary calls.",
            url = "https://crates.io/crates/jni"
        ),
        DependencyCredit(
            name = "libc crate",
            version = "0.2.189",
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "Raw Rust FFI bindings for Linux and Android bionic C library system calls and data structures.",
            url = "https://crates.io/crates/libc"
        ),
        DependencyCredit(
            name = "serde & serde_derive",
            version = "1.0.229",
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "High-performance zero-copy serialization and deserialization framework for Rust.",
            url = "https://crates.io/crates/serde"
        ),
        DependencyCredit(
            name = "serde_json",
            version = "1.0.151",
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "Fast JSON parser and serializer for exchanging complex memory scan metadata across JNI boundaries.",
            url = "https://crates.io/crates/serde_json"
        ),
        DependencyCredit(
            name = "memchr",
            version = "2.8.3",
            category = DependencyCategory.RUST_CORE,
            license = "Unlicense OR MIT",
            description = "Heavily optimized byte and substring search routines utilizing ARM64 NEON vector extensions.",
            url = "https://crates.io/crates/memchr"
        ),
        DependencyCredit(
            name = "combine",
            version = "4.6.7",
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "Fast parser combinator library used for parsing native signatures and type descriptors.",
            url = "https://crates.io/crates/combine"
        ),
        DependencyCredit(
            name = "simd_cesu8 & simdutf8",
            version = "1.2.0 / 0.1.5",
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "SIMD-accelerated CESU-8 and UTF-8 string decoding and validation library for JNI strings.",
            url = "https://crates.io/crates/simd_cesu8"
        ),
        DependencyCredit(
            name = "thiserror",
            version = "2.0.20",
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "Custom derive macro for convenient and idiomatic Rust error type implementations.",
            url = "https://crates.io/crates/thiserror"
        ),
        DependencyCredit(
            name = "walkdir",
            version = "2.5.0",
            category = DependencyCategory.RUST_CORE,
            license = "Unlicense OR MIT",
            description = "Efficient recursive directory walker used for inspecting Android process structures.",
            url = "https://crates.io/crates/walkdir"
        ),
        DependencyCredit(
            name = "itoa",
            version = "1.0.18",
            category = DependencyCategory.RUST_CORE,
            license = "MIT OR Apache-2.0",
            description = "Ultra-fast integer to string formatting primitives avoiding heap allocations.",
            url = "https://crates.io/crates/itoa"
        )
    )
}
