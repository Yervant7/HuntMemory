# AGENTS.md — HuntMemory (HMem)

## 1. Project Overview & Architecture

**HuntMemory (HMem)** is an advanced, high-performance process memory editor and scanner designed exclusively for **Android 10+ (API 29+) on ARM64 (`arm64-v8a` / `aarch64-linux-android`)**.

### Multi-Layer Security & Execution Pipeline:
```text
┌─────────────────────────────────────────────────────────────────────────┐
│ UI Layer (App Process)                                                  │
│ - Jetpack Compose Floating Overlay UI                                   │
│ - Virtual Keyboards (QWERTY / Numeric / Hexadecimal)                   │
│ - Lua 5.4 Engine + LuaCanvasOverlay (DrawScope GPU Rendering / gg.* API)│
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ AIDL / IPC (libsu)
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Root Space (UID 0 - HMemService via libsu RootService)                  │
│ - AIDL Service & Lifecycle Manager                                      │
│ - JNI NativeBridge (@FastNative / @CriticalNative)                      │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ JNI (C ABI)
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Native Engine (hmem_jni - Rust Edition 2024)                            │
│ - ARM NEON SIMD Vectorized Scanning Engine                              │
│ - Zero-Copy Ringbuffers, Chunked Iterators, Freeze Loop Manager        │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ SYS_GETRESUID / SuperCall
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Kernel Space (HMKPM - KernelPatch Module)                               │
│ - Direct MMU Translation, Page Table Walkers & Kernel Memory Operations │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Stack & Target Specifications (Non-Negotiable)

* **Host Platform:** Windows 11.
* **Target Platforms:** Physical 64-bit ARM devices (`arm64-v8a` / `aarch64-linux-android`).
* **Android Target:** `minSdk = 29` (Android 10), `targetSdk = 37`, `compileSdk = 37`.
* **Toolchains:** Gradle 9.x, Kotlin 2.x (official Compose compiler), Java 21 LTS, Rust Edition 2024 (`rustc 1.90+`), Android NDK 29+.
* **Language Rules:** **Kotlin, Rust 2024, and pure C only. DO NOT use or generate C++.**
* **Codebase Language:** All code, comments, documentation, and commit messages must be strictly in **English**. Non-English strings belong exclusively in `res/values-*/strings.xml`.

---

## 3. Rust Engine (`hmem_jni`) & SIMD Scanning Standards

### A. ARM NEON SIMD Vectorization
* All high-throughput memory scanning routines must use ARM NEON intrinsics (`core::arch::aarch64::*`) guarded by `#[cfg(target_arch = "aarch64")]`.
* Process memory in 16-byte/32-byte chunks with pre-allocated zero-copy buffers. Never allocate inside hot scan loops.

### B. Unsafe Invariants & Struct Layouts
* **Mandatory `// SAFETY:` Comments:** Every single `unsafe` block or function must explicitly document pointer validity, alignment, lifetime bounds, and alias guarantees.
* **ABI Layouts:** Structs crossing JNI or IPC boundaries must be marked with `#[repr(C)]` or `#[repr(packed)]` and use fixed-width types (`u32`, `u64`, `i64`, `f32`, `f64`). Never use `usize`/`isize` across FFI boundaries.
* **No UB:** Use `std::mem::MaybeUninit` for uninitialized memory. Never use deprecated uninitialized memory factories.

### C. JNI Safety & Bridge Rigor
* **Panic Boundary:** Wrap every single JNI export in `std::panic::catch_unwind` to prevent panics from unwinding across the FFI boundary.
* **Reference Management:** Explicitly release local references (`env.delete_local_ref()`) and primitive critical arrays (`ReleasePrimitiveArrayCritical`) inside loops.
* **Thread Boundary:** Never pass or cache `JNIEnv` pointers across thread boundaries.

---

## 4. Kernel Interface & HMKPM Communication

* **HMKPM Syscall Protocol:** All kernel memory reads, writes, and batch translations must route through the `SYS_GETRESUID` syscall hook using the verified magic key (`HMKPM_MAGIC = 0x00484D4B504D`).
* **Pagemap Optimization:** Read `/proc/[pid]/pagemap` (`PM_PRESENT` bit 63 / `PM_SWAP` bit 62) to filter out uncommitted anonymous pages and accelerate scans without blind zero fallbacks.
* **No Deprecated User-space Fallbacks:** Do NOT use `/proc/[pid]/mem`, `process_vm_readv`/`process_vm_writev`, `ptrace`, or `/data/local/tmp` binary drops.
* **0% SELinux Touch:** Preserve SELinux integrity. Rely exclusively on kernel-level memory manipulation.

---

## 5. Kotlin, Jetpack Compose & Service Guidelines

* **Threading & Coroutines:** Heavy tasks (memory scans, pagemap parsing, freeze polling, process listing) must run exclusively on `Dispatchers.IO` or `Dispatchers.Default`, never on the Main/UI thread.
* **Service Lifecycles:** Overlay services must strictly satisfy foreground service requirements (`LifecycleService`, `SavedStateRegistryOwner`, `ViewModelStoreOwner`, foreground service type `specialUse`).
* **Lua 5.4 & Canvas Integration:** Lua script execution must run asynchronously, dispatching drawing commands to `LuaCanvasOverlay` using Compose `DrawScope`.

---

## 6. Project Agent Skills (`.agents/skills`)

* **`rust-skills`**: Comprehensive Rust 2024 coding, ARM NEON SIMD, safety invariants, memory optimization, and Clippy guidelines.
* **`r8-analyzer`**: Android R8 / Proguard configuration analyzer and keep rule optimizer.
* **`compose-performance`**: Jetpack Compose recomposition diagnostics, state stability, and deferred state reads.
* **`kotlin-concurrency-and-flow`**: Structured concurrency, asynchronous flow pipelines, lifecycle scopes, and cancellation safety.
* **`android-profiler`**: Android Profiler integration, CPU/memory profiling, and trace analysis.
* **`android-intent-security`**: Android component security, IPC surfaces, permission verification, and PendingIntent safeguards.
* **`find-skills`**: Meta-skill package manager for agent ecosystem discovery (`npx skills`).

---

## 7. Definition of Done (DoD) & Verification Loop

Before concluding any implementation task:
1. **Rust Core Build:** `cargo ndk -t arm64-v8a build --release` (executed in `app/src/main/hmem`) compiles with zero warnings.
2. **Clippy Check:** `cargo clippy --target aarch64-linux-android -- -D warnings` (executed in `app/src/main/hmem`) passes cleanly.
3. **Android Build:** `.\gradlew compileDebugKotlin assembleDebug` succeeds on Windows Host.
4. **Surgical Diffs:** No unnecessary file reformats or unverified abstractions introduced.