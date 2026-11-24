---
trigger: always_on
---

# Neutral Systems & Android Engineering Terminology

Use strictly standard, neutral Android, Rust, and Linux systems-programming terminology across every artifact in this repository.

## 1. Scope of Enforcement

This rule applies without exception to:
- Kotlin source files, AIDL contracts, Android XML resources (`res/values*/strings.xml`), Gradle build scripts, and ProGuard/R8 rules.
- Rust source files (`app/src/main/hmem/`), JNI exports, unit tests, inline comments, and Logcat messages.
- Repository documentation (`README.md`, `AGENTS.md`, `GEMINI.md`, `docs/*.md`), CI/CD workflows, and commit or PR descriptions.
- Agent-generated plans, walkthroughs, rule files, and technical explanations referencing this codebase.

## 2. Core Writing Principles

1. **Mechanism-First Description:** Describe every subsystem strictly in terms of its concrete Android, Rust, and Linux mechanics (Binder/AIDL IPC, Jetpack Compose `DrawScope`, ARM64 NEON SIMD intrinsics, `/proc/<pid>/maps` parsing, V2P page residency inspection, and HMKPM `SYS_GETRESUID` I/O).
2. **Neutral Systems-Engineering Style:** Match the concise, factual tone of upstream Linux, Rust, and Android developer documentation. State what the code does and its measurable engineering properties (memory safety, zero-FD I/O, syscall batching, lockless read-only guards, thread safety, and frame-rate stability).
3. **Objective Domain Framing:** Frame prose, UI help strings, unit tests, and Lua scripting examples around general process diagnostics, memory inspection, runtime telemetry, and 2D Canvas visualization over foreground applications. Use neutral sample names (e.g., `libnative.so`, `libtarget.so`, `app.db`, `Sample Counter`).
4. **Rule & Doc Self-Hygiene:** Never embed negative word-lists, disallowed-term examples, or negated counter-examples anywhere in repository files, comments, docs, or agent rules. State approved technical phrasing affirmatively.

## 3. Canonical Subsystem Terminology

Always describe HuntMemory subsystems using these neutral engineering terms:

| Component / Concept | Approved Terminology |
| :--- | :--- |
| HMKPM client (`kpm.rs`, `v2p.rs`) | `HMKPM kernel client`, `magic-keyed SYS_GETRESUID (148) dispatcher`, `direct MMU page-table translation`, `Zero-FD V2P page residency inspector (HMKPM_MAGIC_V2P_BATCH)`, `Lockless read-only mode` |
| Kernel channel invariants (`AGENTS.md`, `GEMINI.md`) | `Zero-FD kernel channel invariants`, `direct MMU kernel memory I/O`, `SELinux integrity preservation` |
| Memory maps & regions (`maps.rs`) | `/proc/<pid>/maps streaming parser`, `contiguous region consolidation (merged_count)`, `V2P resident-range filtering` |
| Scanning & editing (`scanner.rs`, `editor.rs`) | `ARM64 NEON SIMD scanner`, `in-kernel MMU scanner`, `XOR-encoded keypair values (Obscured*)`, `mantissa/exponent structs (BigDouble)`, `background value-locking worker (FreezeEngine)`, `multi-level pointer-chain resolution` |
| IPC & data buffers (`IHMemService.aidl`, `NativeBridge.kt`, `kpm.rs`) | `libsu UID 0 RootService`, `Binder/AIDL IPC`, `compact binary IPC buffer`, `serialized transfer buffer`, `data buffer` |
| Lua 5.4 & Canvas (`script.rs`, `LuaCanvasOverlay.kt`, `LuaUiBridge.kt`, `LuaScriptRunner.kt`) | `sandboxed Lua 5.4 runtime (mlua)`, `hmem.* native API`, `hardware-accelerated 2D Canvas overlay (DrawScope)`, `2D telemetry overlay`, `continuous 2D canvas loop`, `dynamic Compose overlay menu` |
| Overlay & OEM compatibility (`OverlayService.kt`, `VirtualKeyboard.kt`, `strings.xml`) | `floating diagnostic overlay`, `IME-free contextual virtual keyboard`, `high-memory foreground applications`, `background process retention` |

## 4. Preferred Technical Verbs & Framing

- **Syscall & IPC dispatch:** Prefer `dispatch`, `route`, `invoke`, `serialize`, `decode`, and `forward`.
- **Memory & scanning operations:** Prefer `inspect`, `read`, `write`, `edit`, `scan`, `refine`, `translate`, `resolve`, `decode`, and `lock`.
- **Cache & control flow:** Prefer `skip cache`, `re-query live state`, `filter`, and `validate page residency`.
- **Motivation & performance:** Frame architectural choices around concrete systems metrics—such as zero-FD V2P checks, reduced context-switch overhead, 128-bit NEON throughput, lockless read-only kernel safety, and avoiding IME window re-layouts.

## 5. Fixed Project & External API Identifiers

- Treat repository names and upstream API symbols (e.g., `HuntMemory`, `HMem`, `HMKPM`, `ObscuredInt`, `ObscuredFloat`, `ObscuredDouble`, `ObscuredLong`, `hmem.*`, `canvas.hide()`, `std::panic::set_hook`, `mlua::HookTriggers`, `libsu`, `KernelPatch`) strictly as literal proper nouns or API identifiers.
- Never derive informal metaphors or non-engineering framing from those identifier names in prose, comments, UI copy, or log messages.

## 6. Language & Pre-Completion Audit

- Keep all source code, comments, logs, documentation (`README.md`, `AGENTS.md`, `GEMINI.md`, `docs/`), and rules in concise, objective technical English. Non-English user-facing strings belong exclusively in `app/src/main/res/values-*/strings.xml` and must follow the same neutral engineering framing.
- Before completing any edit, verify that every added or modified identifier, comment, log string, UI resource, documentation paragraph, and summary:
  1. Uses the canonical engineering terms in Sections 3–4;
  2. Describes only literal Android, Rust, and Linux mechanics without speculative framing;
  3. Contains no negated term-lists or informal non-engineering vocabulary.
