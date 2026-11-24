# HMKPM Kernel Protocol Specification (v3.0.0)

**HMKPM (HuntMemory KernelPatch Module)** is a kernel-space extension for the **KernelPatch** framework on **ARM64 Android**. It provides direct virtual memory I/O, page-table inspection, hardware region enumeration, batched pointer-chain resolution, and in-kernel memory scanning by walking the target task's MMU page tables (`task->mm->pgd`) directly.

---

## 1. Communication Channel

HMKPM dispatches requests through the Linux `SYS_GETRESUID` syscall (**Syscall ID 148** on `aarch64`):

```c
long syscall(148, unsigned long magic, void *user_buffer, unsigned long total_len);
```

Commands are dispatched using 64-bit magic identifiers (caller must run as `UID 0`):

### Magic Command Identifiers

| Identifier | Value (Hex) | Purpose |
| :--- | :--- | :--- |
| `HMKPM_MAGIC` | `0x0048_4D4B_504D` | Module Probe / Version, Telemetry & Feature Discovery |
| `HMKPM_MAGIC_READ` | `0x0048_4D4B_504E` | Single Virtual Address Memory Read |
| `HMKPM_MAGIC_WRITE` | `0x0048_4D4B_504F` | Single Virtual Address Memory Write |
| `HMKPM_MAGIC_READ_BATCH` | `0x0048_4D4B_5050` | Coalesced Batch Memory Read |
| `HMKPM_MAGIC_WRITE_BATCH` | `0x0048_4D4B_5051` | Batch Memory Write |
| `HMKPM_MAGIC_V2P_BATCH` | `0x0048_4D4B_5052` | Batch Virtual-to-Physical & Page-Flag Inspection |
| `HMKPM_MAGIC_SCAN_KERNEL` | `0x0048_4D4B_5053` | In-Kernel Direct MMU Memory Scan (with values & resume cursor) |
| `HMKPM_MAGIC_READ_CHAIN` | `0x0048_4D4B_5054` | Batched In-Kernel Pointer-Chain Resolution & Terminal Read |
| `HMKPM_MAGIC_SCAN_REFINE` | `0x0048_4D4B_5055` | In-Kernel Candidate Refinement & Compaction |
| `HMKPM_MAGIC_ENUM_REGIONS` | `0x0048_4D4B_5056` | Hardware Page-Table Mapped-Region Enumeration |

---

## 2. Memory Struct Layouts & Alignment

All structures crossing the userspace/kernel boundary are strictly 8-byte aligned (`#[repr(C, align(8))]`) with compile-time size and offset assertions matching `include/hmkpm_uapi.h`.

### 1. Version & Telemetry Info (`HmkpmVersionInfo`) — 80 Bytes

```rust
#[repr(C, align(8))]
pub struct HmkpmVersionInfo {
    pub magic: u64,             // Offset 0x00: HMKPM_MAGIC
    pub struct_size: u32,       // Offset 0x08: sizeof == 80
    pub version_code: u32,      // Offset 0x0C: (major << 16) | (minor << 8) | patch
    pub version_major: u16,     // Offset 0x10: Major version (3)
    pub version_minor: u16,     // Offset 0x12: Minor version (0)
    pub version_patch: u16,     // Offset 0x14: Patch version (0)
    pub is_lockless: u8,        // Offset 0x16: 1 if running in lockless read-only mode
    pub lock_source: u8,        // Offset 0x17: 0=lockless, 1=btf, 2=gki_validated, 3=disasm_legacy
    pub mmap_lock_offset: i32,  // Offset 0x18: Resolved mm_struct->mmap_lock offset, or -1
    pub page_size: u32,         // Offset 0x1C: Hardware page granule (4096 or 16384)
    pub kver: u32,              // Offset 0x20: KernelPatch kver (major << 16 | minor << 8 | patch)
    pub va_bits: u8,            // Offset 0x24: TTBR0 user VA width (36..48)
    pub tbi0: u8,               // Offset 0x25: 1 if TCR_EL1.TBI0 is active
    pub sw_pan_mode: u8,        // Offset 0x26: 0=off, 1=kernel_uaccess, 2=manual_ttbr0
    pub fsnotify_mode: u8,      // Offset 0x27: 0=off, 1=global, 2=caller_pids
    pub features: u64,          // Offset 0x28: Active HMKPM_FEATURE_* bitmask (bits 0..11)
    pub reserved: [u64; 2],     // Offset 0x30: Reserved (zeroed)
    pub version_str: [u8; 16],  // Offset 0x40: NUL-terminated version string ("3.0.0")
}
```

