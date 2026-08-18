# HMKPM Kernel Protocol Specification

**HMKPM (HuntMemory KernelPatch Module)** is a kernel-space extension designed for the **KernelPatch** framework on **ARM64 Android**. It provides direct, high-speed virtual memory access into target processes by traversing the kernel MMU structures directly.

---

## 📡 1. Communication Channel

HMKPM intercepts the Linux `SYS_GETRESUID` syscall (**Syscall ID 148** on `aarch64`):

```c
long syscall(148, unsigned long magic, void *request_buffer, unsigned long buffer_len);
```

Commands are dispatched using specific 64-bit magic operation identifiers.

### Magic Command Identifiers

| Identifier | Value (Hex) | Purpose |
| :--- | :--- | :--- |
| `HMKPM_MAGIC` | `0x0048_4D4B_504D` | Module Probe / Liveness Check |
| `HMKPM_MAGIC_READ` | `0x0048_4D4B_504E` | Single Virtual Address Memory Read |
| `HMKPM_MAGIC_WRITE` | `0x0048_4D4B_504F` | Single Virtual Address Memory Write |
| `HMKPM_MAGIC_READ_BATCH` | `0x0048_4D4B_5050` | Batch Memory Read |
| `HMKPM_MAGIC_WRITE_BATCH` | `0x0048_4D4B_5051` | Batch Memory Write |

---

## 🏗️ 2. Memory Struct Layouts & Alignment

All structures crossing the userspace ↔ kernel boundary are strictly aligned to 8-byte boundaries using `#[repr(C, align(8))]` to avoid unaligned access penalties on ARM64.

### 1. Single Request Header (`HmkpmReq`) — 24 Bytes

```rust
#[repr(C, align(8))]
pub struct HmkpmReq {
    pub pid: i32,    // Offset 0x00: Target process PID
    pub _pad: u32,   // Offset 0x04: 4-byte explicit padding
    pub addr: u64,   // Offset 0x08: 64-bit Target virtual address
    pub size: u64,   // Offset 0x10: Number of bytes to read/write
}
```

### 2. Batch Request Header (`HmkpmBatchHdr`) — 24 Bytes

```rust
#[repr(C, align(8))]
pub struct HmkpmBatchHdr {
    pub pid: i32,        // Offset 0x00: Target process PID
    pub _pad: u32,       // Offset 0x04: 4-byte explicit padding
    pub count: u64,      // Offset 0x08: Number of batch entries
    pub data_total: u64, // Offset 0x10: Total payload data length in bytes
}
```

### 3. Batch Entry Descriptor (`HmkpmBatchEntry`) — 16 Bytes

```rust
#[repr(C, align(8))]
pub struct HmkpmBatchEntry {
    pub addr: u64,   // Offset 0x00: 64-bit Target virtual address
    pub size: u64,   // Offset 0x08: Requested size / actual size returned
}
```

---

## 📦 3. Batch Payload Buffer Memory Layout

For batch operations (`HMKPM_MAGIC_READ_BATCH` and `HMKPM_MAGIC_WRITE_BATCH`), requests and data are packed into a single contiguous buffer:

```text
+-------------------------------------------------------------------------+
| HmkpmBatchHdr (24 bytes)                                                |
+-------------------------------------------------------------------------+
| HmkpmBatchEntry[0] (16 bytes)                                           |
| HmkpmBatchEntry[1] (16 bytes)                                           |
| ...                                                                     |
| HmkpmBatchEntry[count - 1] (16 bytes)                                   |
+-------------------------------------------------------------------------+
| Data Payload Area (contiguous byte streams for entry 0 .. count - 1)    |
+-------------------------------------------------------------------------+
```

### Buffer Size Calculation

$$\text{Total Buffer Size} = \text{sizeof}(\text{HmkpmBatchHdr}) + (\text{count} \times \text{sizeof}(\text{HmkpmBatchEntry})) + \text{data\_total}$$

---

## ⚡ 4. Protocol Limits

| Constraint | Maximum Value | Description |
| :--- | :--- | :--- |
| `HMKPM_MAX_SINGLE_SIZE` | 64 MB (`0x0400_0000`) | Maximum single read/write chunk |
| `HMKPM_MAX_ENTRY_SIZE` | 64 MB (`0x0400_0000`) | Maximum individual entry size in a batch |
| `HMKPM_MAX_BATCH_ENTRIES` | 65,536 (`0x10000`) | Maximum entries per batch call |
| `HMKPM_MAX_BATCH_TOTAL_SIZE`| 128 MB (`0x0800_0000`) | Maximum combined payload across a batch |

---

## 🛡️ 5. MMU Resolution & Memory Safety

1. **Kernel Page Table Walking**:
   - The kernel resolves addresses by inspecting the target task's memory descriptor: `task->mm->pgd`.
   - Bypasses userspace `process_vm_readv` / `ptrace` limitations and security hooks.

2. **Stack Allocation Optimization (Small I/O)**:
   - For transfers $\le 256$ bytes, `kpm.rs` allocates an 8-byte aligned stack buffer (`AlignedStackBuf`), avoiding heap allocator overhead for high-frequency freeze and read operations.

3. **Userspace Memory Locking (`mlock`)**:
   - Buffers passed across the syscall interface during heavy batch scans are locked in RAM to prevent the Linux kernel page-reclaim subsystem from swapping out the receiving buffers mid-execution.
