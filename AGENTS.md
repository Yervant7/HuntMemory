# AGENTS.md

## Project Overview

Hobby/testing Android application: **Process Memory Editor & Scanner**.
Target platform: Android 10+ (API 29+), exclusively **ARM64** (`arm64-v8a` / `aarch64-linux-android`).

Architecture: **Kotlin + Jetpack Compose** (App UI / Overlay) → **libsu RootService** (Root IPC) → **JNI** → **Rust Core** (`hmem_jni`) → **HMKPM** (KernelPatch Module via `SYS_GETRESUID`) running in the patched kernel for virtual/physical memory operations.

---

## Language & Localization Policy

- **Codebase Language**: English is mandatory for all source code, variable/function/class names, code comments, documentation, commit messages, logging, and agent prompts/transcripts.
- **Other Languages**: Non-English languages (e.g., Portuguese, Spanish, German, French, Hindi, Japanese, Korean, Russian, Chinese) are **strictly restricted** to Android localization string resources (`app/src/main/res/values-*/strings.xml`).

---

## Modernization & Stability Policy

- **Stay Modern**: Keep the project up to date with modern Android development standards, Gradle version catalog practices, Kotlin 2.x paradigms, Compose declarative UI, and Rust 2024 idioms.
- **Prioritize Stability**: Always prefer **stable, production-ready** dependencies, toolchains, APIs, and libraries over experimental, alpha, or nightly releases unless explicitly requested. Avoid deprecated APIs and replace them with their recommended stable successors.

---

## Stack & Target Specifications (Non-Negotiable)

- **Target SDKs**: `minSdk = 29` (Android 10), `targetSdk = 37`, `compileSdk = 37`. Any SDK API above 29 requires explicit `Build.VERSION.SDK_INT` runtime guards or compatible fallbacks.
- **Toolchain**: Gradle 9.x, Kotlin 2.x (with official Compose compiler plugin), Java 21 LTS, Rust Edition 2024 (rustc 1.90+), NDK 29+.
- **Single ABI**: Exclusively `arm64-v8a` (`aarch64-linux-android`). Do not add `armeabi-v7a`, `x86`, or `x86_64` support unless explicitly requested for isolated emulator development builds.
- **Pointers & Struct Layouts**: Always 64-bit. For any struct crossing the Kotlin ↔ Rust / JNI boundary, use fixed-width primitive types (`u32`, `u64`, `i64`, `f32`, `f64`) rather than `usize`/`isize`, and mark with `#[repr(C)]`. Any layout change must be updated and documented synchronously on both ends.

---

## Kotlin & Android Guidelines

- **Threading & Coroutines**: Heavy operations (memory scanning, pagemap queries, freeze polling, process filtering) must never run on the UI/Main thread. Use Kotlin Coroutines with dedicated dispatchers (`Dispatchers.IO` or `Dispatchers.Default`).
- **Error Handling**: Native errors must return typed results (`Result<T>`, sealed classes, or well-defined error codes), never silent crashes or ambiguous magic numbers.
- **Overlay & Service Lifecycle**: Compose overlays running inside background services must strictly follow Android lifecycle contracts (`LifecycleService`, `SavedStateRegistryOwner`, `ViewModelStoreOwner`, and foreground service policies with `specialUse` type).
- **JNI Contracts**: Every `external fun` exposed in `NativeBridge` must have a documented contract covering nullability, buffer ownership, value encoding, and lifecycle.

---

## Rust Core & JNI Guidelines

- **JNI Safety & Exceptions**: Never allow a Rust `panic!` to cross the JNI boundary. Wrap every JNI export in `std::panic::catch_unwind` and return a safe fallback value.
- **JNIEnv & Parameters**: Extract all JNI parameters (`JString`, primitives) prior to `catch_unwind` closures. Avoid borrowing or cloning `JNIEnv` across unwinding boundaries. In `jni 0.22+`, treat `jboolean` as `bool`.
- **Memory Safety & Unsafe**: Minimize `unsafe` blocks. Every `unsafe` block must include a comment explaining the invariant that guarantees safety. Validate all addresses and buffer sizes before reading or writing.
- **SIMD Vectorization**: Vectorized buffer comparisons (fast scanning) must use ARM64 NEON intrinsics via `std::arch::aarch64`, always behind `#[cfg(target_arch = "aarch64")]`. Never suggest SSE/AVX.

---

## Development Workflow & "Definition of Done"

- **Research First**: Inspect relevant files before editing. If critical requirements are ambiguous, clarify before assuming.
- **Scoped Changes**: Keep edits small, focused, and clean. Do not perform unrelated refactoring.
- **Definition of Done**:
  1. Rust compiles cleanly for `aarch64-linux-android` (`cargo ndk -t arm64-v8a build --release`).
  2. Kotlin compiles cleanly (`.\gradlew compileDebugKotlin assembleDebug`).
  3. No new compiler, linter, or Clippy warnings are introduced.