### 2. Single Request Header (`HmkpmReq`) — 24 Bytes

```rust
#[repr(C, align(8))]
pub struct HmkpmReq {
    pub pid: i32,    // Offset 0x00: Target process PID
    pub _pad: u32,   // Offset 0x04: 4-byte explicit padding
    pub addr: u64,   // Offset 0x08: 64-bit target virtual address
    pub size: u64,   // Offset 0x10: Number of bytes to read/write
}
```

### 3. Batch Request Header & Entry (`HmkpmBatchHdr` 24B / `HmkpmBatchEntry` 16B)

```rust
#[repr(C, align(8))]
pub struct HmkpmBatchHdr {
    pub pid: i32,        // Offset 0x00: Target process PID
    pub _pad: u32,       // Offset 0x04: Padding
    pub count: u64,      // Offset 0x08: Number of batch entries
    pub data_total: u64, // Offset 0x10: Total data buffer length in bytes
}

#[repr(C, align(8))]
pub struct HmkpmBatchEntry {
    pub addr: u64,       // Offset 0x00: 64-bit target virtual address
    pub size: u64,       // Offset 0x08: Requested size / actual size transferred
}
```

### 4. V2P Batch Header & Entry (`HmkpmV2pHdr` 16B / `HmkpmV2pEntry` 24B)

```rust
#[repr(C, align(8))]
pub struct HmkpmV2pHdr {
    pub pid: i32,        // Offset 0x00: Target process PID
    pub _pad: u32,       // Offset 0x04: Padding
    pub count: u64,      // Offset 0x08: Number of V2P entries
}

#[repr(C, align(8))]
pub struct HmkpmV2pEntry {
    pub va: u64,         // Offset 0x00: Target virtual address
    pub pa: u64,         // Offset 0x08: Resolved physical address (output)
    pub flags: u32,      // Offset 0x10: HMKPM_PAGE_FLAG_{PRESENT,RO,DIRTY,BLOCK}
    pub page_size: u32,  // Offset 0x14: Hardware leaf span size (4 KiB, 16 KiB, 2 MiB, etc.)
}
```

### 5. Kernel Scan Header (`HmkpmScanHdr` 64B), Range (16B) & Value Match (16B)

```rust
#[repr(C, align(8))]
pub struct HmkpmScanHdr {
    pub pid: i32,                 // Offset 0x00
    pub scan_type: u8,            // Offset 0x04: I8..U64, F32, F64, BYTES
    pub op: u8,                   // Offset 0x05: EXACT, RANGE, MASK, NEQ, NOT_RANGE
    pub align: u8,                // Offset 0x06: 0 (natural) or 1/2/4/8
    pub flags: u8,                // Offset 0x07: COUNT_ALL | WITH_VALUES | RESUME
    pub range_count: u32,         // Offset 0x08
    pub max_matches: u32,         // Offset 0x0C
    pub pattern_len: u64,         // Offset 0x10
    pub criteria: HmkpmScanCriteria, // Offset 0x18 (16 bytes)
    pub matches_found: u64,       // Offset 0x28: Output count
    pub resume_va: u64,           // Offset 0x30: In/Out resume cursor VA
    pub resume_range_idx: u32,    // Offset 0x38: In/Out resume cursor range index
    pub scan_status: u32,         // Offset 0x3C: 0=COMPLETE, 1=TRUNCATED
}
```

