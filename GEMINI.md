# GEMINI.md — HuntMemory (HMem) Architecture & Engineering Context

> Complements `AGENTS.md` with current repository architecture, end-to-end data flows, explicit runtime/environment constraints, legacy deprecation policies, and the prioritized evolution roadmap.

---

## 1. Project Overview & Tech Stack

**HuntMemory (HMem)** (`com.yervant.huntmem`, `versionName = "4.0.0"`, `versionCode = 400`) is an ARM64-only (`arm64-v8a` / `aarch64-linux-android`) process memory scanner, editor, and Lua 5.4 scripting overlay for **Android 10+ (API 29+)**. It pairs a **Kotlin + Jetpack Compose** presentation layer with a **Rust Edition 2024** native engine (`libhmem_jni.so`) running inside a `libsu` `UID 0` daemon, interfacing with kernel space via the external **[HuntMemory-KPM (HMKPM)](https://github.com/Yervant7/HuntMemory-KPM)** KernelPatch module.

### Build & Dependency Versions

| Layer | Component / Tool | Version / Spec | Source |
| :--- | :--- | :--- | :--- |
| **Build** | Gradle Wrapper | `9.9.1` | `gradle/wrapper/gradle-wrapper.properties` |
| **Build** | AGP / Kotlin / Java | AGP `9.5.1`, Kotlin `2.4.21`, Java `21` | `gradle/libs.versions.toml`, `app/build.gradle.kts` |
| **Android** | SDK / NDK | `minSdk 29`, `compileSdk 37` (minor `1`), `targetSdk 37`, NDK `29.0.14206865` | `app/build.gradle.kts` |
| **UI** | Compose BOM / Material 3 | BOM `2026.09.00`, Material 3 `1.4.0`, Coil `2.7.0` | `gradle/libs.versions.toml` |
| **Root / Async** | `libsu` / Coroutines / Serialization | `libsu` `6.0.0`, Coroutines `1.11.0`, Serialization `1.13.0` | `gradle/libs.versions.toml` |
| **Signing** | `lsplugin.apksign` | `1.5` (falls back to debug signing when keystore properties are unset) | `app/build.gradle.kts` |
| **Native** | Rust (`hmem_jni` `0.2.0`) | Edition `2024`, `cdylib`, `aarch64-linux-android` default target in `.cargo/config.toml` | `app/src/main/hmem/` |
| **Native Crates** | `jni`, `libc`, `mlua`, `serde`, `serde_json` | `jni 0.22`, `libc 0.2`, `mlua 0.12` (`lua54`, `vendored`, `send`, `serialize`), `serde 1`, `serde_json 1` | `app/src/main/hmem/hmem_jni/Cargo.toml` |

---

## 2. Directory Structure

```text
HuntMemory/
├── AGENTS.md                        # Mandatory engineering standards & DoD commands
├── GEMINI.md                        # Architecture, flows, implicit constraints & roadmap (this file)
├── README.md                        # User-facing overview
├── build.gradle.kts                 # Root plugin declarations
├── settings.gradle.kts              # Single module (:app); Google, MavenCentral, JitPack repos
├── gradle/libs.versions.toml        # Version catalog
├── docs/                            # MkDocs technical documentation
├── .github/workflows/               # CI, GitHub Pages, and release workflows
└── app/
    ├── build.gradle.kts             # Android config + Rust Gradle tasks (fmtRust, buildRust*, copyRust*, cleanRust)
    ├── proguard-rules.pro           # R8 keep rules for JNI, AIDL stubs, and LuaUiBridge
    └── src/main/
        ├── AndroidManifest.xml      # Permissions, Activities, OverlayService (specialUse)
        ├── aidl/com/yervant/huntmem/
        │   ├── IHMemService.aidl    # App -> Root daemon Binder contract
        │   └── ILuaUiCallback.aidl  # Root daemon (Lua) -> App UI Binder callback contract
        ├── hmem/                    # Rust Cargo workspace
        │   ├── Cargo.toml           # Workspace & release profile (opt-level=3, thin LTO, panic="unwind")
        │   ├── .cargo/config.toml   # Default target aarch64-linux-android & linker strip flags
        │   ├── build.rs             # Emits warning when targeting non-aarch64-linux-android builds
        │   ├── justfile             # Manual cargo-ndk & clippy recipes
        │   └── hmem_jni/src/        # Rust JNI + scanning/editing/scripting modules
        └── kotlin/com/yervant/huntmem/
            ├── HuntMemApp.kt        # HuntMemApplication: libsu Shell setup, eager RootService bind, SharedPreferences ("hconfig")
            ├── backend/             # Binder client/server, JNI bridge, scan managers, repositories
            └── ui/                  # Activities, OverlayService, Compose tabs, custom VirtualKeyboard, OEM compat
```

---

## 3. Implicit Technical & Environment Constraints

These constraints are binding even where not explicitly enforced by compiler errors or legacy docs:

1. **HMKPM Lockstep Versioning (`2.8.0+`):**
   - HuntMemory evolves in **lockstep** with the external `HuntMemory-KPM` repository and always assumes the latest HMKPM release (`2.8.0+`).
   - Do **not** add or preserve legacy compatibility shims for older `2.x` KPM modules unless explicitly requested.
   - Any change to `Hmkpm*` C-ABI structs or magic numbers in `app/src/main/hmem/hmem_jni/src/kpm.rs` must stay synchronized with `HuntMemory-KPM`.

2. **HMKPM Lockless Mode (Active Runtime Requirement):**
   - Lockless Mode is a **live runtime condition** of HMKPM `2.8.0+` on kernels where `mmap_lock` cannot be resolved (`is_lockless != 0`, `HMKPM_FEATURE_LOCKLESS`, `mmap_lock_offset < 0`, or `EOPNOTSUPP`/errno `95` on writes).
   - The app must continue to operate cleanly in **read-only mode** (allowing scans and reads while blocking/signaling writes and freezes in the UI and backend) whenever Lockless Mode is active.

3. **Zero-FD Kernel Channel & SELinux Integrity Invariants:**
   - **Zero procfs memory/pagemap FDs:** Never open `/proc/<pid>/pagemap` or `/proc/<pid>/mem` (page residency must use `v2p.rs` via `HMKPM_MAGIC_V2P_BATCH`).
   - **No legacy debugging primitives:** Never use `ptrace`, `process_vm_readv`/`process_vm_writev`, or drop helper binaries in `/data/local/tmp`.
   - **SELinux Integrity:** Do not alter SELinux policies or enforcement mode; keep kernel memory access strictly inside the `SYS_GETRESUID` HMKPM channel.
   - Note: `v2p.rs` (`HMKPM_MAGIC_V2P_BATCH`) supersedes older `/proc/[pid]/pagemap` mentions in `AGENTS.md`, `README.md`, and `docs/`.

4. **Cross-Compile Production Builds & Host Unit Testing:**
   - Production builds and ARM64 lints are **cross-compiled for `aarch64-linux-android`** (`cargo ndk -t arm64-v8a build [--release]` and `cargo ndk -t arm64-v8a clippy -- -D warnings`).
   - Because `.cargo/config.toml` sets `target = "aarch64-linux-android"`, host Rust unit tests (`types`, `maps`, scalar `scanner`, `script`, `logger`) must pass `--target <host-triple>` (e.g. `cargo test --target x86_64-unknown-linux-gnu --all-targets` in CI or `cargo test --target x86_64-pc-windows-msvc --all-targets` on Windows). Real ARM64 NEON, HMKPM syscall, and JNI integration verification is performed on a **physical ARM64 Android device**.

5. **Lightweight Android Stack (No Heavy DI / Persistence Frameworks):**
   - Do **not** introduce Hilt, Koin, Room, or DataStore during stabilization/refactoring.
   - Keep dependency weight minimal: manage UI/domain state with Kotlin `StateFlow`-backed repositories and ViewModels scoped to `OverlayService`, and store lightweight settings in the existing `SharedPreferences("hconfig")`.

---

## 4. Current Architecture & Privilege Boundaries

HuntMemory isolates execution across **four privilege domains**:

### Layer 1: App Process (Userspace — App UID)
- **Lifecycle & Startup (`HuntMemApp.kt`, `DisclaimerActivity.kt`, `MainActivity.kt`):**
  - `HuntMemApplication` configures `libsu` `Shell`, eagerly binds `HMemServiceConnection`, and initializes `SharedPreferences("hconfig")`.
  - `DisclaimerActivity` (launcher) enforces one-time disclaimer acceptance (`"disclaimer_accepted"`) before routing to `MainActivity`.
  - `MainActivity` (`AppCompatActivity`) initializes `LocaleManager`, checks notification/overlay/OEM permissions (`OemCompatibilityHelper`), and renders `MainScreen` / `CreditsScreen` via `AppRoute`.
- **Floating Overlay (`OverlayService.kt`, `OverlayUI.kt`, `LuaCanvasOverlay.kt`):**
  - `OverlayService` (`LifecycleService`, `ViewModelStoreOwner`, `SavedStateRegistryOwner`, `DialogCallback`, `foregroundServiceType="specialUse"`) attaches three `WindowManager` `ComposeView` windows (`TYPE_APPLICATION_OVERLAY`):
    1. `canvasView` — `LuaCanvasOverlay` (`FLAG_NOT_TOUCHABLE` full-screen transparent GPU `DrawScope` overlay).
    2. `menuView` — `MenuOverlayContent` hosting 5 tabs (`ProcessScreen`, `MemoryScanTab`, `AddressTableTab`, `LuaScriptTab`, `HuntSettings`), custom modal dialogs, and `VirtualKeyboard`.
    3. `iconView` — draggable `FloatingIcon` bubble toggling `menuView`.
  - `LuaScriptImportActivity` provides a translucent SAF `OpenDocument` bridge to import `.lua`/`.txt` files into `context.filesDir/scripts`.
- **Critical Boundary Rule:** `libhmem_jni.so` is **never** loaded in the app process; all native operations route through `HMemServiceConnection.service` (`IHMemService`).

### Layer 2: Root Service Process (Userspace — UID 0 via `libsu`)
- `HMemService` (`RootService`) receives `NATIVE_LIB_DIR` from `HMemServiceConnection.bind(...)`, loads `libhmem_jni.so` via `NativeBridge.loadLibrary(this, customLibDir)`, and exposes `IHMemService.Stub` forwarding directly to `NativeBridge.native*`.
- Process enumeration (`ShellProcessProvider`) runs `ps -e -o uid,pid,rss,cmdline` via `libsu` `Shell.cmd(...)` and filters `uid >= 10000`.

### Layer 3: Native Engine (`app/src/main/hmem/hmem_jni`, Rust 2024 `cdylib`)
- **`lib.rs`:** `JNI_OnLoad` installs the Android Logcat panic hook; exports JNI entry points guarded by `std::panic::catch_unwind`; manages `SESSIONS: OnceLock<Mutex<HashMap<String, ScanSession>>>`.
- **`kpm.rs`:** HMKPM v2.8.0 syscall client (`SYS_GETRESUID = 148`, `HMKPM_MAGIC = 0x0048_4D4B_504D` .. `+6`), compile-time `size_of`/`align_of` assertions, `AlignedStackBuf` (`<= 256` bytes), `AlignedHeapBuf` (8-byte aligned `Vec<u64>` backing), and static `CACHED_VERSION`.
- **`v2p.rs`:** Zero-FD MMU page residency inspector (`V2pReader`, 4096-page batches via `HMKPM_MAGIC_V2P_BATCH`) returning contiguous `PAGE_FLAG_PRESENT` `(start, end)` ranges.
- **`maps.rs`:** Streaming `/proc/<pid>/maps` parser (`parse_maps`), excludes unsafe kernel/device mappings, filters non-resident regions via `V2pReader`, merges contiguous regions (`merged_count`), classifies region tags (`A`, `CA`, `CB`, `CD`, `CH`, `JH`, `S`, `AS`, `NL`, `F`, `CUSTOM`), and resolves module bases (including Android split-APK `!` paths).
- **`scanner.rs`:** Multi-threaded (`std::thread::scope`, up to 8 workers) 4 MB chunked scanner with ARM64 NEON intrinsics (`std::arch::aarch64::*`) for `Byte`, `Short`, `Int`, `Long`, `Float`, `Double` (scalar `Float16`), NEON XOR-pair `Obscured*` (`vld2q_*` + `veorq_*`), 12B/16B `BigDouble`, homogeneous/heterogeneous `Group` scans, relative/unknown filters, and optional in-kernel scan (`ScanEngineMode`: `RustNeon = 0`, `Kernel = 1`, `Auto = 2`) via `kpm::scan_kernel`.
- **`editor.rs`:** V2P-verified writes (`write_raw_bytes`, `write_value`, `write_obscured`, `write_big_double`, `batch_write`, `batch_write_binary`), multi-level 64-bit pointer chain resolver (`resolve_pointer_chain`), static XOR read/write, and `FreezeEngine` (dedicated `"hmem-freeze"` worker thread using `Arc<(Mutex<FreezeState>, Condvar)>`, waking every `100ms` to dispatch per-PID `kpm::write_batch`).
- **`script.rs`:** Sandboxed Lua 5.4 runtime (`mlua`), cooperative interruption via `SCRIPT_CANCELLED` atomic + instruction/line hook, exposing `hmem.*`, `canvas.*`, and 2D Canvas compatibility shims (`getWH`, `newPaint`, `newView`), plus binary Canvas packet encoding.
- **`types.rs` / `logger.rs`:** Core value/session types, IEEE 754 `f16` conversion, little-endian binary IPC encoders (`encode_regions_binary`, `encode_matches_page_binary`), and `__android_log_write` logging.

### Layer 4: Kernel Space (External `HMKPM` Module)
- Dispatches `SYS_GETRESUID` (`148`) requests on `aarch64` to perform direct `task->mm->pgd` MMU page-table walks for `PROBE`, `READ`, `WRITE`, `READ_BATCH`, `WRITE_BATCH`, `V2P_BATCH`, and `SCAN_KERNEL`.

---

## 5. End-to-End Data Flows

### A. Memory Scan & Refinement (`*Fast` Binary Pipeline)
1. `MemoryScanTab` (`VirtualTextField`) -> `onNextScanClicked` (`MemoryScanUtils.kt`) parses input expressions (direct address/pointer `[base]+off`, `obscured:`/`xor:`, `bigdouble:`, tolerance `+-`/`±`, scaled `*`, group `;`, range `..`/`~`, or exact/relative/unknown).
2. `MemoryScanManager` dispatches to `MemoryEngine.*Fast` on `Dispatchers.IO`.
3. `NativeBridge.*Fast` -> Binder `IHMemService` -> `HMemService` (UID 0) -> `NativeBridge.native*Fast` -> `lib.rs` -> `maps.rs` (`get_target_regions`) -> `scanner.rs` (`kpm::scan_kernel` or multi-threaded NEON over `V2pReader::get_present_ranges`).
4. Matches stay in Rust `SESSIONS[sessionId]` as `Vec<CompactMatch>`; only `count` returns across IPC. `MemoryEngine` fetches pages via `NativeBridge.getResultsPage(sessionId, offset, limit)` (`encode_matches_page_binary` -> `ByteBuffer` `decodeMatchesPage` -> `MatchInfo`).

### B. Memory Editing & Freezing
- **Batch Write:** `MemoryEditor.writeAll` checks `isLockless` -> `NativeBridge.encodeBatchWrites` -> `IHMemService.nativeBatchWriteBinary` -> `editor::batch_write_binary` -> V2P residency check -> `kpm::write_batch`.
- **Freeze Loop:** `NativeBridge.freezeAddress` -> `FreezeEngine::freeze` (V2P-verified initial write + map insert) -> `"hmem-freeze"` thread batch-writes every `100ms`. `AddressTableTab` polls `isAddressFrozen` and refreshes values every `1500ms`.

### C. Lua Scripting & Reverse UI/Canvas Callbacks
1. `LuaScriptTab` calls `MemoryEngine.runLuaScript(pid, script)` on `Dispatchers.IO`, passing `LuaUiBridge.asAidlCallback()` (`ILuaUiCallback.Stub`).
2. `lib.rs` wraps the callback in `JniAidlUiBridge` (`JavaVM` + JNI `Global` ref) and runs `script::run_script`.
3. **Synchronous UI calls** (`alert`, `prompt`, `choice`, `multi_choice`) invoke `ILuaUiCallback` over Binder; `LuaUiBridge` blocks the Binder worker thread on `runBlocking { deferred.await() }` until the Compose dialog completes the `CompletableDeferred`.
4. **Asynchronous UI & Canvas calls** (`postLog`, `showToast`, `setDynamicMenu`, `canvasDrawBinary`) use `oneway` Binder calls updating `LuaUiBridge` `StateFlow`s. Binary Canvas packets (`CANVAS_MODE_REPLACE`/`APPEND`, `CANVAS_CMD_TEXT`/`LINE`/`RECT`/`CIRCLE`) decode into pre-resolved `CanvasDrawCommand` objects rendered in `LuaCanvasOverlay` via `Modifier.drawBehind`.

---

## 6. Code Standards & Legacy / Refactoring Policy

### Code Conventions
- **Languages:** Kotlin, Rust 2024, and pure C only (no C++). Code, comments, docs, and commits strictly in **English**.
- **Compiler Rigor:** Kotlin `allWarningsAsErrors = true`, `extraWarnings = true`; Rust `#![deny(unsafe_op_in_unsafe_fn)]`, mandatory `// SAFETY:` comments, `catch_unwind` at every JNI export, `clippy -D warnings`, `cargo fmt --all`.
- **Binary IPC Preference:** Use compact little-endian `ByteBuffer` formats for hot-path IPC (`*Fast`, `nativeGetMemoryMapsBinary`, `nativeGetResultsPage`, `nativeBatchWriteBinary`, `canvasDrawBinary`).
- **IME-Free Overlay Input:** Overlay forms must use `VirtualTextField` + `LocalKeyboardController` instead of triggering the system IME.

### Legacy Code & Refactoring Boundaries
- **No Untouchable Modules:** No file or module in this repository is strictly frozen/off-limits; all subsystems may be refactored provided functional behavior, HMKPM C-ABI compatibility (`kpm.rs`), and Zero-FD kernel channel invariants remain intact.
- **Deprecated Legacy JSON APIs:** The older JSON-based scan, filter, maps, and batch-write endpoints (`nativeScanMemory`, `nativeScanRange`, `nativeScanGroup`, `nativeScanObscured`, `nativeScanBigDouble`, `nativeFilterMatches*`, `nativeGetMemoryMaps`, `nativeBatchWrite`) across `IHMemService.aidl`, `NativeBridge.kt`, `MemoryEngine.kt`, and `lib.rs` are **deprecated legacy code**:
  - All new features must exclusively use the binary / `*Fast` paginated APIs.
  - Legacy JSON methods should be removed incrementally during cleanup.
  - Keep JSON only where appropriate for low-frequency structured responses (`nativeGetHmkpmVersionInfo`, `nativeRunLuaScript` execution results, dynamic Lua menu definitions).

---

## 7. Roadmap & Known Gaps / Ambiguities

### Explicitly Deferred (Next Phase)
- Fix group scan
- Major new analysis UI features (Memory Viewer / Hex Editor, Pointer Scanner, ARM64 Disassembler).
- Multi-script concurrent execution in `script.rs` (currently single global `SCRIPT_CANCELLED` atomic).