Contiguous buffer layout:
`[ HmkpmScanHdr (64B) | HmkpmScanRange[range_count] | pattern[pattern_len] | mask[pattern_len] | matches (u64[max_matches] or HmkpmScanValMatch[max_matches]) ]`

### 6. Pointer Chain Header & Request (`HmkpmChainHdr` 24B / `HmkpmChainReq` 32B)

Contiguous buffer layout:
`[ HmkpmChainHdr (24B) | HmkpmChainReq[count] | i64 offsets[total_offsets] | data[data_total] ]`

### 7. Scan Refinement Header (`HmkpmRefineHdr` 48B)

Contiguous buffer layout:
`[ HmkpmRefineHdr (48B) | items (u64[count] or HmkpmScanValMatch[count]) ]`

Supports `EXACT`, `NEQ`, `RANGE`, `NOT_RANGE`, `MASK`, and delta operators (`CHANGED`, `UNCHANGED`, `INCREASED`, `DECREASED`) with in-place compaction when `HMKPM_REFINE_FLAG_WITH_VALUES` is set.

### 8. Mapped-Region Enumeration (`HmkpmEnumHdr` 40B / `HmkpmRegionEntry` 24B)

Contiguous buffer layout:
`[ HmkpmEnumHdr (40B) | HmkpmRegionEntry[max_regions] ]`

Walks the hardware page tables with O(1) empty-subtree skipping and returns coalesced mapped ranges with `READ`, `WRITE`, `EXEC`, and `HAS_BLOCK` flags plus a `next_va` pagination cursor.

---

## 3. Protocol Limits

| Constraint | Maximum Value | Description |
| :--- | :--- | :--- |
| `HMKPM_MAX_SINGLE_SIZE` | 64 MiB | Maximum single read/write transfer |
| `HMKPM_MAX_ENTRY_SIZE` | 64 MiB | Maximum individual entry size in a batch |
| `HMKPM_MAX_BATCH_ENTRIES` | 65,536 | Maximum entries per batch call |
| `HMKPM_MAX_BATCH_TOTAL_SIZE` | 128 MiB | Maximum combined data size across a batch |
| `HMKPM_MAX_V2P_ENTRIES` | 65,536 | Maximum V2P entries per batch call |
| `HMKPM_MAX_SCAN_RANGES` | 4,096 | Maximum ranges per `SCAN_KERNEL` call |
| `HMKPM_MAX_PATTERN_LEN` | 256 bytes | Maximum byte-pattern length for `SCAN_TYPE_BYTES` |
| `HMKPM_MAX_SCAN_MATCHES` | 1,048,576 | Maximum matches returned per `SCAN_KERNEL` call |
| `HMKPM_MAX_CHAIN_COUNT` / `DEPTH` | 1,024 / 32 | Maximum chains per call and hops per chain |
| `HMKPM_MAX_REFINE_COUNT` | 1,048,576 | Maximum candidate items per `SCAN_REFINE` call |
| `HMKPM_MAX_ENUM_REGIONS` | 16,384 | Maximum entries per `ENUM_REGIONS` page |

---

## 4. MMU Resolution & Safety Properties

1. **Direct MMU Page-Table Translation**:
   - Resolves target virtual addresses through `task->mm->pgd` with MAIR normal-memory validation, AP/UXN permission checks, and TBI0 pointer untagging without opening `/proc/<pid>/mem` or `/proc/<pid>/pagemap`.
2. **Lockless Read-Only Mode**:
   - When `mm_struct->mmap_lock` cannot be resolved via BTF, validated GKI layout, or legacy disassembly, HMKPM activates Lockless read-only mode (`is_lockless = 1`, `HMKPM_FEATURE_LOCKLESS`), preserving full read/scan/V2P/enumeration capabilities while rejecting writes with `-EOPNOTSUPP` (errno 95).
3. **Zero-Copy Stack & Aligned Heap Buffers**:
   - Small single reads/writes ($\le 256$ bytes) use an 8-byte aligned `AlignedStackBuf`; larger transfers and scan loops use reusable 8-byte aligned heap buffers (`AlignedHeapBuf` / `KpmReadBuffer`).
